import { Injectable, inject } from '@angular/core';
import { EingangskorbEntwurfSpeicher } from '../eingangskorb/entwurf-speicher';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';

/**
 * Abmelden wie bisher in der Kopfzeile (Spec 06, Req 11.10): Entwürfe des Eingangskorbs verwerfen, dann bei Keycloak
 * abmelden. Eine Quelle für die Seite «Mein Konto» und die Kopfzeile bei abgelaufener Sitzung (Req 11.2).
 */
@Injectable({ providedIn: 'root' })
export class Abmeldung {
  private readonly anmeldung = inject(AnmeldungService);
  private readonly entwurfSpeicher = inject(EingangskorbEntwurfSpeicher);

  abmelden(): void {
    this.entwurfSpeicher.alleLoeschen();
    this.anmeldung.abmelden();
  }
}
