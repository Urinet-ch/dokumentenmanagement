import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';

describe('Fehler bei Benutzeraktionen', () => {
  it('unterscheidet fachliche Konflikte ohne fremde Servertexte anzuzeigen', () => {
    const problem = (code: string) => new HttpErrorResponse({
      status: 409, error: { fehlercode: code, detail: 'unbekannter technischer Text' },
    });
    expect(benutzerAktionsfehler(problem('LETZTER_INHABER'))).toBe(TEXTE.benutzerAktionsfehler.letzterInhaber);
    expect(benutzerAktionsfehler(problem('BENUTZER_EXISTIERT'))).toBe(TEXTE.benutzerAktionsfehler.benutzerExistiert);
    expect(benutzerAktionsfehler(problem('STATUS_KONFLIKT'))).toBe(TEXTE.benutzerAktionsfehler.statusKonflikt);
    expect(benutzerAktionsfehler(problem('UNBEKANNT'))).toBe(TEXTE.httpFehler.konflikt);
  });

  it('meldet Versionskonflikt, Wartezeit und Ausfall', () => {
    expect(benutzerAktionsfehler(new HttpErrorResponse({ status: 412 })))
      .toBe(TEXTE.benutzerAktionsfehler.versionKonflikt);
    expect(benutzerAktionsfehler(new HttpErrorResponse({
      status: 429, headers: new HttpHeaders({ 'Retry-After': '60' }),
    }))).toBe(TEXTE.httpFehler.zuViele(60));
    expect(benutzerAktionsfehler(new HttpErrorResponse({
      status: 503, error: { fehlercode: 'IDENTITAETSDIENST_NICHT_ERREICHBAR' },
    }))).toBe(TEXTE.benutzerAktionsfehler.identitaetsdienst);
  });

  it('zeigt bei BESTAETIGUNG_NOETIG nur geprüfte Auswirkungstexte und sendet nichts', () => {
    const fehler = new HttpErrorResponse({ status: 409, error: {
      fehlercode: 'BESTAETIGUNG_NOETIG', auswirkung: [{ schluessel: 'a', text: 'Der Zugang wird beendet.' }, { text: 7 }],
    } });
    expect(benutzerAktionsfehler(fehler)).toBe(
      `${TEXTE.benutzerAktionsfehler.bestaetigungNoetig} ${TEXTE.benutzerAktionsfehler.bestaetigungAuswirkung} Der Zugang wird beendet.`);
    expect(benutzerAktionsfehler(new HttpErrorResponse({ status: 409, error: { fehlercode: 'BESTAETIGUNG_NOETIG' } })))
      .toBe(TEXTE.benutzerAktionsfehler.bestaetigungNoetig);
  });
});
