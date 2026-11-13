import { HttpErrorResponse } from '@angular/common/http';
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
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Dokument, Dokumentart, DownloadAdresse } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TitelAnzeige, anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { betrag, datum, zeitpunkt } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DokumentPort, Variante } from './dokument-port';
import { MetadatenFormular } from './metadaten-formular';

type Laden = 'laedt' | 'ok' | 'fehlt' | 'fehler' | 'verbindung';
type Vorschau = 'laedt' | 'ok' | 'fehlt' | 'fehler';

// Reserve, damit ein Link nicht genau beim Klick abläuft
const RESERVE_MS = 15_000;

/** Status, in denen `PATCH /dokumente/{id}` Änderungen annimmt (design.md «Umsetzung 3.5»). */
const AENDERBAR = new Set(['EINGANGSKORB', 'FEHLER', 'ARCHIVIERT']);
const BREIT = '(min-width: 960px)';

/** Nur http(s) darf in object/Link; die Adresse kommt vom Archiv, die Prüfung schützt trotzdem vor javascript: o. Ä. */
export function sichereAdresse(url: string | null | undefined): string | null {
  try {
    const adresse = new URL(url ?? '');
    return adresse.protocol === 'https:' || adresse.protocol === 'http:' ? adresse.href : null;
  } catch {
    return null;
  }
}

interface Zusatzfeld {
  name: string;
  wert: string;
}

/**
 * Dokumentansicht (Req 14.6): Metadaten, PDF-Vorschau der PDF/A-Fassung und Download über eine vorsignierte Adresse,
 * die beim Öffnen geholt und vom Archiv protokolliert wird; Metadaten bearbeiten mit Grund (A6, Req 10.1) und die
 * gesicherten Fassungen. Das Vorschaubild der ersten Seite ersetzt die PDF-Vorschau auf schmalen Bildschirmen und
 * ohne PDF/A-Fassung.
 */
@Component({
  selector: 'app-dokument-ansicht',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink, MetadatenFormular, TitelAnzeige],
  templateUrl: './dokument-ansicht.html',
  styleUrl: './dokument-ansicht.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DokumentAnsicht {
  private readonly port = inject(DokumentPort);
  private readonly bereinigen = inject(DomSanitizer);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly id = input.required<string>();

  protected readonly texte = TEXTE.dokument;
  protected readonly stati = TEXTE.suche.stati;
  protected readonly datum = datum;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly betrag = betrag;
  protected readonly richtungen = TEXTE.eingangskorb.richtung;

  protected readonly laden = signal<Laden>('laedt');
  protected readonly dokument = signal<Dokument | null>(null);
  protected readonly vorschau = signal<Vorschau>('laedt');
  private readonly pdfa = signal<DownloadAdresse | null>(null);
  protected readonly pdfaUrl = computed(() => sichereAdresse(this.pdfa()?.url));
  protected readonly pdfaRessource = computed<SafeResourceUrl | null>(() => {
    const url = this.pdfaUrl();
    return url ? this.bereinigen.bypassSecurityTrustResourceUrl(url) : null;
  });
  protected readonly dateiFehler = signal(false);
  /**
   * Zuletzt per Klick geholte Adresse: `window.open` läuft erst nach der Antwort und damit ausserhalb der Klick-Geste;
   * blockiert der Browser das Fenster, bleibt dieser Link als zweite, eigene Geste.
   */
  private readonly geholt = signal<{ adresse: DownloadAdresse; variante: Variante } | null>(null);
  protected readonly geholtUrl = computed(() => sichereAdresse(this.geholt()?.adresse.url));

  protected readonly arten = signal<readonly Dokumentart[]>([]);
  protected readonly artName = computed(() => {
    const code = this.dokument()?.art;
    return code ? (this.arten().find((a) => a.code === code)?.name ?? code) : TEXTE.suche.ohneArt;
  });
  /** Knopf nur mit A6 und in einem änderbaren Status; das Archiv prüft trotzdem selbst (Req 2.6). */
  protected readonly darfBearbeiten = computed(() => {
    const d = this.dokument();
    return !!d && AENDERBAR.has(d.status) && this.anmeldung.darf('A6');
  });
  protected readonly bearbeiten = signal(false);
  protected readonly meldung = signal('');
  protected readonly ich = computed(() => this.anmeldung.benutzer()?.id ?? '');

  /** Breiter Bildschirm: PDF im `<object>`; sonst das Vorschaubild, weil Mobilbrowser PDFs oft nicht einbetten. */
  protected readonly breit = signal(typeof window.matchMedia === 'function' && window.matchMedia(BREIT).matches);
  private readonly bild = signal<{ id: string; url: string } | null>(null);
  protected readonly bildUrl = computed(() => {
    const bild = this.bild();
    return bild && bild.id === this.dokument()?.id ? bild.url : null;
  });
  private readonly bildNoetig = computed(() => {
    const d = this.dokument();
    const v = this.vorschau();
    return !!d && (d.seiten ?? 0) >= 1 && (!this.breit() || v === 'fehlt' || v === 'fehler');
  });
  private bildAbo = new Subscription();
  /** Ohne Titel der Dateiname als Platzhalter, in jedem Status (Req 14.10). */
  protected readonly anzeige = computed(() =>
    anzeigeTitel(this.dokument()?.titel, null, this.dokument()?.dateiname),
  );
  protected readonly titel = computed(() => this.anzeige().text);
  protected readonly zusatz = computed<Zusatzfeld[]>(() => {
    const z = this.dokument()?.zusatz as Record<string, unknown> | null | undefined;
    if (!z || typeof z !== 'object') {
      return [];
    }
    return Object.entries(z)
      .filter(([, wert]) => wert !== null && wert !== undefined && wert !== '')
      .map(([name, wert]) => ({ name, wert: typeof wert === 'object' ? JSON.stringify(wert) : String(wert) }));
  });

  private abos = new Subscription();

  constructor() {
    const arten = this.port.dokumentarten().subscribe({
      next: (liste) => this.arten.set(liste),
      error: () => undefined,
    });
    const medien = typeof window.matchMedia === 'function' ? window.matchMedia(BREIT) : null;
    const wechsel = (e: MediaQueryListEvent) => this.breit.set(e.matches);
    medien?.addEventListener?.('change', wechsel);
    inject(DestroyRef).onDestroy(() => {
      arten.unsubscribe();
      this.abos.unsubscribe();
      medien?.removeEventListener?.('change', wechsel);
      this.bildFreigeben();
    });
    effect(() => {
      const id = this.id();
      untracked(() => this.oeffnen(id));
    });
    effect(() => {
      if (this.bildNoetig()) {
        untracked(() => this.bildLaden());
      }
    });
  }

  protected bearbeitenOeffnen(): void {
    this.meldung.set('');
    this.bearbeiten.set(true);
  }

  protected bearbeitenSchliessen(): void {
    this.bearbeiten.set(false);
    this.fokus('[data-test="bearbeiten"]');
  }

  protected gespeichert(dokument: Dokument): void {
    this.dokument.set(dokument);
    this.bearbeiten.set(false);
    this.melden(this.texte.gespeichert);
  }

  /** 409/412/404 beim Speichern: Ansicht neu laden, damit die Änderung auf dem aktuellen Stand neu erfasst wird. */
  protected veraltet(meldung: string): void {
    this.oeffnen(this.id());
    this.melden(meldung);
  }

  protected wer(sub: string): string {
    return sub && sub === this.ich() ? this.texte.sie : sub;
  }

  private melden(meldung: string): void {
    this.meldung.set(meldung);
    this.fokus('[data-test="meldung"]');
  }

  private fokus(selektor: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selektor)?.focus(), {
      injector: this.injector,
    });
  }

  private bildLaden(): void {
    const id = this.dokument()?.id;
    if (!id || this.bild()?.id === id) {
      return;
    }
    this.bildAbo.unsubscribe();
    this.bildAbo = this.port.vorschau(id, 1).subscribe({
      next: (blob) => {
        this.bildFreigeben();
        this.bild.set({ id, url: URL.createObjectURL(blob) });
      },
      error: () => undefined,
    });
  }

  private bildFreigeben(): void {
    const bild = this.bild();
    if (bild) {
      URL.revokeObjectURL(bild.url);
      this.bild.set(null);
    }
  }

  private oeffnen(id: string): void {
    this.abos.unsubscribe();
    this.abos = new Subscription();
    this.bildAbo.unsubscribe();
    if (this.bild()?.id !== id) {
      this.bildFreigeben();
    }
    this.bearbeiten.set(false);
    this.meldung.set('');
    this.laden.set('laedt');
    this.dokument.set(null);
    this.pdfa.set(null);
    this.vorschau.set('laedt');
    this.dateiFehler.set(false);
    this.geholt.set(null);
    this.abos.add(
      this.port.lesen(id).subscribe({
        next: (dokument) => {
          this.dokument.set(dokument);
          this.laden.set('ok');
          this.vorschauLaden(id);
        },
        error: (fehler: unknown) => this.laden.set(ladeFehler(fehler)),
      }),
    );
  }

  private vorschauLaden(id: string): void {
    this.abos.add(
      this.port.adresse(id, 'pdfa').subscribe({
        next: (adresse) => {
          this.pdfa.set(adresse);
          this.vorschau.set(sichereAdresse(adresse.url) ? 'ok' : 'fehler');
        },
        error: (fehler: unknown) => this.vorschau.set(status(fehler) === 404 ? 'fehlt' : 'fehler'),
      }),
    );
  }

  /** Abgelaufene Adressen holt der Klick neu (jeder Abruf wird protokolliert); sonst öffnet der Link direkt. */
  protected pdfaKlick(ereignis: MouseEvent): void {
    const adresse = this.pdfa();
    if (adresse && gueltig(adresse)) {
      return;
    }
    ereignis.preventDefault();
    this.holenUndOeffnen('pdfa');
  }

  protected originalHerunterladen(): void {
    this.holenUndOeffnen('original');
  }

  protected geholtKlick(ereignis: MouseEvent): void {
    const geholt = this.geholt();
    if (geholt && gueltig(geholt.adresse)) {
      return;
    }
    ereignis.preventDefault();
    if (geholt) {
      this.holenUndOeffnen(geholt.variante);
    }
  }

  private holenUndOeffnen(variante: Variante): void {
    this.dateiFehler.set(false);
    this.geholt.set(null);
    this.abos.add(
      this.port.adresse(this.id(), variante).subscribe({
        next: (adresse) => {
          const url = sichereAdresse(adresse.url);
          if (!url) {
            this.dateiFehler.set(true);
            return;
          }
          if (variante === 'pdfa') {
            this.pdfa.set(adresse);
          }
          this.geholt.set({ adresse, variante });
          window.open(url, '_blank', 'noopener,noreferrer');
        },
        error: () => this.dateiFehler.set(true),
      }),
    );
  }
}

function gueltig(adresse: DownloadAdresse): boolean {
  const bis = Date.parse(adresse.gueltigBis);
  return Number.isFinite(bis) && bis - Date.now() > RESERVE_MS;
}

function status(fehler: unknown): number {
  return fehler instanceof HttpErrorResponse ? fehler.status : -1;
}

function ladeFehler(fehler: unknown): Laden {
  const s = status(fehler);
  if (s === 404 || s === 400) {
    return 'fehlt';
  }
  return s === 0 ? 'verbindung' : 'fehler';
}
