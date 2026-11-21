import { HttpClient } from '@angular/common/http';
import { Injectable, Signal, WritableSignal, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Anleitung, anleitungMarkdown } from './anleitung-markdown';
import { istAppPfad } from './app-pfade';
import { HilfeEintrag } from './hilfe-katalog';

export type AnleitungZustand =
  | { art: 'laedt' }
  | { art: 'fertig'; anleitung: Anleitung }
  /** Weder Server noch Service Worker lieferten die Datei, oder sie ist grösser als 32 KiB (Req 7.6). */
  | { art: 'fehler' };

/**
 * Lädt die Dateien der Anleitung (`/anleitung/<anker>.md`, Req 7.1) einmal je Sitzung; offline liefert sie der Service
 * Worker. Ein Fehler betrifft nur seinen Abschnitt und bleibt, bis «Erneut versuchen» ({@link erneut}) gewählt wird.
 */
@Injectable({ providedIn: 'root' })
export class AnleitungLader {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly zustaende = new Map<string, WritableSignal<AnleitungZustand>>();

  zustand(eintrag: HilfeEintrag): Signal<AnleitungZustand> {
    let zustand = this.zustaende.get(eintrag.anker);
    if (!zustand) {
      zustand = signal<AnleitungZustand>({ art: 'laedt' });
      this.zustaende.set(eintrag.anker, zustand);
      this.laden(eintrag, zustand);
    }
    return zustand.asReadonly();
  }

  erneut(eintrag: HilfeEintrag): void {
    const zustand = this.zustaende.get(eintrag.anker);
    if (!zustand) {
      this.zustand(eintrag);
    } else if (zustand().art === 'fehler') {
      zustand.set({ art: 'laedt' });
      this.laden(eintrag, zustand);
    }
  }

  private laden(eintrag: HilfeEintrag, zustand: WritableSignal<AnleitungZustand>): void {
    this.http.get(eintrag.datei, { responseType: 'text', observe: 'response' }).subscribe({
      next: (antwort) => {
        // Eine HTML-Antwort ist die Rückfallseite der SPA (Datei fehlt auf dem Server), keine Anleitung
        if (antwort.headers.get('Content-Type')?.toLowerCase().startsWith('text/html')) {
          zustand.set({ art: 'fehler' });
          return;
        }
        try {
          const routes = this.router.config;
          const anleitung = anleitungMarkdown(antwort.body ?? '', {
            istAppPfad: (pfad) => istAppPfad(pfad, routes),
            praefix: eintrag.anker,
          });
          zustand.set({ art: 'fertig', anleitung });
        } catch {
          zustand.set({ art: 'fehler' });
        }
      },
      error: () => zustand.set({ art: 'fehler' }),
    });
  }
}
