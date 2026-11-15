import { HttpErrorResponse } from '@angular/common/http';
import { Dokument } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  Entwurf,
  ausVorschlag,
  betragAnzeige,
  betragGueltig,
  bestaetigungAus,
  bittePruefen,
  datumAnzeige,
  entwurfAus,
  erkannterPartner,
  eingangAnzeige,
  fehlerAus,
  feldName,
} from './entwurf';

function dokument(angaben: Partial<Dokument> = {}): Dokument {
  return {
    id: 'd1',
    eingang: '2026-11-08T09:30:00Z',
    quelle: 'UPLOAD',
    tags: [],
    vertraulichkeit: 'PERSONAL',
    status: 'EINGANGSKORB',
    fristAbgelaufen: false,
    version: 3,
    warnungen: [],
    pdfaFehlt: false,
    unsicher: [],
    versionen: [],
    ...angaben,
  };
}

describe('Entwurf', () => {
  it('nimmt bestätigte Werte vor Vorschlägen und filtert Vorschlags-Tags', () => {
    const entwurf = entwurfAus(
      dokument({
        art: 'QUITTUNG',
        titel: 'Gespeicherter Titel',
        datum: '2026-01-02',
        finanz: { belegnummer: 'Q-1', betrag: 12.5, waehrung: 'CHF' },
        vorschlag: {
          art: 'AUSGANGSRECHNUNG',
          titel: 'KI-Titel',
          belegnummer: 'KI-2',
          betrag: 99,
          waehrung: 'EUR',
          tags: ['KI', 1, 'wichtig'],
          vertraulichkeit: 'INTERN',
        },
      }),
    );

    expect(entwurf).toMatchObject({
      art: 'QUITTUNG',
      titel: 'Gespeicherter Titel',
      datum: '2026-01-02',
      belegnummer: 'Q-1',
      betrag: '12.5',
      waehrung: 'CHF',
      tags: ['KI', 'wichtig'],
      vertraulichkeit: 'INTERN',
    });
  });

  it('erkennt einen unzugeordneten Partner und markiert nur unsichere Vorschlagswerte', () => {
    const d = dokument({
      vorschlag: { partnerName: '  Muster AG  ', betrag: 25, titel: 'Vorschlag' },
      unsicher: ['betrag', 'titel'],
    });

    expect(erkannterPartner(d)).toBe('Muster AG');
    expect(ausVorschlag(d, 'betrag')).toBe(true);
    expect(bittePruefen(d, 'betrag')).toBe(true);
    expect(bittePruefen(d, 'titel')).toBe(true);
    expect(
      erkannterPartner(dokument({ partnerId: 'k1', vorschlag: { partnerName: 'Muster AG' } })),
    ).toBeNull();

    const bestaetigt = dokument({
      finanz: { betrag: 30, waehrung: 'CHF' },
      vorschlag: { betrag: 25 },
      unsicher: ['betrag'],
    });
    expect(ausVorschlag(bestaetigt, 'betrag')).toBe(false);
    expect(bittePruefen(bestaetigt, 'betrag')).toBe(false);
  });

  it('baut eine Bestätigung, normalisiert leere Felder und übernimmt unsichtbare Dokumentwerte', () => {
    const d = dokument({
      personBenutzer: 'benutzer-1',
      geschaeftsjahr: 2025,
      finanz: { waehrung: 'CHF', konto: '1020' },
      vertrag: { kuendigungsfrist: '3 Monate', naechsteFrist: '2026-12-01' },
    });
    const entwurf: Entwurf = {
      ...entwurfAus(d),
      art: 'QUITTUNG',
      titel: '  ',
      datum: '2026-05-04',
      partnerId: ' ',
      projekt: ' P-1 ',
      betrag: "1'234,50",
      waehrung: 'chf',
      vertraulichkeit: '',
      tags: ['synthetisch'],
    };

    const bestaetigung = bestaetigungAus(entwurf, d, {
      ohnePdfa: true,
      fristAbgelaufenAkzeptiert: true,
    });

    expect(bestaetigung).toMatchObject({
      art: 'QUITTUNG',
      titel: null,
      datum: '2026-05-04',
      partnerId: null,
      projekt: 'P-1',
      personBenutzer: 'benutzer-1',
      geschaeftsjahr: 2025,
      betrag: 1234.5,
      waehrung: 'CHF',
      konto: '1020',
      kuendigungsfrist: '3 Monate',
      naechsteFrist: '2026-12-01',
      ohnePdfa: true,
      fristAbgelaufenAkzeptiert: true,
    });
    expect(bestaetigung.tags).toEqual(['synthetisch']);
    expect(bestaetigung.tags).not.toBe(entwurf.tags);
    expect(bestaetigung.vertraulichkeit).toBeUndefined();
  });

  it('prüft Betragsgrenzen und macht ungültige Werte nicht zu Zahlen', () => {
    expect(betragGueltig('')).toBe(true);
    expect(betragGueltig('9999999999.99')).toBe(true);
    expect(betragGueltig("1'234,50")).toBe(true);
    expect(betragGueltig('10000000000.00')).toBe(false);
    expect(betragGueltig('12.345')).toBe(false);
    expect(betragGueltig('kein Betrag')).toBe(false);

    const d = dokument();
    const entwurf: Entwurf = { ...entwurfAus(d), betrag: '12.345' };
    expect(bestaetigungAus(entwurf, d).betrag).toBeNull();
  });

  it('ordnet Fehlerfelder zu und behält ungültige Anzeigeeingaben unverändert', () => {
    expect(
      fehlerAus(
        new HttpErrorResponse({
          status: 422,
          error: { fehlercode: 422, detail: 3, felder: ['datum', 7, 'partner'] },
        }),
      ),
    ).toEqual({
      status: 422,
      fehlercode: undefined,
      detail: undefined,
      felder: ['datum', 'partner'],
    });
    expect(fehlerAus(new Error('synthetischer Fehler'))).toEqual({ status: -1, felder: [] });
    expect(feldName('qr_referenz')).toBe(TEXTE.eingangskorb.feld['qrReferenz']);
    expect(feldName('unbekanntes_feld')).toBe('unbekanntes_feld');

    expect(betragAnzeige('1234,5', 'chf')).toBe("CHF 1'234.50");
    expect(betragAnzeige('kein Betrag', 'CHF')).toBe('kein Betrag');
    expect(datumAnzeige('2026-11-08')).toBe('08.11.2026');
    expect(datumAnzeige('ungültig')).toBe('ungültig');
    expect(eingangAnzeige('2026-11-08T23:30:00Z')).toBe('09.11.2026');
    expect(eingangAnzeige('ungültig')).toBe('ungültig');
  });
});
