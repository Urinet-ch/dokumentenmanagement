import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { RouterLink } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { ImportLauf, ImportLaufMitZeilen, ImportZeile, ImporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  Entscheid,
  FELDER,
  Felder,
  ImportArt,
  entscheideAnfrage,
  feldDerSpalte,
  fehlerText,
  fehlercode,
  kontaktText,
  pflichtErfuellt,
  profilGilt,
  zahl,
  zeit,
  zuordnen,
  zuordnungAnfrage,
} from './import-logik';

/** Abstand der Statusabfrage, solange der Worker rechnet (Vorschau oder Übernahme). */
export const ABFRAGE_MS = 2000;
const SEITE = 50;

type Ergebnis = ImportZeile.ErgebnisEnum;
const ERGEBNISSE: readonly Ergebnis[] = ['NEU', 'GEAENDERT', 'UNVERAENDERT', 'DUBLETTE', 'FEHLER', 'UEBERSPRUNGEN'];

interface Zaehler {
  ergebnis: Ergebnis | null;
  anzahl: number;
}

/**
 * Ein Import-Lauf (Aufgabe 4.4): Zuordnung bestätigen (Req 13.2), Vorschau mit Zählern und Filter (Req 13.3, 13.5),
 * Dubletten entscheiden und übernehmen (Req 13.6, 13.7). Die Übernahme startet erst nach der Bestätigung.
 */
@Component({
  selector: 'app-import-lauf',
  imports: [MatButtonModule, MatCheckboxModule, RouterLink],
  templateUrl: './import-lauf.html',
  styleUrl: './import-lauf.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportLaufSeite {
  private readonly importe = inject(ImporteService);
  private readonly injector = inject(Injector);
  private abfrage?: ReturnType<typeof setTimeout>;
  private anfrage?: Subscription;

  readonly id = input.required<string>();

  protected readonly texte = TEXTE.importe;
  protected readonly zahl = zahl;
  protected readonly zeit = zeit;

  protected readonly antwort = signal<ImportLaufMitZeilen | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly filter = signal<Ergebnis | null>(null);
  protected readonly seite = signal(0);
  protected readonly sendet = signal(false);

  // Zuordnung (Req 13.2)
  protected readonly felder = signal<Felder>({});
  protected readonly profilGewaehlt = signal(false);
  private readonly profilVorschlag = signal<Felder | null>(null);
  private zuordnungFuer: string | null = null;

  // Dubletten-Entscheide bleiben über Filter und Seiten erhalten (Req 13.6)
  protected readonly entscheide = signal<ReadonlyMap<number, Entscheid>>(new Map());
  protected readonly bestaetigen = signal(false);

  protected readonly lauf = computed<ImportLauf | null>(() => this.antwort()?.lauf ?? null);
  protected readonly art = computed<ImportArt>(() => (this.lauf()?.art === 'verkaufschance' ? 'verkaufschance' : 'kontakt'));
  protected readonly status = computed(() => this.lauf()?.status ?? null);
  protected readonly uebernahmeLaeuft = computed(() => this.status() === 'VORSCHAU' && !!this.lauf()?.uebernommenVon);
  protected readonly zeilen = computed<readonly ImportZeile[]>(() => this.antwort()?.eintraege ?? []);
  protected readonly kontakte = computed(() => this.antwort()?.kontakte ?? {});

  protected readonly spalten = computed(() => this.antwort()?.spalten ?? []);
  protected readonly zielFelder = computed(() => FELDER[this.art()]);
  protected readonly profilMoeglich = computed(() => this.art() === 'kontakt' && !!this.profilVorschlag());
  protected readonly profilAktiv = computed(
    () => this.profilGewaehlt() && profilGilt(this.art(), this.profilVorschlag(), this.felder()),
  );
  protected readonly pflichtOk = computed(() => pflichtErfuellt(this.art(), this.felder()));
  protected readonly anzahlZugeordnet = computed(() => Object.keys(this.felder()).length);

  protected readonly zaehler = computed<Zaehler[]>(() => {
    const lauf = this.lauf();
    if (!lauf) {
      return [];
    }
    const anzahl: Record<Ergebnis, number> = {
      NEU: lauf.neu ?? 0,
      GEAENDERT: lauf.geaendert ?? 0,
      UNVERAENDERT: lauf.unveraendert ?? 0,
      DUBLETTE: lauf.dubletten ?? 0,
      FEHLER: lauf.fehler ?? 0,
      UEBERSPRUNGEN: lauf.uebersprungen ?? 0,
    };
    return [{ ergebnis: null, anzahl: lauf.zeilen ?? 0 }, ...ERGEBNISSE.map((e) => ({ ergebnis: e, anzahl: anzahl[e] }))];
  });
  protected readonly dublettenOffen = computed(() => Math.max(0, (this.lauf()?.dubletten ?? 0) - this.entscheide().size));
  protected readonly bilanz = computed(() => {
    const lauf = this.lauf();
    return (['angelegt', 'aktualisiert', 'ausgelassen', 'gekennzeichnet'] as const)
      .filter((schluessel) => schluessel !== 'gekennzeichnet' || lauf?.vollexport)
      .map((schluessel) => ({ schluessel, anzahl: lauf?.[schluessel] ?? 0 }));
  });

  private readonly bestaetigenTitel = viewChild<ElementRef<HTMLElement>>('bestaetigenTitel');
  private readonly statusTitel = viewChild<ElementRef<HTMLElement>>('statusTitel');

  constructor() {
    inject(DestroyRef).onDestroy(() => this.anhalten());
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.antwort.set(null);
        this.filter.set(null);
        this.seite.set(0);
        this.entscheide.set(new Map());
        this.zuordnungFuer = null;
        this.laden(id);
      });
    });
  }

  protected filtern(ergebnis: Ergebnis | null): void {
    this.filter.set(ergebnis);
    this.seite.set(0);
    this.laden(this.id());
  }

  protected blaettern(richtung: -1 | 1): void {
    this.seite.update((seite) => Math.max(0, seite + richtung));
    this.laden(this.id());
  }

  protected hatWeitere(): boolean {
    return (this.seite() + 1) * SEITE < (this.antwort()?.gesamt ?? 0);
  }

  protected bereich(): { von: string; bis: string; gesamt: string } {
    const gesamt = this.antwort()?.gesamt ?? 0;
    const von = this.seite() * SEITE + 1;
    return { von: zahl(von), bis: zahl(Math.min(gesamt, von + this.zeilen().length - 1)), gesamt: zahl(gesamt) };
  }

  // Zuordnung

  protected feldDerSpalte(position: number): string {
    return feldDerSpalte(this.felder(), position) ?? '';
  }

  protected zuordnenSpalte(position: number, feld: string): void {
    this.felder.update((felder) => zuordnen(felder, position, feld || null));
  }

  /** Das Profil setzt die Zuordnung auf den Vorschlag zurück; ändert man danach ein Profilfeld, gilt es nicht mehr. */
  protected profilWaehlen(gewaehlt: boolean): void {
    const vorschlag = this.profilVorschlag();
    if (gewaehlt && vorschlag) {
      this.felder.set({ ...vorschlag });
    }
    this.profilGewaehlt.set(gewaehlt);
  }

  protected beispiel(position: number): string {
    return this.zeilen()[0]?.werte?.[position] ?? '';
  }

  protected feldText(feld: string): string {
    return this.texte.feld[feld] ?? feld;
  }

  protected zuordnungBestaetigen(): void {
    const lauf = this.lauf();
    if (!lauf?.id || !this.pflichtOk() || this.sendet()) {
      return;
    }
    const anfrage = zuordnungAnfrage(lauf.id, this.art(), this.profilVorschlag(), this.profilGewaehlt(), this.felder());
    this.senden(this.importe.apiV1ImporteCrmZuordnungPut(anfrage, this.art()));
  }

  // Dubletten und Übernahme

  protected entscheidWert(nr: number | undefined): string {
    return (nr !== undefined && this.entscheide().get(nr)) || '';
  }

  protected entscheiden(nr: number | undefined, wert: string): void {
    if (nr === undefined) {
      return;
    }
    this.entscheide.update((alt) => {
      const neu = new Map(alt);
      if (wert === 'NEU' || wert === 'UEBERSPRINGEN' || wert.startsWith('ZUSAMMENFUEHREN:')) {
        neu.set(nr, wert as Entscheid);
      } else {
        neu.delete(nr);
      }
      return neu;
    });
  }

  protected kontaktText(id: string): string {
    return kontaktText(this.kontakte()[id], id);
  }

  protected felderListe(zeile: ImportZeile): { feld: string; wert: string }[] {
    return Object.entries(zeile.felder ?? {})
      .filter(([, wert]) => wert !== '')
      .map(([feld, wert]) => ({ feld: this.feldText(feld), wert }));
  }

  protected uebernehmenFragen(): void {
    this.bestaetigen.set(true);
    afterNextRender(() => this.bestaetigenTitel()?.nativeElement.focus(), { injector: this.injector });
  }

  protected uebernehmen(): void {
    const id = this.lauf()?.id;
    if (!id || this.sendet()) {
      return;
    }
    const dublettenEntscheide = this.art() === 'kontakt' ? entscheideAnfrage(this.entscheide()) : {};
    this.senden(this.importe.apiV1ImporteIdUebernehmenPost(id, { dublettenEntscheide }));
  }

  // Laden und Abfragen

  private senden(anfrage: Observable<ImportLauf>): void {
    this.sendet.set(true);
    this.fehler.set(null);
    anfrage.subscribe({
      next: () => {
        this.sendet.set(false);
        this.bestaetigen.set(false);
        this.filter.set(null);
        this.seite.set(0);
        this.laden(this.id());
        afterNextRender(() => this.statusTitel()?.nativeElement.focus(), { injector: this.injector });
      },
      error: (fehler: unknown) => {
        this.sendet.set(false);
        this.fehler.set(fehlerText(fehler));
        if (fehlercode(fehler) === 'STATUS_KONFLIKT') {
          this.bestaetigen.set(false);
          this.laden(this.id());
        }
      },
    });
  }

  private laden(id: string): void {
    this.anhalten();
    const ergebnis = this.filter() ?? undefined;
    this.anfrage = this.importe.apiV1ImporteIdGet(id, ergebnis, SEITE, this.seite()).subscribe({
      next: (antwort) => {
        this.antwort.set(antwort);
        this.zuordnungUebernehmen(antwort);
        this.planen(id);
      },
      error: (fehler: unknown) => this.fehler.set(fehlerText(fehler)),
    });
  }

  private zuordnungUebernehmen(antwort: ImportLaufMitZeilen): void {
    const lauf = antwort.lauf;
    if (lauf?.status !== 'ZUORDNUNG_NOETIG' || !lauf.id || this.zuordnungFuer === lauf.id) {
      return;
    }
    this.zuordnungFuer = lauf.id;
    const vorschlag = antwort.vorschlagZuordnung;
    const felder: Felder = { ...(vorschlag?.felder ?? {}) };
    this.felder.set(felder);
    this.profilVorschlag.set(vorschlag?.profil ? felder : null);
    this.profilGewaehlt.set(!!vorschlag?.profil);
  }

  private planen(id: string): void {
    if (this.status() === 'VORSCHAU_AUSSTEHEND' || this.uebernahmeLaeuft()) {
      this.abfrage = setTimeout(() => this.laden(id), ABFRAGE_MS);
    }
  }

  private anhalten(): void {
    clearTimeout(this.abfrage);
    this.anfrage?.unsubscribe();
  }
}
