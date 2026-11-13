import { Ausgang, Eintrag, Meldungen, bewerten } from './eintrag';

const MELDUNGEN: Meldungen = {
  unbekannt: 'unbekannt',
  wiederholtFehlgeschlagen: 'dreimal',
  fehlercode: { DATEITYP_NICHT_ERLAUBT: 'Dateityp nicht erlaubt' },
};

const eintrag = (fehlversuche = 0): Eintrag => ({
  id: 'e1',
  aufgenommen: '2026-11-13T07:15:00.000Z',
  seiten: [],
  fehlversuche,
  zustand: 'wartet',
});

const fehler = (status: number, mehr: Partial<Extract<Ausgang, { art: 'abgelehnt' }>> = {}): Ausgang => ({
  art: 'abgelehnt',
  status,
  ...mehr,
});

describe('bewerten (Req 4.5, 4.6, 4.8)', () => {
  it('löscht angenommene Einträge und Dubletten', () => {
    const ausgang: Ausgang = {
      art: 'angenommen',
      ergebnisse: [
        { datei: 'a', status: 'NEU', id: '1' },
        { datei: 'b', status: 'DUBLETTE', vorhandenId: '2' },
      ],
    };
    expect(bewerten(eintrag(), ausgang, true, MELDUNGEN)).toEqual({ art: 'loeschen' });
  });

  it('markiert eine teilweise abgelehnte Erfassung mit der Meldung des Servers als fehlerhaft', () => {
    const ausgang: Ausgang = {
      art: 'angenommen',
      ergebnisse: [
        { datei: 'a', status: 'NEU', id: '1' },
        { datei: 'b', status: 'ABGELEHNT', fehler: { status: 415, fehlercode: 'DATEITYP_NICHT_ERLAUBT' } },
      ],
    };
    expect(bewerten(eintrag(), ausgang, true, MELDUNGEN)).toEqual({
      art: 'behalten',
      eintrag: { ...eintrag(), zustand: 'fehlerhaft', meldung: 'Dateityp nicht erlaubt' },
    });
  });

  it('verlangt bei 401 eine neue Anmeldung und lässt den Eintrag unverändert', () => {
    expect(bewerten(eintrag(2), fehler(401), true, MELDUNGEN)).toEqual({ art: 'anmelden' });
  });

  it('markiert 4xx sofort mit der Meldung des Servers als fehlerhaft, ohne zu löschen', () => {
    expect(bewerten(eintrag(), fehler(413, { detail: 'Zu gross.' }), true, MELDUNGEN)).toEqual({
      art: 'behalten',
      eintrag: { ...eintrag(), zustand: 'fehlerhaft', meldung: 'Zu gross.' },
    });
    const nurFehlercode = bewerten(eintrag(), fehler(415, { fehlercode: 'DATEITYP_NICHT_ERLAUBT' }), true, MELDUNGEN);
    expect(nurFehlercode).toEqual({
      art: 'behalten',
      eintrag: { ...eintrag(), zustand: 'fehlerhaft', meldung: 'Dateityp nicht erlaubt' },
    });
    const ohneDetail = bewerten(eintrag(), fehler(400), true, MELDUNGEN);
    expect(ohneDetail).toEqual({ art: 'behalten', eintrag: { ...eintrag(), zustand: 'fehlerhaft', meldung: 'unbekannt' } });
  });

  it('zählt Netzwerkfehler und 5xx online als Fehlversuch, beim dritten fehlerhaft', () => {
    expect(bewerten(eintrag(0), fehler(0), true, MELDUNGEN)).toEqual({ art: 'behalten', eintrag: eintrag(1) });
    expect(bewerten(eintrag(1), fehler(503), true, MELDUNGEN)).toEqual({ art: 'behalten', eintrag: eintrag(2) });
    expect(bewerten(eintrag(2), fehler(500), true, MELDUNGEN)).toEqual({
      art: 'behalten',
      eintrag: { ...eintrag(3), zustand: 'fehlerhaft', meldung: 'dreimal' },
    });
  });

  it('zählt Versuche im Offline-Zustand nicht', () => {
    expect(bewerten(eintrag(2), fehler(0), false, MELDUNGEN)).toEqual({ art: 'behalten', eintrag: eintrag(2) });
  });
});
