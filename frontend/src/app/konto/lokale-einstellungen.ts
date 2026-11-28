import { Injectable, inject } from '@angular/core';
import { EingangskorbEntwurfSpeicher } from '../eingangskorb/entwurf-speicher';
import { BrandingService } from '../gemeinsam/branding.service';
import { FarbschemaService } from '../gemeinsam/farbschema';
import { EinfuehrungVermerk } from '../hilfe/einfuehrung-vermerk';

/**
 * «Lokale Einstellungen entfernen» (Spec 06, Req 11.9): löscht genau die Farbschema-Wahl, den Einführungsvermerk und die
 * Entwürfe des Eingangskorbs dieser Person sowie das zwischengespeicherte Erscheinungsbild dieser Domain. Nie den ganzen
 * Speicher des Browsers, nie die Warteschlange (IndexedDB, Spec 01, Req 4.11), nie Vermerke oder Entwürfe anderer.
 * Die Anmeldung bleibt bestehen (die Tokens liegen nur im Arbeitsspeicher). Liefert `false`, wenn ein Teil scheitert.
 */
@Injectable({ providedIn: 'root' })
export class LokaleEinstellungen {
  private readonly farbschema = inject(FarbschemaService);
  private readonly vermerk = inject(EinfuehrungVermerk);
  private readonly entwurfSpeicher = inject(EingangskorbEntwurfSpeicher);
  private readonly branding = inject(BrandingService);

  async entfernen(wer: { id: string; mandant: string | null } | null): Promise<boolean> {
    // Alle Teile versuchen, auch wenn einer scheitert; Erfolg nur, wenn alle gelingen
    const ergebnisse = [
      this.farbschema.waehlen('system'),
      wer?.id ? this.entwurfSpeicher.benutzerLoeschen(wer.id) : true,
      this.branding.zwischenspeicherEntfernen(),
      await this.vermerk.entfernen(wer),
    ];
    return ergebnisse.every(Boolean);
  }
}
