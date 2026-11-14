import { ChatFehler } from './chat-port';
import {
  SSE_START,
  SseEreignis,
  SseZustand,
  chatEreignis,
  quellenAus,
  sseLesen,
} from './sse-leser';

/** Liest die Stücke nacheinander und sammelt alle Ereignisse. */
function lesen(...stuecke: string[]): { ereignisse: SseEreignis[]; zustand: SseZustand } {
  let zustand = SSE_START;
  const ereignisse: SseEreignis[] = [];
  for (const stueck of stuecke) {
    const ergebnis = sseLesen(zustand, stueck);
    zustand = ergebnis.zustand;
    ereignisse.push(...ergebnis.ereignisse);
  }
  return { ereignisse, zustand };
}

describe('SSE-Leser (Spec 02, Aufgabe 7.4)', () => {
  it('liest event- und data-Zeilen eines Ereignisses', () => {
    expect(lesen('event: text\ndata: {"delta":"Hallo"}\n\n').ereignisse).toEqual([
      { name: 'text', daten: '{"delta":"Hallo"}' },
    ]);
  });

  it('setzt ein Ereignis über Stückgrenzen zusammen, auch mitten im Feldnamen und im JSON', () => {
    const { ereignisse } = lesen('ev', 'ent: te', 'xt\nda', 'ta: {"del', 'ta":"Ha"}', '\n', '\n');
    expect(ereignisse).toEqual([{ name: 'text', daten: '{"delta":"Ha"}' }]);
  });

  it('liest mehrere Ereignisse aus einem Stück', () => {
    const { ereignisse } = lesen(
      'event: werkzeug\ndata: {"name":"kennzahl"}\n\nevent: text\ndata: {"delta":"a"}\n\nevent: text\ndata: {"delta":"b"}\n\n',
    );
    expect(ereignisse.map((e) => e.name)).toEqual(['werkzeug', 'text', 'text']);
  });

  it('versteht \\r\\n und \\r als Zeilenende, auch wenn \\r\\n über eine Stückgrenze reicht', () => {
    expect(lesen('event: text\r\ndata: {"delta":"x"}\r\n\r\n').ereignisse).toHaveLength(1);
    // ein \r am Ende eines Stücks wartet auf das nächste Stück (es könnte ein \r\n werden)
    expect(lesen('event: text\rdata: {"delta":"x"}\r\r').ereignisse).toHaveLength(0);
    expect(lesen('event: text\rdata: {"delta":"x"}\r\r', ': weiter').ereignisse).toHaveLength(1);
    const getrennt = lesen('event: text\r', '\ndata: {"delta":"x"}\r', '\n\r', '\n');
    expect(getrennt.ereignisse).toEqual([{ name: 'text', daten: '{"delta":"x"}' }]);
  });

  it('überspringt Kommentarzeilen und unbekannte Felder', () => {
    const { ereignisse } = lesen(
      ': ping\n\nid: 7\nretry: 1000\nevent: text\n: dazwischen\ndata: {"delta":"x"}\n\n',
    );
    expect(ereignisse).toEqual([{ name: 'text', daten: '{"delta":"x"}' }]);
  });

  it('verbindet mehrere data-Zeilen mit einem Zeilenumbruch und nimmt nur ein Leerzeichen nach dem Doppelpunkt weg', () => {
    expect(lesen('event: text\ndata:{"delta":\ndata:  "x"}\n\n').ereignisse[0].daten).toBe(
      '{"delta":\n "x"}',
    );
  });

  it('meldet ein angefangenes Ereignis erst mit der Leerzeile', () => {
    const { ereignisse, zustand } = lesen('event: text\ndata: {"delta":"x"}\n');
    expect(ereignisse).toEqual([]);
    expect(sseLesen(zustand, '\n').ereignisse).toHaveLength(1);
  });

  it('übergeht unbekannte Ereignisse', () => {
    expect(chatEreignis({ name: 'neu', daten: 'kein JSON' })).toBeNull();
    expect(chatEreignis({ name: 'message', daten: '{}' })).toBeNull();
  });

  it('wandelt die bekannten Ereignisse um', () => {
    expect(chatEreignis({ name: 'werkzeug', daten: '{"name":"fristen"}' })).toEqual({
      typ: 'werkzeug',
      name: 'fristen',
    });
    expect(chatEreignis({ name: 'text', daten: '{"delta":"Zeile\\nzwei"}' })).toEqual({
      typ: 'text',
      delta: 'Zeile\nzwei',
    });
    expect(chatEreignis({ name: 'fertig', daten: '{"gespraechId":"g1","tokens":12}' })).toEqual({
      typ: 'fertig',
      gespraechId: 'g1',
    });
    expect(
      chatEreignis({ name: 'fehler', daten: '{"meldung":"Nicht verfügbar","gespraechId":"g2"}' }),
    ).toEqual({
      typ: 'fehler',
      meldung: 'Nicht verfügbar',
      gespraechId: 'g2',
    });
    expect(
      chatEreignis({
        name: 'quellen',
        daten:
          '[{"titel":"Rechnung 2026-004","link":"/dokument/d1"},{"datenstand":{"zeitpunkt":"2026-11-14T08:00:00Z","anzahl":12}}]',
      }),
    ).toEqual({
      typ: 'quellen',
      quellen: [
        { titel: 'Rechnung 2026-004', link: '/dokument/d1' },
        { datenstand: { zeitpunkt: '2026-11-14T08:00:00Z', anzahl: 12 } },
      ],
    });
  });

  it('wertet ungültiges JSON und fehlende Felder als Fehler der Antwort', () => {
    for (const sse of [
      { name: 'text', daten: '{"delta":' },
      { name: 'text', daten: '{"anderes":"x"}' },
      { name: 'fertig', daten: '[]' },
      { name: 'quellen', daten: '{"titel":"x"}' },
      { name: 'fehler', daten: '"nur Text"' },
    ]) {
      expect(() => chatEreignis(sse)).toThrow(ChatFehler);
      try {
        chatEreignis(sse);
      } catch (fehler) {
        expect((fehler as ChatFehler).art).toBe('ANTWORT');
      }
    }
  });

  it('lässt Quellen in unbekannter Form weg und liest den Datenstand auch flach', () => {
    expect(
      quellenAus([
        { wissen: { titel: 'Seite', link: '/wissen/x', stand: '2026-11-01' }, dokumente: [] },
        { datenstand: '2026-11-14T08:00:00Z', anzahl: 3 },
        'Text',
        null,
      ]),
    ).toEqual([{ datenstand: { zeitpunkt: '2026-11-14T08:00:00Z', anzahl: 3 } }]);
    expect(quellenAus('kein Array')).toEqual([]);
  });
});
