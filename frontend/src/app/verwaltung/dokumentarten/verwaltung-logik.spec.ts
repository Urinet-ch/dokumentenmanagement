import { Dokumentart } from '../../gemeinsam/api';
import {
  FeldEntwurf,
  feldEditorFehler,
  schemaAusFeldern,
  schemaEditor,
  versionsvergleich,
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
});
