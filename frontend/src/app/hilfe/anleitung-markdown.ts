import { Textteil } from '../wissen/wissen-text';
import { HILFE_KATALOG } from './hilfe-katalog';

// Markdown-Teilmenge der Anleitung (Spec 07, Req 7.3, 7.4; design.md «Markdown-Teilmenge und Darstellung»): zeilenweise,
// ohne reguläre Ausdrücke mit Rückverfolgung. Was nicht zur Teilmenge gehört, erscheint wörtlich als Text und steht in
// «befunde» (die Inhaltsprüfung verlangt eine leere Liste). Dargestellt wird der Baum nur über Interpolation.

export type Anleitungsart =
  | 'text'
  | 'platzhalter'
  | 'p'
  | 'h2'
  | 'h3'
  | 'h4'
  | 'ul'
  | 'ol'
  | 'li'
  | 'strong'
  | 'em'
  | 'code'
  | 'hinweis'
  | 'link'
  | 'abschnitt';

/** Ein Teil des Baums: wie `Textteil` der Wissensseite, dazu Anker (Sprungmarke, id) und Variante des Hinweises. */
export interface Anleitungsteil extends Textteil {
  art: Anleitungsart;
  /** «abschnitt»: Ziel im Hilfe-Katalog; Überschrift: ihre id. */
  anker?: string;
  /** «hinweis»: `> **Hinweis:**` ergibt «info», `> **Achtung:**` ergibt «warnung». */
  variante?: 'info' | 'warnung';
  /** «platzhalter»: `{name}` (Name aus dem Branding) bzw. `{rolle}` (Bezeichnung der Rolle). */
  platzhalter?: 'name' | 'rolle';
  kinder?: Anleitungsteil[];
}

export interface Anleitung {
  teile: Anleitungsteil[];
  /** Konstrukte ausserhalb der Teilmenge, je mit Zeile; nur IDs und Art, kein Inhalt. */
  befunde: string[];
}

/** Grösste Datei der Anleitung; darüber gilt der Fehlerfall von Req 7.6. */
export const ANLEITUNG_MAX_BYTES = 32 * 1024;

export class AnleitungZuGross extends Error {
  constructor() {
    super(`Die Datei der Anleitung ist grösser als ${ANLEITUNG_MAX_BYTES} Bytes.`);
  }
}

export interface AnleitungOptionen {
  /** Ist der Pfad eine Route der App? Nur dann wird `[Text](/pfad)` ein Link (Req 7.4). */
  istAppPfad: (pfad: string) => boolean;
  /** Anker der Datei; Überschriften erhalten dann die id «<praefix>-<titel>». */
  praefix?: string;
}

const PLATZHALTER = ['name', 'rolle'] as const;
const UMLAUTE: Record<string, string> = { ä: 'ae', ö: 'oe', ü: 'ue', à: 'a', é: 'e', è: 'e' };

function istZiffer(zeichen: string): boolean {
  return zeichen >= '0' && zeichen <= '9';
}

function istBuchstabe(zeichen: string): boolean {
  return (zeichen >= 'a' && zeichen <= 'z') || (zeichen >= 'A' && zeichen <= 'Z');
}

/** «## Titel» → 2; 0, wenn die Zeile keine Überschrift ist. */
function ueberschriftStufe(zeile: string): number {
  let stufe = 0;
  while (zeile.charAt(stufe) === '#') stufe++;
  return stufe > 0 && zeile.charAt(stufe) === ' ' ? stufe : 0;
}

/** Länge der Marke einer Liste («- » bzw. «12. ») am Zeilenanfang, sonst 0. */
function marke(zeile: string): { art: 'ul' | 'ol'; laenge: number } | null {
  if (zeile.startsWith('- ')) return { art: 'ul', laenge: 2 };
  let i = 0;
  while (istZiffer(zeile.charAt(i))) i++;
  return i > 0 && i <= 9 && zeile.startsWith('. ', i) ? { art: 'ol', laenge: i + 2 } : null;
}

function istZaun(zeile: string): boolean {
  return zeile.trimStart().startsWith('```');
}

/** Beginnt mit der Zeile ein neuer Block (Überschrift, Hinweis, Liste, Codeblock)? */
function beginntBlock(zeile: string): boolean {
  return ueberschriftStufe(zeile) > 0 || zeile.startsWith('>') || marke(zeile) !== null || istZaun(zeile);
}

/** Nur aus einem Zeichen wie «---», «***», «===» (Trennlinie oder unterstrichene Überschrift). */
function istLinie(zeile: string): boolean {
  const text = zeile.trim();
  return text.length >= 3 && ['-', '*', '_', '='].includes(text.charAt(0)) && [...text].every((z) => z === text.charAt(0));
}

function kennung(text: string): string {
  let ergebnis = '';
  for (const zeichen of text.toLowerCase()) {
    const ersatz = UMLAUTE[zeichen] ?? zeichen;
    if ([...ersatz].every((z) => istBuchstabe(z) || istZiffer(z))) {
      ergebnis += ersatz;
    } else if (ergebnis !== '' && !ergebnis.endsWith('-')) {
      ergebnis += '-';
    }
  }
  return ergebnis.endsWith('-') ? ergebnis.slice(0, -1) : ergebnis;
}

function roh(text: string): Anleitungsteil {
  return { art: 'p', kinder: [{ art: 'text', text }] };
}

/**
 * Inline-Teile: `**fett**`, `*kursiv*`, `` `Code` ``, `[Text](/pfad)`, `[Text](#anker)`, `{name}`, `{rolle}`.
 * Jede Suche nach einem Schlusszeichen merkt sich ihr Ergebnis; weil die Startpunkte nur wachsen, bleibt die Arbeit
 * linear in der Länge der Zeile (kein ReDoS, auch bei vielen offenen Klammern).
 */
function inline(text: string, optionen: AnleitungOptionen, melden: (was: string) => void): Anleitungsteil[] {
  const teile: Anleitungsteil[] = [];
  const gefunden = new Map<string, number>();
  let puffer = '';
  let i = 0;

  const suche = (zeichen: string, ab: number): number => {
    const bekannt = gefunden.get(zeichen);
    if (bekannt !== undefined && (bekannt === -1 || bekannt >= ab)) return bekannt;
    const position = text.indexOf(zeichen, ab);
    gefunden.set(zeichen, position);
    return position;
  };
  const ausgeben = (teil: Anleitungsteil) => {
    if (puffer) teile.push({ art: 'text', text: puffer });
    puffer = '';
    teile.push(teil);
  };
  /** `[Text](Ziel)` ab der Klammer bei `start`: Text, Ziel und Ende, sonst null. */
  const klammern = (start: number): { text: string; ziel: string; ende: number } | null => {
    const schluss = suche(']', start + 1);
    if (schluss <= start + 1 || text.charAt(schluss + 1) !== '(') return null;
    const ende = suche(')', schluss + 2);
    if (ende < 0) return null;
    return { text: text.slice(start + 1, schluss), ziel: text.slice(schluss + 2, ende).trim(), ende };
  };

  while (i < text.length) {
    const zeichen = text.charAt(i);
    if (zeichen === '`') {
      const ende = suche('`', i + 1);
      if (ende > i + 1) {
        ausgeben({ art: 'code', text: text.slice(i + 1, ende) });
        i = ende + 1;
        continue;
      }
    } else if (zeichen === '*' && text.charAt(i + 1) === '*') {
      const ende = suche('**', i + 2);
      if (ende > i + 2) {
        ausgeben({ art: 'strong', kinder: inline(text.slice(i + 2, ende), optionen, melden) });
        i = ende + 2;
        continue;
      }
      puffer += '**';
      i += 2;
      continue;
    } else if (zeichen === '*') {
      const ende = suche('*', i + 1);
      if (ende > i + 1 && text.charAt(i + 1) !== ' ') {
        ausgeben({ art: 'em', kinder: inline(text.slice(i + 1, ende), optionen, melden) });
        i = ende + 1;
        continue;
      }
    } else if (zeichen === '!' && text.charAt(i + 1) === '[') {
      const bild = klammern(i + 1);
      if (bild) {
        melden('Bild');
        puffer += text.slice(i, bild.ende + 1);
        i = bild.ende + 1;
        continue;
      }
    } else if (zeichen === '[') {
      if (text.charAt(i + 1) === '^') {
        melden('Fussnote');
      } else {
        const link = klammern(i);
        if (link) {
          const ziel = link.ziel;
          if (ziel.startsWith('#') && HILFE_KATALOG.some((eintrag) => eintrag.anker === ziel.slice(1))) {
            ausgeben({ art: 'abschnitt', text: link.text, anker: ziel.slice(1) });
          } else if (ziel.startsWith('/') && optionen.istAppPfad(ziel)) {
            ausgeben({ art: 'link', text: link.text, pfad: ziel });
          } else {
            melden(ziel.startsWith('#') ? 'unbekannter Anker' : ziel.startsWith('/') ? 'unbekannter Pfad' : 'fremdes Ziel');
            puffer += text.slice(i, link.ende + 1);
          }
          i = link.ende + 1;
          continue;
        }
      }
    } else if (zeichen === '<') {
      const naechstes = text.charAt(i + 1);
      if (istBuchstabe(naechstes) || ['/', '!', '?'].includes(naechstes)) melden('HTML');
    } else if (zeichen === '{') {
      const platzhalter = PLATZHALTER.find((name) => text.startsWith(`{${name}}`, i));
      if (platzhalter) {
        ausgeben({ art: 'platzhalter', platzhalter });
        i += platzhalter.length + 2;
        continue;
      }
    }
    puffer += zeichen;
    i++;
  }
  if (puffer) teile.push({ art: 'text', text: puffer });
  return teile;
}

/** Setzt eine Datei der Anleitung in den Baum um; über {@link ANLEITUNG_MAX_BYTES} wirft sie {@link AnleitungZuGross}. */
export function anleitungMarkdown(quelle: string, optionen: AnleitungOptionen): Anleitung {
  if (new TextEncoder().encode(quelle).length > ANLEITUNG_MAX_BYTES) throw new AnleitungZuGross();
  const zeilen = quelle.split('\n').map((zeile) => (zeile.endsWith('\r') ? zeile.slice(0, -1) : zeile));
  const teile: Anleitungsteil[] = [];
  const befunde: string[] = [];
  const ids = new Map<string, number>();
  let start = 0;
  const melden = (was: string) => befunde.push(`Zeile ${start + 1}: ${was}`);
  const text = (inhalt: string) => inline(inhalt, optionen, melden);
  const id = (titel: string): string | undefined => {
    if (!optionen.praefix) return undefined;
    const basis = [optionen.praefix, kennung(titel)].filter(Boolean).join('-');
    const anzahl = (ids.get(basis) ?? 0) + 1;
    ids.set(basis, anzahl);
    return anzahl === 1 ? basis : `${basis}-${anzahl}`;
  };

  let i = 0;
  while (i < zeilen.length) {
    const zeile = zeilen[i];
    start = i;
    if (zeile.trim() === '') {
      i++;
    } else if (istZaun(zeile)) {
      melden('Codeblock');
      const block = [zeile];
      i++;
      while (i < zeilen.length) {
        block.push(zeilen[i]);
        i++;
        if (istZaun(zeilen[i - 1])) break;
      }
      teile.push(roh(block.join('\n')));
    } else if (ueberschriftStufe(zeile) > 0) {
      const stufe = ueberschriftStufe(zeile);
      const titel = zeile.slice(stufe + 1).trim();
      if (stufe >= 2 && stufe <= 4) {
        teile.push({ art: `h${stufe}` as Anleitungsart, anker: id(titel), kinder: text(titel) });
      } else {
        melden(`Überschrift der Stufe ${stufe}`);
        teile.push(roh(zeile.trim()));
      }
      i++;
    } else if (zeile.startsWith('>')) {
      const block: string[] = [];
      while (i < zeilen.length && zeilen[i].startsWith('>')) block.push(zeilen[i++]);
      const inhalt = block.map((z) => z.slice(1).trim()).filter(Boolean).join(' ');
      const variante = inhalt.startsWith('**Hinweis:**') ? 'info' : inhalt.startsWith('**Achtung:**') ? 'warnung' : null;
      if (variante) {
        teile.push({ art: 'hinweis', variante, kinder: text(inhalt) });
      } else {
        melden('Zitat ohne «Hinweis:» oder «Achtung:»');
        teile.push(roh(block.join('\n')));
      }
    } else if (marke(zeile)) {
      const art = marke(zeile)!.art;
      const punkte: Anleitungsteil[] = [];
      while (i < zeilen.length && marke(zeilen[i])?.art === art) {
        start = i;
        const punkt = [zeilen[i].slice(marke(zeilen[i])!.laenge)];
        i++;
        while (i < zeilen.length && zeilen[i].trim() !== '' && !beginntBlock(zeilen[i])) {
          if (marke(zeilen[i].trimStart()) || zeilen[i].trimStart().startsWith('* ')) melden('verschachtelte Liste');
          punkt.push(zeilen[i++].trim());
        }
        punkte.push({ art: 'li', kinder: text(punkt.join(' ').trim()) });
      }
      teile.push({ art, kinder: punkte });
    } else {
      const absatz: string[] = [];
      while (i < zeilen.length && zeilen[i].trim() !== '' && (absatz.length === 0 || !beginntBlock(zeilen[i]))) {
        const z = zeilen[i];
        if (z.trimStart().startsWith('|')) melden('Tabelle');
        else if (istLinie(z)) melden('Trennlinie');
        else if (z.startsWith('* ') || z.startsWith('+ ')) melden('Liste mit «*» oder «+»');
        else if (z.startsWith('    ') || z.startsWith('\t')) melden('eingerückter Code');
        absatz.push(z.trim());
        i++;
      }
      teile.push({ art: 'p', kinder: text(absatz.join(' ')) });
    }
  }
  return { teile, befunde };
}
