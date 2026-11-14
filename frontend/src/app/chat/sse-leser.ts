import { ChatEreignis, ChatFehler, Quelle } from './chat-port';

// Leser für `text/event-stream` (Spec 02 design.md «Chat-Agent», Aufgabe 7.4) als reine Funktionen: Die Stücke kommen,
// wie das Netz sie liefert, Ereignisse können über Stückgrenzen reichen und mehrere in einem Stück stehen. Zeilenenden
// \n, \r\n und \r; Kommentarzeilen (`:`) und Felder ausser `event` und `data` fallen weg (WHATWG, «Server-sent events»).

export interface SseEreignis {
  name: string;
  daten: string;
}

/** Was vom letzten Stück noch nicht ausgewertet ist: angefangene Zeile und das angefangene Ereignis. */
export interface SseZustand {
  readonly puffer: string;
  readonly name: string;
  readonly daten: readonly string[];
}

export const SSE_START: SseZustand = { puffer: '', name: '', daten: [] };

export function sseLesen(
  zustand: SseZustand,
  stueck: string,
): { zustand: SseZustand; ereignisse: SseEreignis[] } {
  const puffer = zustand.puffer + stueck;
  const ereignisse: SseEreignis[] = [];
  let name = zustand.name;
  let daten = [...zustand.daten];
  let start = 0;
  for (let i = 0; i < puffer.length; i++) {
    const zeichen = puffer[i];
    if (zeichen !== '\n' && zeichen !== '\r') {
      continue;
    }
    // \r am Ende des Stücks: Ein \n im nächsten Stück gehört noch zu diesem Zeilenende
    if (zeichen === '\r' && i + 1 === puffer.length) {
      break;
    }
    const zeile = puffer.slice(start, i);
    if (zeichen === '\r' && puffer[i + 1] === '\n') {
      i++;
    }
    start = i + 1;
    if (zeile === '') {
      if (daten.length > 0) {
        ereignisse.push({ name: name || 'message', daten: daten.join('\n') });
      }
      name = '';
      daten = [];
      continue;
    }
    if (zeile.startsWith(':')) {
      continue;
    }
    const trenner = zeile.indexOf(':');
    const feld = trenner < 0 ? zeile : zeile.slice(0, trenner);
    let wert = trenner < 0 ? '' : zeile.slice(trenner + 1);
    if (wert.startsWith(' ')) {
      wert = wert.slice(1);
    }
    if (feld === 'event') {
      name = wert;
    } else if (feld === 'data') {
      daten.push(wert);
    }
  }
  return { zustand: { puffer: puffer.slice(start), name, daten }, ereignisse };
}

const objekt = (wert: unknown): wert is Record<string, unknown> =>
  typeof wert === 'object' && wert !== null && !Array.isArray(wert);

function quelle(wert: unknown): Quelle | null {
  if (!objekt(wert)) {
    return null;
  }
  if (typeof wert['titel'] === 'string' && typeof wert['link'] === 'string') {
    return { titel: wert['titel'], link: wert['link'] };
  }
  // Datenstand der zählenden Werkzeuge in der Form von 7.3 (`ChatJson`): `{werkzeug, datenstand: <ISO>, belege}`
  const stand = wert['datenstand'];
  if (typeof stand === 'string' && typeof wert['belege'] === 'number') {
    return { datenstand: { zeitpunkt: stand, anzahl: wert['belege'] } };
  }
  // andere Formen (z. B. Wissensseiten aus Spec 04) zeigt diese Fassung noch nicht
  return null;
}

/** Nur lesbare Quellen; andere Formen fallen weg. */
export function quellenAus(wert: unknown): Quelle[] {
  return Array.isArray(wert) ? wert.map(quelle).filter((q): q is Quelle => q !== null) : [];
}

const BEKANNT = new Set(['werkzeug', 'text', 'quellen', 'fertig', 'fehler']);

/**
 * Ereignis des Chats aus einem SSE-Ereignis; `null` für unbekannte Ereignisse. Ungültiges JSON oder fehlende Felder
 * sind ein Fehler der Antwort (`ChatFehler` `ANTWORT`).
 */
export function chatEreignis(sse: SseEreignis): ChatEreignis | null {
  if (!BEKANNT.has(sse.name)) {
    return null;
  }
  let wert: unknown;
  try {
    wert = JSON.parse(sse.daten);
  } catch {
    throw new ChatFehler('ANTWORT');
  }
  if (sse.name === 'quellen') {
    if (!Array.isArray(wert)) {
      throw new ChatFehler('ANTWORT');
    }
    return { typ: 'quellen', quellen: quellenAus(wert) };
  }
  if (!objekt(wert)) {
    throw new ChatFehler('ANTWORT');
  }
  const text = (feld: string) => (typeof wert[feld] === 'string' ? (wert[feld] as string) : null);
  const ereignis = ((): ChatEreignis | null => {
    switch (sse.name) {
      case 'werkzeug':
        return text('name') === null ? null : { typ: 'werkzeug', name: text('name')! };
      case 'text':
        return text('delta') === null ? null : { typ: 'text', delta: text('delta')! };
      case 'fertig':
        return text('gespraechId') === null
          ? null
          : { typ: 'fertig', gespraechId: text('gespraechId')! };
      default:
        return text('meldung') === null
          ? null
          : {
              typ: 'fehler',
              meldung: text('meldung')!,
              gespraechId: text('gespraechId') ?? undefined,
            };
    }
  })();
  if (ereignis === null) {
    throw new ChatFehler('ANTWORT');
  }
  return ereignis;
}
