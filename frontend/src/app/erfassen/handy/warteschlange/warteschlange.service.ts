import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { AnmeldungService } from '../../../gemeinsam/anmeldung/anmeldung.service';
import { ONLINE } from '../../../gemeinsam/online';
import { TEXTE } from '../../../gemeinsam/texte.de-CH';
import { Eintrag, Meldungen, Seite, bewerten } from './eintrag';
import { WarteschlangeSpeicher } from './speicher';
import { WarteschlangeUpload } from './warteschlange-upload';

/** Nach einem Fehlversuch im Online-Zustand: neuer Versuch nach Fehlversuche × diese Zeit. */
export const WIEDERHOLEN_NACH_MS = 30_000;

const MELDUNGEN: Meldungen = {
  unbekannt: TEXTE.handy.fehlerUnbekannt,
  wiederholtFehlgeschlagen: TEXTE.handy.fehlerWiederholt,
  fehlercode: TEXTE.upload.fehlercode,
};

/**
 * Warteschlange der Handy-Erfassung (Req 4.4–4.8): hält Erfassungen in IndexedDB und lädt sie hoch, sobald das Gerät
 * online und der Benutzer mit Recht A1 angemeldet ist – beim Start der App, beim Wechsel auf online, nach der Anmeldung
 * und nach dem Erfassen. Die Tokens liegen nur im Arbeitsspeicher (AnmeldungService), darum kein Background Sync.
 */
@Injectable({ providedIn: 'root' })
export class WarteschlangeService {
  private readonly speicher = inject(WarteschlangeSpeicher);
  private readonly upload = inject(WarteschlangeUpload);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly online = inject(ONLINE);

  private readonly alleEintraege = signal<Eintrag[]>([]);
  private laeuft: Promise<void> | null = null;
  private nochmal = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  /** Frühester nächster Versuch je Eintrag nach einem Fehlversuch (nur im Arbeitsspeicher). */
  private readonly nichtVor = new Map<string, number>();

  readonly eintraege = this.alleEintraege.asReadonly();
  /** Zähler «wartet auf Upload» (Req 4.4). */
  readonly wartend = computed(() => this.alleEintraege().filter((e) => e.zustand === 'wartet').length);
  readonly fehlerhaft = computed(() => this.alleEintraege().filter((e) => e.zustand === 'fehlerhaft'));
  readonly geladen: Promise<void>;

  constructor() {
    // Ohne IndexedDB (z. B. privater Modus) bleibt die Schlange leer; das Erfassen meldet den Fehler
    this.geladen = this.neuLaden().catch(() => undefined);
    // Wieder online: Die Fehlversuche lagen womöglich an der Verbindung, darum ohne Wartezeit erneut
    let warOnline = true;
    effect(() => {
      const online = this.online();
      if (online && !warOnline) {
        untracked(() => this.nichtVor.clear());
      }
      warOnline = online;
    });
    effect(() => {
      if (this.anmeldung.angemeldet() && this.online()) {
        untracked(() => void this.verarbeiten());
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  /** Legt eine Erfassung in die Warteschlange und versucht sofort den Upload. */
  async hinzufuegen(seiten: Seite[], aufgenommen: Date): Promise<void> {
    const eintrag: Eintrag = {
      id: crypto.randomUUID(),
      aufgenommen: aufgenommen.toISOString(),
      seiten,
      fehlversuche: 0,
      zustand: 'wartet',
      benutzer: this.anmeldung.benutzer()?.id || undefined,
    };
    await this.speicher.speichern(eintrag);
    // Bittet den Browser, die Warteschlange nicht bei Platzmangel zu räumen (vor allem Safari)
    void navigator.storage?.persist?.().catch(() => false);
    await this.neuLaden();
    void this.verarbeiten();
  }

  /** Manuelle Wiederholung eines fehlerhaften Eintrags (Req 4.6, 4.8). */
  async wiederholen(id: string): Promise<void> {
    const eintrag = this.alleEintraege().find((e) => e.id === id);
    if (eintrag) {
      this.nichtVor.delete(id);
      await this.speicher.speichern({ ...eintrag, zustand: 'wartet', fehlversuche: 0, meldung: undefined });
      await this.neuLaden();
      void this.verarbeiten();
    }
  }

  /**
   * Lädt alle wartenden Einträge des angemeldeten Benutzers nacheinander hoch; nie zwei Läufe gleichzeitig. Ein Aufruf
   * während eines Laufs hängt einen weiteren an; das Versprechen erfüllt sich erst nach allen.
   */
  verarbeiten(): Promise<void> {
    if (this.laeuft) {
      this.nochmal = true;
      return this.laeuft;
    }
    this.laeuft = (async () => {
      do {
        this.nochmal = false;
        await this.lauf();
      } while (this.nochmal);
    })().finally(() => (this.laeuft = null));
    return this.laeuft;
  }

  private async lauf(): Promise<void> {
    await this.geladen;
    for (const eintrag of this.alleEintraege()) {
      if (!this.darfHochladen(eintrag)) {
        continue;
      }
      if (!this.online()) {
        return;
      }
      // Nach einem Fehlversuch wartet die Schlange, auch wenn sie inzwischen anders angestossen wird (Req 4.6)
      const warten = (this.nichtVor.get(eintrag.id) ?? 0) - Date.now();
      if (warten > 0) {
        this.wecken(warten);
        return;
      }
      const ausgang = await this.upload.senden(eintrag);
      const bewertung = bewerten(eintrag, ausgang, this.online(), MELDUNGEN);
      if (bewertung.art === 'anmelden') {
        this.anmeldung.sitzungAbgelaufen();
        return;
      }
      this.nichtVor.delete(eintrag.id);
      if (bewertung.art === 'loeschen') {
        await this.speicher.loeschen(eintrag.id);
      } else {
        await this.speicher.speichern(bewertung.eintrag);
      }
      await this.neuLaden();
      const neu = bewertung.art === 'behalten' ? bewertung.eintrag : null;
      if (neu?.zustand === 'wartet') {
        // Netzwerkfehler oder 5xx: später erneut; offline wartet die Schlange auf das Ereignis «online»
        if (neu.fehlversuche > eintrag.fehlversuche) {
          this.nichtVor.set(eintrag.id, Date.now() + WIEDERHOLEN_NACH_MS * neu.fehlversuche);
          this.wecken(WIEDERHOLEN_NACH_MS * neu.fehlversuche);
        }
        return;
      }
    }
  }

  private wecken(nachMs: number): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.verarbeiten(), nachMs);
  }

  /** Nur mit Recht A1 und nur Einträge, die dieser Benutzer erfasst hat (ohne Benutzer: jeder mit A1). */
  private darfHochladen(eintrag: Eintrag): boolean {
    const benutzer = this.anmeldung.benutzer()?.id;
    return (
      eintrag.zustand === 'wartet' &&
      this.anmeldung.darf('A1') &&
      (!eintrag.benutzer || eintrag.benutzer === benutzer)
    );
  }

  private async neuLaden(): Promise<void> {
    this.alleEintraege.set(await this.speicher.alle());
  }
}
