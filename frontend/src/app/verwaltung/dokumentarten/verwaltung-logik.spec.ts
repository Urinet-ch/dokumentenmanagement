import { HttpErrorResponse } from '@angular/common/http';
import { Dokumentart } from '../../gemeinsam/api';
import {
  FeldEntwurf,
  feldEditorFehler,
  pflichtfelderOrdnen,
  schemaAusFeldern,
  schemaEditor,
  versionsvergleich,
  verwaltungsFehlerart,
  zusatzPflichtfelder,
} from './verwaltung-logik';

function art(zusatzSchema: object, pflichtfelder: string[] = []): Dokumentart {
  return {
    code: 'TEST',
    name: 'Testart',
    gruppen: [],
    pflichtfelder,
    zusatzSchema,
    vertraulichkeit: 'NORMAL',
    gesetzlichePflicht: false,
    aktiv: true,
    selbstAusgestellt: false,
    belegnummerMuster: [],
    kiVorschlag: false,
    kiFelder: [],
    reihenfolge: 1,
  };
}

describe('Verwaltung: Zusatz-Schema-Editor', () => {
  it('bearbeitet Felder mit x-labels und Listenwerten ohne Beschriftungen zu verlieren', () => {
    const schema = {
      type: 'object',
      properties: {
        status: {
          type: 'string',
          title: 'Status',
          description: 'Öffentlichkeitsstufe',
          enum: ['intern', 'oeffentlich'],
          'x-labels': { intern: 'Intern', oeffentlich: 'Öffentlich' },
        },
        gruppen: {
          type: 'array',
          items: { type: 'string', enum: ['vhf', 'gsk'], 'x-labels': { vhf: 'VHF', gsk: 'GSK' } },
          minItems: 1,
          uniqueItems: true,
        },
      },
      required: ['status'],
      additionalProperties: false,
    };
    const editor = schemaEditor(art(schema));

    expect(editor.modus).toBe('felder');
    if (editor.modus !== 'felder') {
      return;
    }
    expect(editor.felder[0]).toMatchObject({
      code: 'status',
      name: 'Status',
      beschreibung: 'Öffentlichkeitsstufe',
      pflicht: true,
      optionen: [
        { wert: 'intern', beschriftung: 'Intern' },
        { wert: 'oeffentlich', beschriftung: 'Öffentlich' },
      ],
    });
    const erzeugt = schemaAusFeldern(editor.felder, schema);
    expect(erzeugt).toMatchObject({
      type: 'object',
      required: ['status'],
      additionalProperties: false,
      properties: {
        status: {
          type: 'string',
          title: 'Status',
          description: 'Öffentlichkeitsstufe',
          enum: ['intern', 'oeffentlich'],
          'x-labels': { intern: 'Intern', oeffentlich: 'Öffentlich' },
        },
        gruppen: {
          type: 'array',
          items: { type: 'string', enum: ['vhf', 'gsk'], 'x-labels': { vhf: 'VHF', gsk: 'GSK' } },
          minItems: 1,
          uniqueItems: true,
        },
      },
    });
  });

  it('bietet nicht darstellbare Einschränkungen ausschliesslich als JSON an', () => {
    const schema = {
      type: 'object',
      properties: { periode: { type: 'string', pattern: '^\\\\d{4}$' } },
      additionalProperties: false,
    };

    expect(schemaEditor(art(schema))).toEqual({
      modus: 'json',
      inhalt: JSON.stringify(schema, null, 2),
    });
  });

  it('validiert eindeutige Feldcodes und Auswahlwerte', () => {
    const felder: FeldEntwurf[] = [
      {
        code: 'status',
        name: 'Status',
        typ: 'auswahl',
        pflicht: false,
        pflichtInListe: false,
        pflichtImSchema: false,
        beschreibung: '',
        optionen: [
          { wert: 'aktiv', beschriftung: 'Aktiv' },
          { wert: 'aktiv', beschriftung: 'Aktiv' },
        ],
        eindeutig: false,
      },
    ];

    expect(feldEditorFehler(felder)).toBe('optionen');
    expect(feldEditorFehler([{ ...felder[0], code: 'Nicht gültig' }])).toBe('code');
  });

  it('lässt Pflicht aus den Pflichtfeldern im Rundlauf dort und schreibt sie nicht nach required', () => {
    const schema = {
      type: 'object',
      properties: {
        periode_von: { type: 'string', format: 'date' },
        periode_bis: { type: 'string', format: 'date' },
      },
      additionalProperties: false,
    };
    const editor = schemaEditor(art(schema, ['titel', 'zusatz.periode_von', 'zusatz.periode_bis']));

    expect(editor.modus).toBe('felder');
    if (editor.modus !== 'felder') {
      return;
    }
    expect(editor.felder.every((feld) => feld.pflicht)).toBe(true);
    expect(schemaAusFeldern(editor.felder, schema)).toEqual(schema);
    expect(zusatzPflichtfelder(editor.felder)).toEqual([
      'zusatz.periode_von',
      'zusatz.periode_bis',
    ]);
  });

  it('behält required aus dem Schema, ohne es in die Pflichtfelder zu kopieren', () => {
    const schema = {
      type: 'object',
      properties: { status: { type: 'string' } },
      required: ['status'],
    };
    const editor = schemaEditor(art(schema));
    if (editor.modus !== 'felder') {
      throw new Error('Feld-Modus erwartet');
    }

    expect(schemaAusFeldern(editor.felder, schema)).toEqual(schema);
    expect(zusatzPflichtfelder(editor.felder)).toEqual([]);
  });

  it('führt eine neu gesetzte Pflicht nur in den Pflichtfeldern, nicht im Schema', () => {
    const feld: FeldEntwurf = {
      code: 'prioritaet',
      name: 'prioritaet',
      typ: 'text',
      pflicht: true,
      pflichtInListe: false,
      pflichtImSchema: false,
      beschreibung: '',
      optionen: [],
      eindeutig: false,
    };

    expect(schemaAusFeldern([feld], {})['required']).toBeUndefined();
    expect(zusatzPflichtfelder([feld])).toEqual(['zusatz.prioritaet']);
  });

  it('lässt geleerte Listengrenzen weg statt null ins Schema zu schreiben', () => {
    const feld = {
      code: 'tags',
      name: 'tags',
      typ: 'liste',
      pflicht: false,
      pflichtInListe: false,
      pflichtImSchema: false,
      beschreibung: '',
      optionen: [],
      minEintraege: null,
      maxEintraege: null,
      eindeutig: false,
    } as unknown as FeldEntwurf;

    const erzeugt = schemaAusFeldern([feld], {});

    expect(erzeugt['properties']).toEqual({ tags: { type: 'array', items: { type: 'string' } } });
    expect(feldEditorFehler([feld])).toBeNull();
  });

  it('behält die Reihenfolge der Pflichtfelder und hängt neue an', () => {
    expect(
      pflichtfelderOrdnen(
        ['zusatz.a', 'titel', 'zusatz.b'],
        ['titel', 'zusatz.b', 'zusatz.a', 'zusatz.c'],
      ),
    ).toEqual(['zusatz.a', 'titel', 'zusatz.b', 'zusatz.c']);
  });
});

describe('Verwaltung: Fehlerarten', () => {
  it('erkennt Gebrauch und vergebenen Code der Dokumentarten', () => {
    const fehler = (fehlercode: string) =>
      new HttpErrorResponse({ status: 409, error: { fehlercode } });

    expect(verwaltungsFehlerart(fehler('DOKUMENTART_IN_GEBRAUCH'))).toBe('gebrauch');
    expect(verwaltungsFehlerart(fehler('KONFIGURATION_IN_GEBRAUCH'))).toBe('gebrauch');
    expect(verwaltungsFehlerart(fehler('DOKUMENTART_EXISTIERT'))).toBe('existiert');
  });
});

describe('Verwaltung: Versionsvergleich', () => {
  it('verdeckt Kennungen in Versionsunterschieden', () => {
    const aenderungen = versionsvergleich(
      { name: 'Vorher', kennungen: { iban: 'CH0000000000000000000', uid: 'CHE-123' } },
      { name: 'Nachher', kennungen: { iban: 'CH1111111111111111111', uid: 'CHE-456' } },
    );

    expect(aenderungen.find((aenderung) => aenderung.pfad === '/name')?.neu).toBe('Nachher');
    const kennungen = aenderungen.filter((aenderung) => aenderung.werteVerdeckt);
    expect(kennungen).toHaveLength(2);
    expect(kennungen.every((aenderung) => aenderung.alt === '' && aenderung.neu === '')).toBe(true);
    expect(JSON.stringify(aenderungen)).not.toContain('CH0000000000000000000');
    expect(JSON.stringify(aenderungen)).not.toContain('CHE-123');
  });

  it('verdeckt Kennungen auch, wenn das Elternobjekt in einer Version fehlt', () => {
    const aenderungen = versionsvergleich(
      { name: 'Vorher' },
      { name: 'Vorher', einstellungen: { kennungen: { iban: 'CH2222222222222222222' } } },
    );

    expect(aenderungen).toEqual([
      { pfad: '/einstellungen', alt: '', neu: '', werteVerdeckt: true },
    ]);
  });

  it('vergleicht Listen mit Code je Eintrag statt als Ganzes', () => {
    const aenderungen = versionsvergleich(
      {
        arten: [
          { code: 'RECHNUNG', name: 'Rechnung' },
          { code: 'OFFERTE', name: 'Offerte' },
        ],
        pflichtfelder: ['titel'],
      },
      {
        arten: [
          { code: 'OFFERTE', name: 'Offerte' },
          { code: 'RECHNUNG', name: 'Ausgangsrechnung' },
          { code: 'VERTRAG', name: 'Vertrag' },
        ],
        pflichtfelder: ['titel'],
      },
    );

    expect(aenderungen.map((aenderung) => [aenderung.pfad, aenderung.alt, aenderung.neu])).toEqual([
      ['/arten/RECHNUNG/name', 'Rechnung', 'Ausgangsrechnung'],
      ['/arten/VERTRAG', '—', '{"code":"VERTRAG","name":"Vertrag"}'],
    ]);
  });
});
