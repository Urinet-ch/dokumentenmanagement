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
        case 'BESTAETIGUNG_NOETIG': return bestaetigungsmeldung(antwort);
      }
    }
    if (fehler.status === 503 && antwort.fehlercode === 'IDENTITAETSDIENST_NICHT_ERREICHBAR') {
      return TEXTE.benutzerAktionsfehler.identitaetsdienst;
    }
  }
  return httpFehlerMeldung(fehler);
}

/** 409 BESTAETIGUNG_NOETIG: Die Benutzer-API kennt keine Bestätigung; die Auswirkung wird nur gezeigt, nichts gesendet. */
function bestaetigungsmeldung(antwort: object): string {
  const texte = 'auswirkung' in antwort && Array.isArray(antwort.auswirkung)
    ? antwort.auswirkung.flatMap((eintrag: unknown) =>
      eintrag && typeof eintrag === 'object' && 'text' in eintrag && typeof eintrag.text === 'string'
        && eintrag.text.length > 0 && eintrag.text.length <= 300 ? [eintrag.text] : []).slice(0, 5)
    : [];
  const kopf = TEXTE.benutzerAktionsfehler.bestaetigungNoetig;
  return texte.length ? `${kopf} ${TEXTE.benutzerAktionsfehler.bestaetigungAuswirkung} ${texte.join(' ')}` : kopf;
}
