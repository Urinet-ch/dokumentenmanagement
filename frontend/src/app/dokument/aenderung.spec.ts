import { Dokument, Dokumentart } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { aenderungAus, feldText, formularAus, formularFeld, zusatzFelder, zusatzGueltig } from './aenderung';

const ART = (teil: Partial<Dokumentart> = {}): Dokumentart => ({
  code: 'OFFERTE',
  name: 'Offerte',
  gruppen: ['finanz'],
  pflichtfelder: ['titel', 'datum', 'betrag', 'zusatz.angebot_gueltig_bis'],
  zusatzSchema: {
    type: 'object',
    properties: {
      angebot_gueltig_bis: { type: 'string', format: 'date' },
      stufe: { enum: ['A', 'B'] },
      anzahl: { type: 'integer' },
      verbindlich: { type: 'boolean' },
      positionen: { type: 'array' },
    },
  },
  vertraulichkeit: 'NORMAL',
  gesetzlichePflicht: true,
  aktiv: true,
  ...teil,
});

const DOKUMENT = (teil: Partial<Dokument> = {}): Dokument => ({
  id: 'd1',
  art: 'OFFERTE',
  titel: 'Offerte Verein',
  datum: '2026-11-08',
  eingang: '2026-11-08T08:00:00Z',
  quelle: 'UPLOAD',
  tags: ['verein', 'web'],
  vertraulichkeit: 'NORMAL',
  status: 'ARCHIVIERT',
  fristAbgelaufen: false,
  geschaeftsjahr: 2026,
  zusatz: { angebot_gueltig_bis: '2026-12-31', positionen: [1, 2] },
  finanz: { belegnummer: 'O-2026-005', betrag: 1234.5, waehrung: 'CHF' },
  version: 3,
  warnungen: [],
  pdfaFehlt: false,
  unsicher: [],
  versionen: [],
  ...teil,
});

const kontext = (teil = {}) => ({ art: ART(), darfVertraulichkeit: true, ...teil });

describe('Metadaten bearbeiten: Änderung (Req 10.1)', () => {
  it('füllt das Formular aus dem Dokument, Betrag mit zwei Stellen', () => {
    const f = formularAus(DOKUMENT());
    expect(f.betrag).toBe('1234.50');
    expect(f.tags).toBe('verein, web');
    expect(f.geschaeftsjahr).toBe('2026');
    expect(f.zusatz).toEqual({ angebot_gueltig_bis: '2026-12-31' });
    expect(f.grund).toBe('');
  });

  it('sendet nur geänderte Felder und bei ARCHIVIERT den Grund', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(d, start, { ...start, titel: ' Offerte FC ', projekt: '', grund: ' Tippfehler ' }, kontext());
    expect(ergebnis).toEqual({ aenderung: { titel: 'Offerte FC', grund: 'Tippfehler' } });
  });

  it('sendet beim Artwechsel zusatz ohne Schlüssel der alten Art (QUITTUNG ohne Felder)', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const quittung = ART({ code: 'QUITTUNG', zusatzSchema: { type: 'object', properties: {} }, pflichtfelder: [] });
    const ergebnis = aenderungAus(d, start, { ...start, art: 'QUITTUNG', grund: 'Art korrigiert' }, kontext({ art: quittung }));
    expect(ergebnis).toEqual({ aenderung: { art: 'QUITTUNG', zusatz: {}, grund: 'Art korrigiert' } });
  });

  it('sendet beim Artwechsel nur die Felder der neuen Art (LOHNABRECHNUNG)', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const lohn = ART({
      code: 'LOHNABRECHNUNG',
      zusatzSchema: { type: 'object', properties: { periode: { type: 'string' }, person: { type: 'string' } } },
      pflichtfelder: ['zusatz.periode', 'zusatz.person'],
    });
    const ergebnis = aenderungAus(
      d,
      start,
      { ...start, art: 'LOHNABRECHNUNG', grund: 'g', zusatz: { periode: '2026-10', person: 'Joel' } },
      kontext({ art: lohn }),
    );
    expect(ergebnis).toEqual({
      aenderung: { art: 'LOHNABRECHNUNG', zusatz: { periode: '2026-10', person: 'Joel' }, grund: 'g' },
    });
  });

  it('verlangt bei ARCHIVIERT einen Grund und sendet dann nichts', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(d, start, { ...start, titel: 'Neu' }, kontext());
    expect(ergebnis).toEqual({ pruefung: { meldung: TEXTE.dokument.bearbeiten.grundFehlt, felder: ['grund'] } });
  });

  it('lässt den Grund im Eingangskorb weg, wenn keiner angegeben ist', () => {
    const d = DOKUMENT({ status: 'EINGANGSKORB' });
    const start = formularAus(d);
    expect(aenderungAus(d, start, { ...start, titel: 'Neu' }, kontext())).toEqual({ aenderung: { titel: 'Neu' } });
  });

  it('weist einen zu langen Grund ab', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(d, start, { ...start, titel: 'Neu', grund: 'x'.repeat(1001) }, kontext());
    expect(ergebnis).toEqual({ pruefung: { meldung: TEXTE.dokument.bearbeiten.grundZuLang, felder: ['grund'] } });
  });

  it('meldet, wenn nichts geändert ist', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(d, start, { ...start, grund: 'nur Grund' }, kontext());
    expect(ergebnis).toEqual({ pruefung: { meldung: TEXTE.dokument.bearbeiten.keineAenderung, felder: [] } });
  });

  it('leert ein Feld mit null und liest Beträge mit Apostroph und Komma', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(d, start, { ...start, belegnummer: '', betrag: "1'500,25", grund: 'g' }, kontext());
    expect(ergebnis).toEqual({ aenderung: { belegnummer: null, betrag: 1500.25, grund: 'g' } });
  });

  it('prüft Betrag, Währung und Geschäftsjahr vor dem Senden', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(
      d,
      start,
      { ...start, betrag: '12.345', waehrung: '', geschaeftsjahr: '', grund: 'g' },
      kontext(),
    );
    expect('pruefung' in ergebnis && ergebnis.pruefung.felder).toEqual(['geschaeftsjahr', 'betrag', 'waehrung']);
  });

  it('sendet Finanzfelder nur, wenn die Art die Gruppe hat', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ohne = kontext({ art: ART({ gruppen: [] }) });
    expect(aenderungAus(d, start, { ...start, belegnummer: 'X', titel: 'T', grund: 'g' }, ohne)).toEqual({
      aenderung: { titel: 'T', grund: 'g' },
    });
  });

  it('sendet die Vertraulichkeit nur mit A8', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const neu = { ...start, vertraulichkeit: 'INTERN', titel: 'T', grund: 'g' };
    expect(aenderungAus(d, start, neu, kontext({ darfVertraulichkeit: false }))).toEqual({
      aenderung: { titel: 'T', grund: 'g' },
    });
    expect(aenderungAus(d, start, neu, kontext())).toEqual({
      aenderung: { titel: 'T', vertraulichkeit: 'INTERN', grund: 'g' },
    });
  });

  it('ersetzt die Zusatzwerte ganz, behält verschachtelte und wandelt Typen', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(
      d,
      start,
      { ...start, zusatz: { angebot_gueltig_bis: '', stufe: 'B', anzahl: "1'200", verbindlich: 'true' }, grund: 'g' },
      kontext(),
    );
    expect(ergebnis).toEqual({
      aenderung: { zusatz: { positionen: [1, 2], stufe: 'B', anzahl: 1200, verbindlich: true }, grund: 'g' },
    });
  });

  it('meldet ungültige Zusatzwerte mit Feldnamen', () => {
    const d = DOKUMENT();
    const start = formularAus(d);
    const ergebnis = aenderungAus(d, start, { ...start, zusatz: { anzahl: '1.5' }, grund: 'g' }, kontext());
    expect(ergebnis).toEqual({
      pruefung: {
        meldung: TEXTE.dokument.bearbeiten.ungueltig(TEXTE.eingangskorb.zusatzFeld('anzahl')),
        felder: ['zusatz.anzahl'],
      },
    });
  });

  it('liest Zusatzfelder aus dem Schema und überspringt Listen', () => {
    expect(zusatzFelder(ART()).map((f) => [f.name, f.typ, f.pflicht])).toEqual([
      ['angebot_gueltig_bis', 'datum', true],
      ['stufe', 'auswahl', false],
      ['anzahl', 'ganzzahl', false],
      ['verbindlich', 'janein', false],
    ]);
    expect(zusatzFelder(undefined)).toEqual([]);
  });

  it('übersetzt Feldnamen aus den Problem Details', () => {
    expect(formularFeld('partner')).toBe('partnerId');
    expect(formularFeld('qr_referenz')).toBe('qrReferenz');
    expect(formularFeld('zusatz.periode')).toBe('zusatz.periode');
    expect(feldText('qr_referenz')).toBe('QR-Referenz');
    expect(feldText('geschaeftsjahr')).toBe('Geschäftsjahr');
  });
});

describe('Zusatzfelder vom Typ Liste (Array)', () => {
  const schema = {
    type: 'object',
    properties: {
      verband: { type: 'array', items: { type: 'string', enum: ['vhf', 'gsk'] }, minItems: 1, uniqueItems: true },
      stichworte: { type: 'array', items: { type: 'string' } },
      zahlen: { type: 'array', items: { type: 'integer' } },
    },
  };
  const art = ART({ pflichtfelder: ['zusatz.verband'], zusatzSchema: schema });

  it('erkennt Mehrfachauswahl und freie Liste, nicht aber Listen anderer Elementtypen', () => {
    expect(zusatzFelder(art)).toEqual([
      { name: 'verband', typ: 'mehrfach', optionen: ['vhf', 'gsk'], pflicht: true, minEintraege: 1, eindeutig: true },
      { name: 'stichworte', typ: 'liste', optionen: [], pflicht: false, minEintraege: 0, eindeutig: false },
    ]);
  });

  it('prüft Pflicht, Auswahl, minItems und uniqueItems', () => {
    const [verband, stichworte] = zusatzFelder(art);
    expect(zusatzGueltig(verband, '')).toBe(false);
    expect(zusatzGueltig(verband, 'vhf')).toBe(true);
    expect(zusatzGueltig(verband, 'vhf\ngsk')).toBe(true);
    expect(zusatzGueltig(verband, 'xyz')).toBe(false);
    expect(zusatzGueltig(verband, 'vhf\nvhf')).toBe(false);
    expect(zusatzGueltig(stichworte, '')).toBe(true);
    expect(zusatzGueltig({ ...stichworte, minEintraege: 2 }, 'a')).toBe(false);
  });

  it('übernimmt Listenwerte ins Formular und sendet sie als Array', () => {
    const d = DOKUMENT({ zusatz: { verband: ['vhf'] } });
    const start = formularAus(d);
    expect(start.zusatz['verband']).toBe('vhf');
    const neu = { ...start, zusatz: { ...start.zusatz, verband: 'vhf\ngsk' }, grund: 'Verband ergänzt' };
    expect(aenderungAus(d, start, neu, kontext({ art }))).toEqual({
      aenderung: { zusatz: { verband: ['vhf', 'gsk'] }, grund: 'Verband ergänzt' },
    });
  });

  it('weist ein leeres Pflicht-Array beim Ändern zurück', () => {
    const d = DOKUMENT({ zusatz: { verband: ['vhf'] } });
    const start = formularAus(d);
    const neu = { ...start, zusatz: { ...start.zusatz, verband: '' }, grund: 'x' };
    expect(aenderungAus(d, start, neu, kontext({ art }))).toMatchObject({
      pruefung: { felder: ['zusatz.verband'] },
    });
  });
});
