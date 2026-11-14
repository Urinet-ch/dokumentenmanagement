// Attrappe der Chat-Schnittstelle für die Browser-Szenarien von Spec 02, Aufgabe 7.4 (chat-pwa.feature). Das Backend
// (7.2, 7.3) entsteht parallel; die Attrappe folgt dem Vertrag aus Spec 02 design.md «Vertrag Welle 7»: POST /api/v1/chat
// mit {frage, gespraechId?} antwortet als text/event-stream mit werkzeug, text, quellen und fertig oder fehler, vor dem
// Strom mit 401 (ohne Token), 404 oder 429 mit Retry-After; GET /api/v1/chat/gespraeche und /gespraeche/{id}.
// Den Strom liefert ein kleiner HTTP-Server auf 127.0.0.1 Stück für Stück mit Pausen und mitten in Ereignissen
// zerteilt (route.fulfill kann nicht streamen); Playwright leitet die Anfrage dorthin um, für die PWA bleibt es dieselbe
// Adresse. Haltepunkte im Strom lassen die Szenarien prüfen, was während der Antwort zu sehen ist. Die Dokumente der
// Quellen kommen aus der Such-Attrappe (synthetisch); alle Daten sind synthetisch.
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { basisUrl } from './keycloak-attrappe.mjs';
import { DOKUMENTE, sucheAttrappe } from './suche-attrappe.mjs';

const origin = new URL(basisUrl).origin;
const PFAD = '/api/v1/chat';
const HALT = Symbol('halt');

export const SEEDORF = DOKUMENTE[0];
const DRUCKEREI = DOKUMENTE[1];
const quelle = (d) => ({ titel: d.titel, link: `/dokument/${d.id}` });
export const DATENSTAND = '2026-11-14T08:00:00Z';

/** SSE-Text eines Ereignisses; jedes dritte mit \r\n, damit beide Zeilenenden vorkommen. */
let zaehler = 0;
function sse(name, daten) {
  const ende = zaehler++ % 3 === 2 ? '\r\n' : '\n';
  return `event: ${name}${ende}data: ${JSON.stringify(daten)}${ende}${ende}`;
}

/** Zerteilt Text in Stücke ungleicher Länge, absichtlich quer durch Zeilen und JSON. */
function zerteilt(text, laengen = [5, 17, 3, 29, 11]) {
  const stuecke = [];
  for (let i = 0, k = 0; i < text.length; k++) {
    const n = laengen[k % laengen.length];
    stuecke.push(text.slice(i, i + n));
    i += n;
  }
  return stuecke;
}

const LANG_WORT = 'Rechnungsnummernvergleichsliste'.repeat(4);

/** Abläufe je Frage: Stücke (Text), HALT (wartet auf «das Archiv antwortet weiter») oder {status, …} vor dem Strom. */
function ablauf(frage, gespraechId) {
  const id = gespraechId ?? randomUUID();
  const fertig = () => sse('fertig', { gespraechId: id, tokens: 321 });
  switch (frage) {
    case 'Welche Rechnungen sind offen?':
      return {
        id,
        teile: [
          ...zerteilt(': verbunden\n\n' + sse('werkzeug', { name: 'offene_posten' })),
          HALT,
          ...zerteilt(sse('text', { delta: 'Offen sind zwei Rechnungen' })),
          HALT,
          ...zerteilt(
            sse('text', { delta: ":\n- 2026-003 Testverein Seedorf, CHF 1'234.50\n" }) +
              sse('unbekannt', { egal: true }) +
              sse('text', { delta: '- 2026-007 Testverein Bauen, CHF 980.00' }) +
              sse('quellen', [
                quelle(SEEDORF),
                { datenstand: { zeitpunkt: DATENSTAND, anzahl: 2 } },
              ]) +
              fertig(),
          ),
        ],
      };
    case 'Wo ist die Rechnung aus Seedorf?':
      return {
        id,
        teile: zerteilt(
          sse('werkzeug', { name: 'dokument_zeigen' }) +
            sse('text', { delta: 'Hier ist sie.' }) +
            sse('quellen', [
              quelle(SEEDORF),
              { titel: 'Fremde Seite', link: 'https://example.invalid/dokument/1' },
              { titel: 'Protokoll', link: '/verwaltung/protokoll' },
            ]) +
            fertig(),
        ),
      };
    case 'Zeig mir alles':
      return {
        id,
        teile: zerteilt(
          sse('text', {
            delta:
              `Sehr lange Antwort ${LANG_WORT} mit CHF 1'234'567'890.55 und\nmehreren\nZeilen. `.repeat(
                6,
              ),
          }) +
            sse('quellen', [
              ...Array.from({ length: 12 }, (_, i) => ({
                titel: `${i % 2 ? SEEDORF.titel : DRUCKEREI.titel} ${LANG_WORT}`,
                link: `/dokument/${(i % 2 ? SEEDORF : DRUCKEREI).id}`,
              })),
              { datenstand: { zeitpunkt: DATENSTAND, anzahl: 12345 } },
            ]) +
            fertig(),
          [41, 7, 113],
        ),
      };
    case 'Gibt es Fristen?':
      return {
        id,
        teile: zerteilt(
          sse('werkzeug', { name: 'fristen' }) +
            sse('fehler', {
              meldung: 'Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.',
              gespraechId: id,
            }),
        ),
      };
    case 'Lange Frage':
      return {
        id,
        teile: [
          ...zerteilt(sse('text', { delta: 'Ich suche noch' })),
          HALT,
          sse('text', { delta: ' weiter' }),
          fertig(),
        ],
      };
    case 'Frage 101':
      return { status: 429, retryAfter: '1200' };
    case 'Noch eine Frage':
      return { status: 429, retryAfter: '5' };
    default:
      return {
        id,
        teile: zerteilt(
          sse('text', { delta: `Antwort auf «${frage}».` }) + sse('quellen', []) + fertig(),
        ),
      };
  }
}

function deferred() {
  let freigeben;
  const promise = new Promise((r) => (freigeben = r));
  return { promise, freigeben };
}

/** Richtet die Attrappe ein (einmal je Szenario); ihr Zustand steht danach in welt.chat. */
export async function chatAttrappe(welt) {
  if (welt.chat) {
    return welt.chat;
  }
  await sucheAttrappe(welt);
  const chat = {
    gespraeche: [
      { id: randomUUID(), titel: 'Fristen im Dezember', geaendert: '2026-11-02T09:15:00Z' },
      { id: randomUUID(), titel: 'Ausgaben Druckerei 2026', geaendert: '2026-10-20T16:40:00Z' },
    ],
    verlauf: new Map(),
    fragen: [],
    stroeme: new Map(),
    halte: [],
    freigaben: 0,
    abgebrochen: 0,
    server: null,
    weiter() {
      (this.halte[this.freigaben] ??= deferred()).freigeben();
      this.freigaben++;
    },
  };
  welt.chat = chat;
  const [fristen] = chat.gespraeche;
  chat.verlauf.set(fristen.id, [
    {
      rolle: 'BENUTZER',
      inhalt: 'Welche Fristen laufen im Dezember ab?',
      quellen: [],
      zeit: '2026-11-02T09:14:00Z',
    },
    {
      rolle: 'ASSISTENT',
      inhalt: 'Im Dezember läuft eine Frist ab:\n- Offerte Testdruckerei AG, gültig bis 31.12.2026',
      quellen: [
        quelle(DRUCKEREI),
        { datenstand: { zeitpunkt: '2026-11-02T09:14:00Z', anzahl: 1 } },
      ],
      status: 'OK',
      zeit: '2026-11-02T09:15:00Z',
    },
  ]);

  let halt = 0;
  chat.server = http.createServer(async (anfrage, antwort) => {
    const strom = chat.stroeme.get(anfrage.url.slice(1));
    if (!strom) {
      antwort.writeHead(404).end();
      return;
    }
    let offen = true;
    antwort.on('close', () => {
      if (offen) {
        chat.abgebrochen++;
      }
      offen = false;
    });
    antwort.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
    });
    antwort.flushHeaders();
    for (const teil of strom.teile) {
      if (!offen) {
        return;
      }
      if (teil === HALT) {
        await (chat.halte[halt++] ??= deferred()).promise;
        continue;
      }
      antwort.write(teil);
      await new Promise((r) => setTimeout(r, 15));
    }
    offen = false;
    antwort.end();
  });
  await new Promise((fertig) => chat.server.listen(0, '127.0.0.1', fertig));
  const stromBasis = `http://127.0.0.1:${chat.server.address().port}/`;

  const json = (route, status, body, headers = {}) =>
    route.fulfill({
      status,
      headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers },
      body: JSON.stringify(body),
    });

  await welt.seite.route(
    (url) =>
      url.origin === origin && (url.pathname === PFAD || url.pathname.startsWith(`${PFAD}/`)),
    async (route) => {
      const anfrage = route.request();
      const pfad = new URL(anfrage.url()).pathname;
      if ((await anfrage.headerValue('authorization')) !== `Bearer ${welt.keycloak?.accessToken}`) {
        return json(route, 401, {
          status: 401,
          title: 'Nicht angemeldet',
          fehlercode: 'NICHT_ANGEMELDET',
        });
      }
      if (pfad === `${PFAD}/gespraeche` && anfrage.method() === 'GET') {
        return json(
          route,
          200,
          [...chat.gespraeche].sort((a, b) => b.geaendert.localeCompare(a.geaendert)).slice(0, 20),
        );
      }
      const einzel = /^\/api\/v1\/chat\/gespraeche\/([^/]+)$/.exec(pfad);
      if (einzel && anfrage.method() === 'GET') {
        const nachrichten = chat.verlauf.get(decodeURIComponent(einzel[1]));
        return nachrichten
          ? json(route, 200, nachrichten)
          : json(route, 404, {
              status: 404,
              title: 'Nicht gefunden',
              fehlercode: 'NICHT_GEFUNDEN',
            });
      }
      if (pfad !== PFAD || anfrage.method() !== 'POST') {
        return json(route, 404, {
          status: 404,
          title: 'Nicht gefunden',
          fehlercode: 'NICHT_GEFUNDEN',
        });
      }
      const body = anfrage.postDataJSON();
      chat.fragen.push({ ...body, accept: await anfrage.headerValue('accept') });
      const frage = String(body.frage ?? '').trim();
      if (frage.length < 1 || frage.length > 2000) {
        return json(route, 400, {
          status: 400,
          title: 'Anfrage ungültig',
          fehlercode: 'ANFRAGE_UNGUELTIG',
        });
      }
      if (body.gespraechId && !chat.gespraeche.some((g) => g.id === body.gespraechId)) {
        return json(route, 404, {
          status: 404,
          title: 'Nicht gefunden',
          fehlercode: 'NICHT_GEFUNDEN',
        });
      }
      const plan = ablauf(frage, body.gespraechId ?? null);
      if (plan.status === 429) {
        return json(
          route,
          429,
          { status: 429, title: 'Zu viele Anfragen', fehlercode: 'ZU_VIELE_ANFRAGEN' },
          { 'retry-after': plan.retryAfter },
        );
      }
      // wie das Archiv: Das Gespräch steht mit der ersten Frage als Titel in der Liste
      const bisher = chat.gespraeche.find((g) => g.id === plan.id);
      if (bisher) {
        bisher.geaendert = new Date().toISOString();
      } else {
        chat.gespraeche.push({
          id: plan.id,
          titel: frage.slice(0, 80),
          geaendert: new Date().toISOString(),
        });
      }
      const schluessel = randomUUID();
      chat.stroeme.set(schluessel, plan);
      return route.continue({ url: stromBasis + schluessel });
    },
  );
  return chat;
}

export async function chatAttrappeBeenden(welt) {
  if (welt.chat?.server) {
    for (const h of welt.chat.halte) {
      h?.freigeben();
    }
    welt.chat.server.closeAllConnections();
    await new Promise((fertig) => welt.chat.server.close(fertig));
  }
}
