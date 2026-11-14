import { Location } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, map } from 'rxjs';
import { KontaktDetail } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { zeitpunkt } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KontaktBearbeiten } from './kontakt-bearbeiten';
import { KontaktDokumente } from './kontakt-dokumente';
import { KontaktInaktivDialog } from './kontakt-inaktiv-dialog';
import { Zusammengefuehrt, KontaktZusammenfuehrenDialog } from './kontakt-zusammenfuehren-dialog';
import {
  FehlerArt,
  aktionFehlerText,
  fehlerArt,
  kontaktName,
  ladenFehlerText,
} from './kontakt-logik';
import { KontaktePort } from './kontakte-port';

const FELDER = ['strasse', 'plz', 'ort', 'email', 'telefon', 'uid', 'iban', 'kategorie'] as const;

interface Erfolg {
  readonly text: string;
  readonly ziel?: { readonly id: string; readonly name: string };
}

/**
 * Detail eines Kontakts (Req 17.3, 17.4, 17.6): Stammdaten, Kennzeichen und die sichtbaren Dokumente. Ändern, Zusammenführen
 * und Inaktivmarkieren zeigt die Seite nur mit A12 (`inhaber`); das Archiv prüft jede Aktion selbst. Alle Aktionen senden
 * `If-Match` mit der `version` des Details; 412/428 lädt das Detail neu und weist darauf hin.
 */
@Component({
  selector: 'app-kontakt-detail',
  imports: [
    MatButtonModule,
    MatProgressBarModule,
    RouterLink,
    KontaktBearbeiten,
    KontaktDokumente,
    KontaktInaktivDialog,
    KontaktZusammenfuehrenDialog,
  ],
  templateUrl: './kontakt-detail.html',
  styleUrls: ['./kontakte-gemeinsam.scss', './kontakt-detail.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontaktDetailSeite {
  private readonly port = inject(KontaktePort);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly ort = inject(Location);
  private readonly anmeldung = inject(AnmeldungService);

  protected readonly texte = TEXTE.kontakte;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly felder = FELDER;

  protected readonly kontakt = signal<KontaktDetail | null>(null);
  protected readonly laedt = signal(true);
  protected readonly fehler = signal<{ art: FehlerArt; text: string } | null>(null);
  protected readonly hinweis = signal(false);
  protected readonly erfolg = signal<Erfolg | null>(null);
  protected readonly aktionFehler = signal<string | null>(null);
  protected readonly bearbeiten = signal(false);
  protected readonly dialog = signal<'zusammenfuehren' | 'inaktiv' | null>(null);
  protected readonly sendet = signal(false);

  protected readonly id = signal('');
  /** Ändern nur mit A12 (nur `inhaber`); `geschaeftsstelle` sieht Liste und Detail, aber keine Aktionen (Req 17.6). */
  protected readonly darfAendern = computed(() => this.anmeldung.darf('A12'));
  protected readonly aktiv = computed(() => this.kontakt()?.aktiv !== false);
  protected readonly darfWiederAktivieren = computed(() => {
    const k = this.kontakt();
    return !!k && k.aktiv === false && !k.zusammengefuehrtIn;
  });
  protected readonly name = computed(() => kontaktName(this.kontakt() ?? {}));
  protected readonly nichtGefunden = computed(() => this.fehler()?.art === 'nichtGefunden');
  private readonly ueberschrift = viewChild<ElementRef<HTMLElement>>('ueberschrift');
  private laufend?: Subscription;

  constructor() {
    const params = this.route.paramMap.pipe(map((p) => p.get('id') ?? '')).subscribe((id) => {
      this.id.set(id);
      this.kontakt.set(null);
      this.erfolg.set(null);
      this.hinweis.set(false);
      this.bearbeiten.set(false);
      this.dialog.set(null);
      this.laden(true);
    });
    inject(DestroyRef).onDestroy(() => {
      params.unsubscribe();
      this.laufend?.unsubscribe();
    });
  }

  protected laden(mitAnzeige: boolean): void {
    const id = this.id();
    this.laufend?.unsubscribe();
    this.fehler.set(null);
    if (mitAnzeige) {
      this.laedt.set(true);
    }
    this.laufend = this.port.lesen(id).subscribe({
      next: (k) => {
        this.kontakt.set(k);
        this.laedt.set(false);
      },
      error: (f: unknown) => {
        this.laedt.set(false);
        // nie Kontaktdaten zeigen, wenn das Archiv ablehnt (404, 403)
        this.kontakt.set(null);
        this.fehler.set({ art: fehlerArt(f), text: ladenFehlerText(f) });
      },
    });
  }

  /** 412/428: Das Detail wird neu geladen, die Seite weist darauf hin. */
  protected veraltet(): void {
    this.dialog.set(null);
    this.hinweis.set(true);
    this.erfolg.set(null);
    this.laden(false);
  }

  protected bearbeitenOeffnen(): void {
    this.bearbeiten.set(true);
    this.erfolg.set(null);
    this.hinweis.set(false);
  }

  protected gespeichert(): void {
    this.bearbeiten.set(false);
    this.hinweis.set(false);
    this.erfolg.set({ text: this.texte.gespeichert });
    this.laden(false);
    this.fokus();
  }

  protected dialogOeffnen(art: 'zusammenfuehren' | 'inaktiv'): void {
    this.erfolg.set(null);
    this.hinweis.set(false);
    this.aktionFehler.set(null);
    this.dialog.set(art);
  }

  protected dialogSchliessen(): void {
    this.dialog.set(null);
  }

  protected zusammengefuehrt(z: Zusammengefuehrt): void {
    this.dialog.set(null);
    this.erfolg.set({
      text:
        this.texte.zusammengefuehrtMeldung(z.ziel.name) +
        ' ' +
        this.texte.zusammengefuehrtZahlen(z.zahlen.dokumente ?? 0, z.zahlen.verkaufschancen ?? 0),
      ziel: z.ziel,
    });
    this.laden(false);
    this.fokus();
  }

  protected inaktivMarkiert(): void {
    this.dialog.set(null);
    this.erfolg.set({ text: this.texte.inaktivMarkiert });
    this.laden(false);
    this.fokus();
  }

  protected wiederAktivieren(): void {
    const k = this.kontakt();
    if (this.sendet() || !k?.id) {
      return;
    }
    this.sendet.set(true);
    this.aktionFehler.set(null);
    this.erfolg.set(null);
    this.hinweis.set(false);
    this.port.aktivieren(k.id, k.version ?? 0).subscribe({
      next: () => {
        this.sendet.set(false);
        this.erfolg.set({ text: this.texte.wiederAktiviert });
        this.laden(false);
        this.fokus();
      },
      error: (f: unknown) => {
        this.sendet.set(false);
        const art = fehlerArt(f);
        if (art === 'veraltet' || art === 'versionFehlt') {
          this.veraltet();
          return;
        }
        this.aktionFehler.set(aktionFehlerText(f, 'aktiv'));
      },
    });
  }

  protected zurueck(): void {
    if (
      (history.state as { navigationId?: number } | null)?.navigationId &&
      history.state.navigationId > 1
    ) {
      this.ort.back();
    } else {
      void this.router.navigate(['/kontakte']);
    }
  }

  private fokus(): void {
    queueMicrotask(() => this.ueberschrift()?.nativeElement.focus());
  }
}
