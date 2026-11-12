import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Dokument, DownloadAdresse } from '../gemeinsam/api';
import { datum, zeitpunkt } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DokumentPort, Variante } from './dokument-port';

type Laden = 'laedt' | 'ok' | 'fehlt' | 'fehler' | 'verbindung';
type Vorschau = 'laedt' | 'ok' | 'fehlt' | 'fehler';

// Reserve, damit ein Link nicht genau beim Klick abläuft
const RESERVE_MS = 15_000;

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
 * die beim Öffnen geholt und vom Archiv protokolliert wird. Metadaten bearbeiten (Req 10.1) und Versionen folgen mit
 * Aufgabe 3.5 bzw. dem Backend dazu.
 */
@Component({
  selector: 'app-dokument-ansicht',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink],
  templateUrl: './dokument-ansicht.html',
  styleUrl: './dokument-ansicht.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DokumentAnsicht {
  private readonly port = inject(DokumentPort);
  private readonly bereinigen = inject(DomSanitizer);

  readonly id = input.required<string>();

  protected readonly texte = TEXTE.dokument;
  protected readonly stati = TEXTE.suche.stati;
  protected readonly datum = datum;
  protected readonly zeitpunkt = zeitpunkt;

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

  private readonly arten = signal<Map<string, string>>(new Map());
  protected readonly artName = computed(() => {
    const code = this.dokument()?.art;
    return code ? (this.arten().get(code) ?? code) : TEXTE.suche.ohneArt;
  });
  protected readonly titel = computed(() => this.dokument()?.titel || TEXTE.suche.ohneTitel);
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
      next: (liste) => this.arten.set(new Map(liste.map((a) => [a.code, a.name]))),
      error: () => undefined,
    });
    inject(DestroyRef).onDestroy(() => {
      arten.unsubscribe();
      this.abos.unsubscribe();
    });
    effect(() => {
      const id = this.id();
      untracked(() => this.oeffnen(id));
    });
  }

  private oeffnen(id: string): void {
    this.abos.unsubscribe();
    this.abos = new Subscription();
    this.laden.set('laedt');
    this.dokument.set(null);
    this.pdfa.set(null);
    this.vorschau.set('laedt');
    this.dateiFehler.set(false);
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

  private holenUndOeffnen(variante: Variante): void {
    this.dateiFehler.set(false);
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
