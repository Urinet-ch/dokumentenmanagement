import { Injectable, inject } from '@angular/core';
import { EingangskorbEntwurfSpeicher } from '../eingangskorb/entwurf-speicher';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from '../gemeinsam/branding.service';
import { FarbschemaService } from '../gemeinsam/farbschema';
import { EinfuehrungVermerk } from '../hilfe/einfuehrung-vermerk';

/**
 * «Lokale Einstellungen entfernen» (Spec 06, Req 11.9): löscht genau die Farbschema-Wahl, den eigenen Einführungsvermerk,
 * die eigenen Entwürfe des Eingangskorbs und das zwischengespeicherte Erscheinungsbild dieser Domain. Nie den ganzen
 * Speicher des Browsers, nie die Warteschlange (IndexedDB, Spec 01, Req 4.11), nie Vermerke oder Entwürfe anderer.
 * Die Anmeldung bleibt bestehen (die Tokens liegen nur im Arbeitsspeicher).
 */
@Injectable({ providedIn: 'root' })
export class LokaleEinstellungen {
  private readonly anmeldung = inject(AnmeldungService);
  private readonly farbschema = inject(FarbschemaService);
  private readonly vermerk = inject(EinfuehrungVermerk);
  private readonly entwurfSpeicher = inject(EingangskorbEntwurfSpeicher);
  private readonly branding = inject(BrandingService);

  async entfernen(): Promise<void> {
    this.farbschema.waehlen('system');
    const benutzerId = this.anmeldung.benutzer()?.id;
    if (benutzerId) {
      this.entwurfSpeicher.benutzerLoeschen(benutzerId);
    }
    this.branding.zwischenspeicherEntfernen();
    await this.vermerk.entfernen();
  }
}
