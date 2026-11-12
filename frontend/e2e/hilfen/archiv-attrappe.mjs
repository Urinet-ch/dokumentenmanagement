// Attrappe der Archiv-API für den Eingangskorb im Browser-Test (Aufgabe 3.6): Playwright beantwortet die Anfragen der
// PWA an /api/v1/eingangskorb, /dokumentarten, /kontakte und die Aktionen an /dokumente/{id}/… selbst. Das Archiv, das
// die PWA ausliefert, läuft ohne Datenbank und Keycloak; es lehnte das unsignierte Token der Keycloak-Attrappe mit 401
// ab, und die PWA beendete die Sitzung. Die Regeln selbst (Rechte, Sichtbarkeit, Pflichtfelder, Status) belegen die
// Java-Szenarien gegen das echte Backend (features/dokument/eingangskorb.feature, bestaetigen.feature).
// Alle Daten sind synthetisch.
import { basisUrl } from './keycloak-attrappe.mjs';

// 1×1 Pixel, PNG
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  'base64',
);

const ARTEN = [
  {
    code: 'LIEFERANTENRECHNUNG',
    name: 'Lieferantenrechnung',
    gruppen: ['finanz'],
    pflichtfelder: ['datum', 'betrag'],
    zusatzSchema: {},
    vertraulichkeit: 'NORMAL',
    gesetzlichePflicht: true,
    aktiv: true,
  },
  {
    code: 'QUITTUNG',
    name: 'Quittung',
    gruppen: ['finanz'],
    pflichtfelder: ['datum'],
    zusatzSchema: {},
    vertraulichkeit: 'NORMAL',
    gesetzlichePflicht: true,
    aktiv: true,
  },
];

const json = (route, status, body) =>
  route.fulfill({
    status,
    contentType: 'application/json',
    body: body === undefined ? '' : JSON.stringify(body),
  });

/** Richtet die Attrappe für die Seite ein (einmal je Szenario); der Eingangskorb ist anfangs leer. */
export async function archivAttrappe(welt) {
  if (welt.archiv) {
    return welt.archiv;
  }
  const archiv = {
    eintraege: [],
    arten: ARTEN,
    kontakte: [],
    aktionen: [],
    ablehnung: null,
    abfragen: [],
  };
  welt.archiv = archiv;
  const origin = new URL(basisUrl).origin;
  const zustaendig = (url) =>
    url.origin === origin &&
    (url.pathname === '/api/v1/eingangskorb' ||
      url.pathname === '/api/v1/dokumentarten' ||
      url.pathname === '/api/v1/kontakte' ||
      /^\/api\/v1\/dokumente\/[^/]+\/(vorschau|bestaetigen|verwerfen|wiederholen)$/.test(
        url.pathname,
      ));

  await welt.seite.route(zustaendig, async (route) => {
    const anfrage = route.request();
    const url = new URL(anfrage.url());
    if (!(await anfrage.headerValue('authorization'))?.startsWith('Bearer ')) {
      return json(route, 401, { fehlercode: 'NICHT_ANGEMELDET' });
    }
    if (url.pathname === '/api/v1/eingangskorb') {
      archiv.abfragen.push(url.search);
      const groesse = Number(url.searchParams.get('groesse') ?? 50);
      const seite = Number(url.searchParams.get('seite') ?? 0);
      const eintraege = archiv.eintraege.slice(seite * groesse, (seite + 1) * groesse);
      return json(route, 200, { eintraege, gesamt: archiv.eintraege.length, seite, groesse });
    }
    if (url.pathname === '/api/v1/dokumentarten') {
      return json(route, 200, archiv.arten);
    }
    if (url.pathname === '/api/v1/kontakte') {
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      return json(
        route,
        200,
        archiv.kontakte.filter((k) => k.name.toLowerCase().includes(q)).slice(0, 20),
      );
    }
    const [, , , , id, aktion] = url.pathname.split('/');
    const dokument = archiv.eintraege.find((d) => d.id === id);
    if (!dokument) {
      return json(route, 404, { fehlercode: 'NICHT_GEFUNDEN' });
    }
    if (aktion === 'vorschau') {
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    }
    archiv.aktionen.push({
      aktion,
      id,
      titel: dokument.titel,
      ifMatch: await anfrage.headerValue('if-match'),
      body: anfrage.postDataJSON(),
      abgelehnt: Boolean(archiv.ablehnung),
    });
    if (archiv.ablehnung) {
      const { status, body } = archiv.ablehnung;
      archiv.ablehnung = null;
      return route.fulfill({
        status,
        contentType: 'application/problem+json',
        body: JSON.stringify(body),
      });
    }
    archiv.eintraege = archiv.eintraege.filter((d) => d.id !== id);
    return json(route, aktion === 'verwerfen' ? 204 : 202);
  });
  return archiv;
}

/** Dokument der API (Schema `Dokument`) aus einer Zeile des Szenarios. */
export function dokumentAus(zeile, index) {
  const status = zeile.status || 'EINGANGSKORB';
  const vorschlag = {};
  if (zeile.art) vorschlag.art = zeile.art;
  if (zeile.datum) vorschlag.datum = zeile.datum;
  if (zeile.betrag) vorschlag.betrag = Number(zeile.betrag);
  if (zeile.betrag) vorschlag.waehrung = 'CHF';
  if (zeile.partner) vorschlag.partnerName = zeile.partner;
  if (zeile.belegnummer) vorschlag.belegnummer = zeile.belegnummer;
  return {
    id: `d-${index + 1}`,
    titel: zeile.titel,
    eingang: zeile.eingang ? `${zeile.eingang}T08:00:00Z` : '2026-11-08T08:00:00Z',
    quelle: 'UPLOAD',
    tags: [],
    vertraulichkeit: 'PERSONAL',
    status,
    fristAbgelaufen: false,
    version: 1,
    warnungen: zeile.warnung ? [{ code: zeile.warnung, vorhandenId: 'vorhanden-1' }] : [],
    fehler: zeile.fehler || null,
    pdfaFehlt: false,
    seiten: 1,
    vorschlag: status === 'EINGANGSKORB' && Object.keys(vorschlag).length > 0 ? vorschlag : null,
    unsicher: zeile.unsicher ? zeile.unsicher.split(', ') : [],
  };
}
