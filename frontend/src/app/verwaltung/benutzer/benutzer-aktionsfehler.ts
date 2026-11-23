import { HttpErrorResponse } from '@angular/common/http';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';

export function benutzerAktionsfehler(fehler: unknown): string {
  if (!(fehler instanceof HttpErrorResponse)) return httpFehlerMeldung(fehler);
  if (fehler.status === 412) return TEXTE.benutzerAktionsfehler.versionKonflikt;
  const antwort: unknown = fehler.error;
  if (antwort && typeof antwort === 'object' && 'fehlercode' in antwort) {
    if (fehler.status === 409) {
      switch (antwort.fehlercode) {
        case 'LETZTER_INHABER': return TEXTE.benutzerAktionsfehler.letzterInhaber;
        case 'BENUTZER_EXISTIERT': return TEXTE.benutzerAktionsfehler.benutzerExistiert;
        case 'STATUS_KONFLIKT': return TEXTE.benutzerAktionsfehler.statusKonflikt;
      }
    }
    if (fehler.status === 503 && antwort.fehlercode === 'IDENTITAETSDIENST_NICHT_ERREICHBAR') {
      return TEXTE.benutzerAktionsfehler.identitaetsdienst;
    }
  }
  return httpFehlerMeldung(fehler);
}
