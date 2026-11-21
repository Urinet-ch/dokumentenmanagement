import { ANLEITUNG_MAX_BYTES, AnleitungZuGross, Anleitungsteil, anleitungMarkdown } from './anleitung-markdown';

const PFADE = new Set(['/', '/erfassen', '/eingangskorb']);
const lesen = (markdown: string, praefix?: string) =>
  anleitungMarkdown(markdown, { istAppPfad: (pfad) => PFADE.has(pfad), praefix });
const teile = (markdown: string) => lesen(markdown).teile;
const text = (...t: string[]): Anleitungsteil[] => t.map((x) => ({ art: 'text', text: x }));
const absatz = (...kinder: Anleitungsteil[]): Anleitungsteil => ({ art: 'p', kinder });

describe('anleitungMarkdown (Spec 07, Req 7.3, 7.4)', () => {
  describe('Teilmenge', () => {
    it('Überschriften der Stufen 2 bis 4, mit id aus dem Anker der Datei', () => {
      expect(lesen('## Kamera und Seiten\n### Zuschnitt\n#### Über «Zuschnitt»\n## Kamera und Seiten', 'erfassen').teile).toEqual([
        { art: 'h2', anker: 'erfassen-kamera-und-seiten', kinder: text('Kamera und Seiten') },
        { art: 'h3', anker: 'erfassen-zuschnitt', kinder: text('Zuschnitt') },
        { art: 'h4', anker: 'erfassen-ueber-zuschnitt', kinder: text('Über «Zuschnitt»') },
        { art: 'h2', anker: 'erfassen-kamera-und-seiten-2', kinder: text('Kamera und Seiten') },
      ]);
    });

    it('Absätze bis zur Leerzeile, Zeilen mit Leerzeichen verbunden, auch mit CRLF', () => {
      expect(teile('Erste Zeile\r\nzweite Zeile\r\n\r\nNeuer Absatz')).toEqual([
        absatz(...text('Erste Zeile zweite Zeile')),
        absatz(...text('Neuer Absatz')),
      ]);
    });

    it('Listen mit «- » und «1. », eine Ebene, Fortsetzungszeilen gehören zum Punkt', () => {
      expect(teile('- eins\n- zwei\n  weiter\n\n1. erstens\n2. zweitens')).toEqual([
        { art: 'ul', kinder: [{ art: 'li', kinder: text('eins') }, { art: 'li', kinder: text('zwei weiter') }] },
        { art: 'ol', kinder: [{ art: 'li', kinder: text('erstens') }, { art: 'li', kinder: text('zweitens') }] },
      ]);
    });

    it('fett, kursiv und Code, auch verschachtelt', () => {
      expect(teile('**Fett und *kursiv* zusammen** und `Code **nicht fett**`')).toEqual([
        absatz(
          { art: 'strong', kinder: [...text('Fett und '), { art: 'em', kinder: text('kursiv') }, ...text(' zusammen')] },
          ...text(' und '),
          { art: 'code', text: 'Code **nicht fett**' },
        ),
      ]);
      expect(teile('*kursiv* und **fett**')).toEqual([
        absatz({ art: 'em', kinder: text('kursiv') }, ...text(' und '), { art: 'strong', kinder: text('fett') }),
      ]);
    });

    it('Hinweis- und Achtung-Block als Baustein «hinweis»', () => {
      expect(teile('> **Hinweis:** Erst\n> danach.\n\n> **Achtung:** Endgültig.')).toEqual([
        { art: 'hinweis', variante: 'info', kinder: [{ art: 'strong', kinder: text('Hinweis:') }, ...text(' Erst danach.')] },
        { art: 'hinweis', variante: 'warnung', kinder: [{ art: 'strong', kinder: text('Achtung:') }, ...text(' Endgültig.')] },
      ]);
    });

    it('Links auf Pfade der App und auf Abschnitte des Katalogs', () => {
      expect(lesen('Zur [Übersicht](/) oder nach [Erfassen](/erfassen), siehe [Probleme](#probleme).')).toEqual({
        teile: [
          absatz(
            ...text('Zur '),
            { art: 'link', text: 'Übersicht', pfad: '/' },
            ...text(' oder nach '),
            { art: 'link', text: 'Erfassen', pfad: '/erfassen' },
            ...text(', siehe '),
            { art: 'abschnitt', text: 'Probleme', anker: 'probleme' },
            ...text('.'),
          ),
        ],
        befunde: [],
      });
    });

    it('Platzhalter {name} und {rolle}, andere geschweifte Klammern bleiben Text', () => {
      expect(teile('Im {name} als {rolle}, nicht {mandant}.')).toEqual([
        absatz(
          ...text('Im '),
          { art: 'platzhalter', platzhalter: 'name' },
          ...text(' als '),
          { art: 'platzhalter', platzhalter: 'rolle' },
          ...text(', nicht {mandant}.'),
        ),
      ]);
    });

    it('nicht geschlossene Zeichen bleiben Text ohne Befund', () => {
      expect(lesen('2 * 3, **offen, `offen, [offen](')).toEqual({
        teile: [absatz(...text('2 * 3, **offen, `offen, [offen]('))],
        befunde: [],
      });
    });
  });

  describe('alles andere erscheint als Text und wird gemeldet', () => {
    const fremd = [
      'https://example.com',
      'http://example.com',
      'mailto:muster@example.com',
      'javascript:alert(1)',
      '//example.com',
      '/gibt-es-nicht',
      '/erfassen?x=1',
      'erfassen',
    ];
    for (const ziel of fremd) {
      it(`Link auf «${ziel}»`, () => {
        const ergebnis = lesen(`Siehe [hier](${ziel}).`);
        expect(ergebnis.teile).toEqual([absatz(...text(`Siehe [hier](${ziel}).`))]);
        expect(ergebnis.befunde).toHaveLength(1);
      });
    }

    it('unbekannter Anker', () => {
      expect(lesen('[x](#gibt-es-nicht)')).toEqual({
        teile: [absatz(...text('[x](#gibt-es-nicht)'))],
        befunde: ['Zeile 1: unbekannter Anker'],
      });
    });

    it('HTML, auch ein Skript, nur als Text', () => {
      const ergebnis = lesen('<script>alert(1)</script> und <b>fett</b>');
      expect(ergebnis.teile).toEqual([absatz(...text('<script>alert(1)</script> und <b>fett</b>'))]);
      expect(ergebnis.befunde.length).toBeGreaterThan(0);
      expect(JSON.stringify(ergebnis.teile)).not.toContain('"art":"script"');
    });

    it('Bilder, Tabellen, Fussnoten, Trennlinien', () => {
      expect(lesen('![Bild](/erfassen)').teile).toEqual([absatz(...text('![Bild](/erfassen)'))]);
      expect(lesen('![Bild](/erfassen)').befunde).toEqual(['Zeile 1: Bild']);
      expect(lesen('| a | b |\n| - | - |').befunde).toContain('Zeile 1: Tabelle');
      expect(lesen('Text[^1]').befunde).toEqual(['Zeile 1: Fussnote']);
      expect(lesen('Oben\n\n---').befunde).toEqual(['Zeile 3: Trennlinie']);
    });

    it('Überschrift der Stufe 1 und 5, Codeblock und Zitat ohne Vermerk wörtlich', () => {
      expect(lesen('# Titel')).toEqual({ teile: [absatz(...text('# Titel'))], befunde: ['Zeile 1: Überschrift der Stufe 1'] });
      expect(lesen('##### Tief').befunde).toEqual(['Zeile 1: Überschrift der Stufe 5']);
      expect(lesen('```\n**nicht fett** [x](/)\n```\nDanach')).toEqual({
        teile: [absatz(...text('```\n**nicht fett** [x](/)\n```')), absatz(...text('Danach'))],
        befunde: ['Zeile 1: Codeblock'],
      });
      expect(lesen('> Zitat')).toEqual({ teile: [absatz(...text('> Zitat'))], befunde: ['Zeile 1: Zitat ohne «Hinweis:» oder «Achtung:»'] });
    });

    it('verschachtelte Listen und Listen mit «*»', () => {
      expect(lesen('- eins\n  - zwei').befunde).toEqual(['Zeile 1: verschachtelte Liste']);
      expect(lesen('* eins').befunde).toEqual(['Zeile 1: Liste mit «*» oder «+»']);
    });
  });

  describe('Grenzen', () => {
    it(`nimmt höchstens ${ANLEITUNG_MAX_BYTES} Bytes an, gemessen in UTF-8`, () => {
      expect(() => lesen('a'.repeat(ANLEITUNG_MAX_BYTES))).not.toThrow();
      expect(() => lesen('a'.repeat(ANLEITUNG_MAX_BYTES + 1))).toThrow(AnleitungZuGross);
      expect(() => lesen('ä'.repeat(ANLEITUNG_MAX_BYTES / 2 + 1))).toThrow(AnleitungZuGross);
    });

    it('lange Zeilen mit vielen offenen Zeichen ohne Zeitüberschreitung', () => {
      const laenge = ANLEITUNG_MAX_BYTES - 10;
      for (const muster of ['[', '[a]', '*', '**', '`', '![', '<a', '{', '[a](', '[a](#']) {
        const zeile = muster.repeat(Math.floor(laenge / muster.length)) + ']';
        const beginn = performance.now();
        lesen(zeile);
        expect(performance.now() - beginn, muster).toBeLessThan(500);
      }
    });
  });
});
