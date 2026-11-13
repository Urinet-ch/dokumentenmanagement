import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { Observable, Subject, catchError, debounceTime, of, switchMap } from 'rxjs';
import { Dokument, Dokumentart, Kontakt } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { fehlerAus } from '../eingangskorb/entwurf';
import {
  FINANZ_FELDER,
  Formular,
  GRUND_MAX,
  TextFeld,
  VERTRAG_FELDER,
  ZusatzFeld,
  aenderungAus,
  feldText,
  formularAus,
  formularFeld,
  zusatzFelder,
} from './aenderung';
import { DokumentPort } from './dokument-port';

const NEU_LADEN = new Set(['STATUS_KONFLIKT', 'VERSION_KONFLIKT', 'NICHT_GEFUNDEN']);
const IM_FORMULAR: Record<string, string> = {
  BELEGNUMMER_VERGEBEN: 'belegnummer',
  PARTNER_UNBEKANNT: 'partnerId',
  ZUSATZ_UNGUELTIG: '',
  ANFRAGE_UNGUELTIG: '',
};

interface Eingabe {
  feld: TextFeld;
  typ: 'text' | 'date';
  modus?: string;
}

const TYPEN: Partial<Record<TextFeld, Eingabe['typ']>> = {
  faelligkeit: 'date',
  gueltigAb: 'date',
  gueltigBis: 'date',
  naechsteFrist: 'date',
};

/**
 * Metadaten bearbeiten (A6, Req 10.1): Grundfelder, Finanz- und Vertragsangaben je nach Gruppen der Art, Zusatzfelder
 * aus dem Zusatz-Schema und der Grund, der bei archivierten Dokumenten Pflicht ist. Die Vertraulichkeit erscheint nur
 * mit A8. Gesendet werden nur geänderte Felder (design.md «Umsetzung 3.5»).
 */
@Component({
  selector: 'app-metadaten-formular',
  imports: [MatButtonModule, NgTemplateOutlet],
  templateUrl: './metadaten-formular.html',
  styleUrl: './metadaten-formular.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadatenFormular {
  private readonly port = inject(DokumentPort);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  readonly dokument = input.required<Dokument>();
  readonly arten = input<readonly Dokumentart[]>([]);
  /** Art aus dem Eingangskorb: startet als gewählte Art und geht beim Speichern als Artwechsel mit (Review #106). */
  readonly artVorbelegung = input<string | null>(null);
  readonly gespeichert = output<Dokument>();
  readonly abgebrochen = output<void>();
  /** Das Dokument hat sich inzwischen geändert; die Ansicht lädt neu und zeigt die Meldung. */
  readonly veraltet = output<string>();

  protected readonly texte = TEXTE.dokument.bearbeiten;
  protected readonly korb = TEXTE.eingangskorb;
  protected readonly feldNamen = TEXTE.eingangskorb.feld;
  protected readonly vertraulichkeitNamen = TEXTE.dokument.vertraulichkeiten;
  protected readonly richtungNamen = TEXTE.eingangskorb.richtung;
  protected readonly richtungen = ['EINNAHME', 'AUSGABE'];
  protected readonly vertraulichkeiten = ['NORMAL', 'INTERN', 'PERSONAL'];
  protected readonly grundMax = GRUND_MAX;
  protected readonly feldText = feldText;

  private readonly start = computed(() => formularAus(this.dokument()));
  protected readonly formular = linkedSignal<Formular>(() => {
    const start = this.start();
    const art = untracked(this.artVorbelegung);
    return art ? { ...start, art } : start;
  });
  protected readonly laeuft = signal(false);
  protected readonly fehler = signal('');
  protected readonly fehlerFelder = signal<readonly string[]>([]);
  protected readonly treffer = signal<readonly Kontakt[] | null>(null);
  private readonly suche = new Subject<string>();

  protected readonly id = computed(() => this.dokument().id);
  protected readonly archiviert = computed(() => this.dokument().status === 'ARCHIVIERT');
  protected readonly darfVertraulichkeit = computed(() => this.anmeldung.darf('A8'));
  /** Nur zur Benennung des Formulars; das Feld `titel` startet nie mit dem Dateinamen (Req 14.10). */
  protected readonly titel = computed(
    () => anzeigeTitel(this.dokument().titel, null, this.dokument().dateiname).text,
  );
  protected readonly art = computed(() => this.arten().find((a) => a.code === this.formular().art));
  protected readonly vorbelegt = computed(() => {
    const code = this.artVorbelegung();
    return code && code === this.formular().art && code !== this.dokument().art ? (this.art()?.name ?? code) : null;
  });
  protected readonly artenWahl = computed(() =>
    this.arten().filter((a) => a.aktiv || a.code === this.formular().art || a.code === this.dokument().art),
  );
  protected readonly finanz = computed<Eingabe[]>(() =>
    this.art()?.gruppen.includes('finanz')
      ? FINANZ_FELDER.filter((f) => f !== 'richtung').map((feld) => ({
          feld,
          typ: TYPEN[feld] ?? 'text',
          modus: feld === 'betrag' ? 'decimal' : undefined,
        }))
      : [],
  );
  protected readonly mitRichtung = computed(() => this.art()?.gruppen.includes('finanz') ?? false);
  protected readonly vertrag = computed<Eingabe[]>(() =>
    this.art()?.gruppen.includes('vertrag')
      ? VERTRAG_FELDER.map((feld) => ({ feld, typ: TYPEN[feld] ?? 'text' }))
      : [],
  );
  protected readonly zusatz = computed<ZusatzFeld[]>(() => zusatzFelder(this.art()));

  constructor() {
    this.suche
      .pipe(
        debounceTime(300),
        switchMap((q): Observable<readonly Kontakt[] | null> =>
          q.length < 2 ? of(null) : this.port.kontakte(q).pipe(catchError(() => of([]))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((liste) => this.treffer.set(liste));
    this.fokus('[data-fokus="bearbeiten"]');
  }

  protected beschriftung(feld: string): string {
    return this.feldNamen[feld] ?? feldText(feld);
  }

  protected pflicht(feld: string): boolean {
    const snake = feld === 'partnerId' ? 'partner' : feld.replace(/[A-Z]/g, (b) => `_${b.toLowerCase()}`);
    return this.art()?.pflichtfelder.includes(snake) ?? false;
  }

  protected ungueltig(feld: string): boolean {
    return this.fehlerFelder().includes(feld);
  }

  protected textWert(feld: TextFeld): string {
    return this.formular()[feld];
  }

  protected wert(ereignis: Event): string {
    return (ereignis.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement).value;
  }

  protected setze(feld: TextFeld, wert: string): void {
    this.formular.update((f) => ({ ...f, [feld]: wert }));
  }

  protected setzeZusatz(name: string, wert: string): void {
    this.formular.update((f) => ({ ...f, zusatz: { ...f.zusatz, [name]: wert } }));
  }

  protected partnerSuchen(q: string): void {
    this.suche.next(q.trim());
  }

  protected partnerWaehlen(kontakt: Kontakt): void {
    const name = [kontakt.vorname, kontakt.name].filter(Boolean).join(' ');
    this.formular.update((f) => ({ ...f, partnerId: kontakt.id ?? '', partnerName: name }));
    this.suche.next('');
    this.treffer.set(null);
    this.fokus('[data-test="partner-gewaehlt"]');
  }

  protected partnerEntfernen(): void {
    this.formular.update((f) => ({ ...f, partnerId: '', partnerName: '' }));
    // Verwirft einen noch laufenden Debounce oder Abruf, damit keine veraltete Trefferliste erscheint (Review #106).
    this.suche.next('');
    this.treffer.set(null);
    this.fokus('[data-test="partner-suche"]');
  }

  protected kontaktName(kontakt: Kontakt): string {
    const name = [kontakt.vorname, kontakt.name].filter(Boolean).join(' ');
    return kontakt.ort ? `${name}, ${kontakt.ort}` : name;
  }

  protected abbrechen(): void {
    this.abgebrochen.emit();
  }

  protected speichern(): void {
    const ergebnis = aenderungAus(this.dokument(), this.start(), this.formular(), {
      art: this.art(),
      darfVertraulichkeit: this.darfVertraulichkeit(),
    });
    if ('pruefung' in ergebnis) {
      this.melden(ergebnis.pruefung.meldung, ergebnis.pruefung.felder);
      return;
    }
    this.laeuft.set(true);
    this.fehler.set('');
    this.fehlerFelder.set([]);
    this.port
      .aendern(this.id(), this.dokument().version, ergebnis.aenderung)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dokument) => {
          this.laeuft.set(false);
          this.gespeichert.emit(dokument);
        },
        error: (fehler: unknown) => {
          this.laeuft.set(false);
          this.fehlerBehandeln(fehler);
        },
      });
  }

  private fehlerBehandeln(roh: unknown): void {
    const fehler = fehlerAus(roh);
    const code = fehler.fehlercode ?? '';
    if (code === 'PFLICHTFELD_FEHLT') {
      this.melden(
        this.texte.fehlerPflichtfelder(fehler.felder.map(feldText).join(', ')),
        fehler.felder.map(formularFeld),
      );
      return;
    }
    if (NEU_LADEN.has(code) || fehler.status === 404 || fehler.status === 412) {
      this.veraltet.emit(this.texte.fehlercode[code] ?? this.texte.fehlercode['VERSION_KONFLIKT']);
      return;
    }
    if (code in IM_FORMULAR) {
      const feld = IM_FORMULAR[code];
      const felder = fehler.felder.length > 0 ? fehler.felder.map(formularFeld) : feld ? [feld] : [];
      this.melden(this.texte.fehlercode[code], felder);
      return;
    }
    this.melden(this.meldung(fehler.status, code), []);
  }

  private meldung(status: number, code: string): string {
    if (status === 0) {
      return TEXTE.eingangskorb.fehlerVerbindung;
    }
    if (status === 401) {
      return TEXTE.eingangskorb.fehlerSitzung;
    }
    if (status === 403) {
      return this.texte.fehlercode['NICHT_ERLAUBT'];
    }
    return this.texte.fehlercode[code] ?? TEXTE.eingangskorb.fehlerAllgemein;
  }

  private melden(meldung: string, felder: readonly string[]): void {
    this.fehler.set(meldung);
    this.fehlerFelder.set(felder);
    this.fokus('[data-test="formular-fehler"]');
  }

  private fokus(selektor: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selektor)?.focus(), {
      injector: this.injector,
    });
  }
}
