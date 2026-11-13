import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { AnmeldungService } from '../../gemeinsam/anmeldung/anmeldung.service';
import { aufnahmeName } from '../../gemeinsam/aufnahme-name';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { Upload } from '../upload';
import { Ecken } from './scanner/ecken';
import { ScannerPort } from './scanner/scanner.service';
import { WarteschlangeService } from './warteschlange/warteschlange.service';
import { Zuschnitt } from './zuschnitt';

type Hinweis = 'laeuft' | 'kantenErkannt' | 'keineKanten' | 'angepasst' | 'ganzesBild' | 'fehler';

interface Aufnahme {
  nr: number;
  /** Zeitpunkt der Aufnahme; der der ersten Seite einer Erfassung bestimmt den Dateinamen (Req 4.9). */
  aufgenommen: Date;
  original: Blob;
  originalUrl: string;
  vorschauUrl?: string;
  arbeitsfassung?: Blob;
  ecken: Ecken | null;
  breite: number;
  hoehe: number;
  hinweis: Hinweis;
}

type Kamera = 'aus' | 'startet' | 'an' | 'nicht-verfuegbar';

const ORIGINAL_QUALITAET = 0.95;

/**
 * Seite «Erfassen» (Req 4.2–4.9): Seiten mit der Rückkamera aufnehmen, zuschneiden (Scanner im Worker), als eine
 * Erfassung in die Warteschlange legen; darunter der Upload von Dateien (Req 3.1). Ohne Kamera bleibt die Dateiwahl
 * des Geräts mit `capture`.
 */
@Component({
  selector: 'app-handy-erfassung',
  imports: [MatButtonModule, Upload, Zuschnitt],
  templateUrl: './handy-erfassung.html',
  styleUrl: './handy-erfassung.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HandyErfassung {
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly warteschlange = inject(WarteschlangeService);
  private readonly scanner = inject(ScannerPort);
  private readonly dokument = inject(DOCUMENT);

  protected readonly texte = TEXTE.handy;
  protected readonly anmeldeTexte = TEXTE.anmeldung;
  protected readonly kamera = signal<Kamera>('aus');
  protected readonly seiten = signal<Aufnahme[]>([]);
  protected readonly bearbeitet = signal<number | null>(null);
  protected readonly meldung = signal<string | null>(null);
  /** Die Erfassung wird gespeichert: Aufnehmen, Dateiwahl, Zuschnitt, Verwerfen und Abschliessen sind gesperrt. */
  protected readonly speichert = signal(false);
  private readonly fertig = computed(() => {
    const seiten = this.seiten();
    return seiten.length > 0 && seiten.every((s) => !!s.arbeitsfassung && s.hinweis !== 'laeuft');
  });
  /** «Abschliessen» ist bedienbar, sobald keine Seite mehr läuft; Seiten mit Fehler erklärt dann die Meldung. */
  protected readonly abschliessbar = computed(
    () => !this.speichert() && this.seiten().length > 0 && this.seiten().every((s) => s.hinweis !== 'laeuft'),
  );
  protected readonly inBearbeitung = computed(() => this.seiten().find((s) => s.nr === this.bearbeitet()) ?? null);
  protected readonly name = (iso: string) => aufnahmeName(new Date(iso));

  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');
  private strom: MediaStream | null = null;
  private naechsteNr = 0;
  private speichern: Promise<boolean> | null = null;
  private readonly laufend = new Set<Promise<void>>();

  constructor() {
    // Req 4.2: «Erfassen» öffnet auf dem Handy direkt die Rückkamera; am Computer erst auf Wunsch
    afterNextRender(() => {
      // Den Scanner laden, solange eine Verbindung besteht; offline kommt er aus dem Service Worker
      this.scanner.vorbereiten();
      if (this.dokument.defaultView?.matchMedia?.('(pointer: coarse)').matches) {
        void this.kameraStarten();
      }
    });
    inject(DestroyRef).onDestroy(() => {
      this.kameraStoppen();
      for (const seite of this.seiten()) {
        this.freigeben(seite);
      }
    });
  }

  async kameraStarten(): Promise<void> {
    const medien = this.dokument.defaultView?.navigator.mediaDevices;
    if (!medien?.getUserMedia) {
      this.kamera.set('nicht-verfuegbar');
      return;
    }
    this.kamera.set('startet');
    try {
      this.strom = await medien.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 3840 }, height: { ideal: 2160 } },
      });
      const video = this.video()!.nativeElement;
      video.srcObject = this.strom;
      await video.play();
      this.kamera.set('an');
    } catch {
      this.kameraStoppen();
      this.kamera.set('nicht-verfuegbar');
    }
  }

  /** Nimmt das volle Kamerabild als Original (Rohaufnahme, Req 4.7) und startet den Zuschnitt. */
  protected async aufnehmen(): Promise<void> {
    const video = this.video()!.nativeElement;
    const leinwand = this.dokument.createElement('canvas');
    leinwand.width = video.videoWidth;
    leinwand.height = video.videoHeight;
    leinwand.getContext('2d')!.drawImage(video, 0, 0);
    const original = await new Promise<Blob | null>((fertig) => leinwand.toBlob(fertig, 'image/jpeg', ORIGINAL_QUALITAET));
    if (original) {
      this.seiteHinzufuegen(original);
    }
  }

  protected dateienGewaehlt(ereignis: Event): void {
    const eingabe = ereignis.target as HTMLInputElement;
    for (const datei of Array.from(eingabe.files ?? [])) {
      this.seiteHinzufuegen(datei);
    }
    eingabe.value = '';
  }

  protected verwerfen(nr: number): void {
    const seite = this.seiten().find((s) => s.nr === nr);
    if (seite) {
      this.freigeben(seite);
    }
    this.seiten.update((seiten) => seiten.filter((s) => s.nr !== nr));
    if (this.bearbeitet() === nr) {
      this.bearbeitet.set(null);
    }
  }

  protected zuschnittUebernehmen(ecken: Ecken | null): void {
    const nr = this.bearbeitet();
    this.bearbeitet.set(null);
    if (nr !== null) {
      this.aufbereiten(nr, ecken);
    }
  }

  /**
   * Legt die Seiten als eine Erfassung in die Warteschlange (Req 4.4); hochgeladen wird sofort oder später. Ein zweiter
   * Aufruf während des Speicherns legt keinen zweiten Eintrag an, sondern wartet auf den ersten.
   */
  protected abschliessen(): Promise<boolean> {
    if (this.speichern) {
      return this.speichern;
    }
    if (!this.fertig()) {
      const fehler = this.seiten().flatMap((s, i) => (s.hinweis === 'fehler' ? [i + 1] : []));
      if (fehler.length > 0) {
        this.meldung.set(this.texte.seitenMitFehler(fehler));
      }
      return Promise.resolve(false);
    }
    this.speichert.set(true);
    this.speichern = this.speichernLauf().finally(() => {
      this.speichern = null;
      this.speichert.set(false);
    });
    return this.speichern;
  }

  /**
   * Vor der Weiterleitung zu Keycloak die aufgenommenen Seiten sichern; der Arbeitsspeicher geht dabei verloren. Seiten,
   * die nicht zugeschnitten werden konnten, halten die Anmeldung mit einer Meldung auf, statt verloren zu gehen.
   */
  protected async anmelden(): Promise<void> {
    await Promise.all(this.laufend);
    if (this.speichern && !(await this.speichern)) {
      return;
    }
    if (this.seiten().length > 0 && !(await this.abschliessen())) {
      return;
    }
    await this.anmeldung.anmelden('/erfassen');
  }

  protected hinweisText(hinweis: Hinweis): string {
    return hinweis === 'laeuft' ? this.texte.wirdAufbereitet : hinweis === 'fehler' ? this.texte.aufbereitenFehler : this.texte[hinweis];
  }

  /** Speichert die Seiten des Moments; danach entfernt es nur diese, später aufgenommene bleiben stehen. */
  private async speichernLauf(): Promise<boolean> {
    this.meldung.set(null);
    const seiten = this.seiten();
    try {
      await this.warteschlange.hinzufuegen(
        seiten.map((s) => ({ original: s.original, arbeitsfassung: s.arbeitsfassung! })),
        seiten[0].aufgenommen,
      );
    } catch {
      this.meldung.set(this.texte.speichernFehler);
      return false;
    }
    const gespeichert = new Set(seiten.map((s) => s.nr));
    this.seiten().filter((s) => gespeichert.has(s.nr)).forEach((s) => this.freigeben(s));
    this.seiten.update((alle) => alle.filter((s) => !gespeichert.has(s.nr)));
    this.meldung.set(this.texte.gespeichert);
    return true;
  }

  private seiteHinzufuegen(original: Blob): void {
    this.meldung.set(null);
    const nr = ++this.naechsteNr;
    this.seiten.update((seiten) => [
      ...seiten,
      {
        nr,
        aufgenommen: new Date(),
        original,
        originalUrl: URL.createObjectURL(original),
        ecken: null,
        breite: 0,
        hoehe: 0,
        hinweis: 'laeuft',
      },
    ]);
    this.aufbereiten(nr);
  }

  /** `ecken` fehlt: Kanten erkennen; `null`: ganzes Bild; sonst die angepassten Ecken (Req 4.3). */
  private aufbereiten(nr: number, ecken?: Ecken | null): void {
    const seite = this.seiten().find((s) => s.nr === nr);
    if (!seite) {
      return;
    }
    this.aendern(nr, { hinweis: 'laeuft' });
    const lauf = this.scanner
      .aufbereiten(seite.original, ecken)
      .then((ergebnis) => {
        const aktuell = this.seiten().find((s) => s.nr === nr);
        if (!aktuell) {
          return;
        }
        if (aktuell.vorschauUrl) {
          URL.revokeObjectURL(aktuell.vorschauUrl);
        }
        const hinweis: Hinweis =
          ecken === undefined ? (ergebnis.ecken ? 'kantenErkannt' : 'keineKanten') : ecken ? 'angepasst' : 'ganzesBild';
        this.aendern(nr, {
          ecken: ergebnis.ecken,
          breite: ergebnis.breite,
          hoehe: ergebnis.hoehe,
          arbeitsfassung: ergebnis.arbeitsfassung,
          vorschauUrl: URL.createObjectURL(ergebnis.arbeitsfassung),
          hinweis,
        });
      })
      .catch(() => this.aendern(nr, { hinweis: 'fehler', arbeitsfassung: undefined }))
      .finally(() => this.laufend.delete(lauf));
    this.laufend.add(lauf);
  }

  private aendern(nr: number, aenderung: Partial<Aufnahme>): void {
    this.seiten.update((seiten) => seiten.map((s) => (s.nr === nr ? { ...s, ...aenderung } : s)));
  }

  private freigeben(seite: Aufnahme): void {
    URL.revokeObjectURL(seite.originalUrl);
    if (seite.vorschauUrl) {
      URL.revokeObjectURL(seite.vorschauUrl);
    }
  }

  private kameraStoppen(): void {
    this.strom?.getTracks().forEach((spur) => spur.stop());
    this.strom = null;
  }
}
