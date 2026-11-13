// Attrappe der Such- und Dokument-API für die Browser-Szenarien von Aufgabe 4.6: Playwright beantwortet die Anfragen der
// PWA an /api/v1/dokumente, /dokumentarten, /kontakte, die vorsignierte Download-Adresse, das Vorschaubild und das
// Ändern (PATCH mit If-Match, Pflicht-Grund bei ARCHIVIERT, Fassung in `versionen`; Aufgabe 4.6, Req 10.1) selbst. Die Inhalte sind
// synthetisch (keine echten Belege). Den Vertrag der echten Suche belegen die Java-Szenarien (suche/volltextsuche.feature,
// dokument/download.feature); hier geht es um die Oberfläche. Ohne das Token der Anmeldung antwortet die Attrappe mit 401.
import { basisUrl } from './keycloak-attrappe.mjs';

const origin = new URL(basisUrl).origin;
export const S3_PFAD = '/s3-attrappe/';

const ARTEN = [
  { code: 'AUSGANGSRECHNUNG', name: 'Ausgangsrechnung' },
  { code: 'LIEFERANTENRECHNUNG', name: 'Lieferantenrechnung' },
].map((a) => ({ ...a, gruppen: ['finanz'], pflichtfelder: [], zusatzSchema: {}, vertraulichkeit: 'NORMAL', gesetzlichePflicht: true, aktiv: true }));

export const DOKUMENTE = [
  {
    id: '0b6f1d3e-0000-4000-8000-000000000001',
    titel: 'Rechnung Vereinsfest Seedorf',
    art: 'AUSGANGSRECHNUNG',
    datum: '2026-03-14',
    partnerId: '0b6f1d3e-0000-4000-8000-0000000000a1',
    partnerName: 'Testverein Seedorf',
    belegnummer: '2026-003',
    betrag: 1234.5,
    richtung: 'EINNAHME',
    tags: ['verein'],
    text: 'Wir danken für den Auftrag zum Vereinsfest in Seedorf und stellen folgende Leistungen in Rechnung.',
  },
  {
    id: '0b6f1d3e-0000-4000-8000-000000000002',
    titel: 'Rechnung Druckerei',
    art: 'LIEFERANTENRECHNUNG',
    datum: '2026-02-02',
    partnerId: '0b6f1d3e-0000-4000-8000-0000000000a2',
    partnerName: 'Testdruckerei AG',
    belegnummer: '2026-123',
    betrag: 89.9,
    richtung: 'AUSGABE',
    tags: [],
    text: 'Flyer A5, 500 Stück, für das Vereinsfest.',
  },
];

function auszug(text, q) {
  if (!q) {
    return [];
  }
  const stelle = text.toLowerCase().indexOf(q.toLowerCase());
  if (stelle < 0) {
    return [{ text: text.slice(0, 60), treffer: false }];
  }
  return [
    { text: text.slice(0, stelle), treffer: false },
    { text: text.slice(stelle, stelle + q.length), treffer: true },
    { text: text.slice(stelle + q.length), treffer: false },
  ].filter((t) => t.text);
}

function treffer(d, q) {
  return {
    id: d.id,
    art: d.art,
    titel: d.titel,
    datum: d.datum,
    eingang: `${d.datum}T08:00:00Z`,
    status: 'ARCHIVIERT',
    vertraulichkeit: 'NORMAL',
    tags: d.tags,
    geschaeftsjahr: Number(d.datum.slice(0, 4)),
    partnerId: d.partnerId,
    partnerName: d.partnerName,
    belegnummer: d.belegnummer,
    betrag: d.betrag,
    waehrung: 'CHF',
    richtung: d.richtung,
    auszug: auszug(d.text, q),
  };
}

function dokument(d, stand) {
  return {
    id: d.id,
    art: d.art,
    titel: stand?.titel ?? d.titel,
    datum: d.datum,
    eingang: `${d.datum}T08:00:00Z`,
    quelle: 'UPLOAD',
    tags: d.tags,
    vertraulichkeit: 'NORMAL',
    status: 'ARCHIVIERT',
    bestaetigt: `${d.datum}T09:00:00Z`,
    geschaeftsjahr: Number(d.datum.slice(0, 4)),
    aufbewahrenBis: `${Number(d.datum.slice(0, 4)) + 11}-01-01`,
    fristAbgelaufen: false,
    zusatz: {},
    partnerId: d.partnerId,
    partnerName: d.partnerName,
    finanz: { belegnummer: d.belegnummer, richtung: d.richtung, betrag: d.betrag, waehrung: 'CHF' },
    seiten: 1,
    version: stand?.version ?? 2,
    warnungen: [],
    pdfaFehlt: false,
    unsicher: [],
    versionen: stand?.versionen ?? [],
  };
}

// 1×1 Pixel PNG für das Vorschaubild
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=',
  'base64',
);

// Kleinstes gültiges PDF, nur damit die Vorschau eine echte PDF-Antwort erhält
const PDF = Buffer.from(
  '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
    '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n',
);

/** Richtet die Attrappe auf der Seite ein; die Anfragen stehen danach in welt.suche.anfragen. */
export async function sucheAttrappe(welt) {
  if (welt.suche) {
    return welt.suche;
  }
  const zustand = { anfragen: [], downloads: [], abgelehnt: 0, dateiAbrufe: [], aenderungen: [], bilder: [], stand: new Map() };
  welt.suche = zustand;
  const json = (route, status, body) =>
    route.fulfill({ status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' }, body: JSON.stringify(body) });

  await welt.seite.route(`${origin}/api/v1/**`, async (route) => {
    const anfrage = route.request();
    const url = new URL(anfrage.url());
    const pfad = url.pathname;
    const aendern = anfrage.method() === 'PATCH' && /^\/api\/v1\/dokumente\/[^/]+$/.test(pfad);
    if (anfrage.method() !== 'GET' && !aendern) {
      return route.fallback();
    }
    if ((await anfrage.headerValue('authorization')) !== `Bearer ${welt.keycloak?.accessToken}`) {
      zustand.abgelehnt++;
      return json(route, 401, { status: 401, title: 'Nicht angemeldet' });
    }
    if (aendern) {
      return aenderungBeantworten(route, anfrage, pfad);
    }
    if (pfad === '/api/v1/dokumentarten') {
      return json(route, 200, ARTEN);
    }
    if (pfad === '/api/v1/kontakte') {
      return json(route, 200, []);
    }
    if (pfad === '/api/v1/dokumente') {
      zustand.anfragen.push(url.searchParams);
      const p = url.searchParams;
      const q = p.get('q') ?? '';
      const liste = DOKUMENTE.filter(
        (d) =>
          (!q || `${d.titel} ${d.text} ${d.partnerName} ${d.belegnummer}`.toLowerCase().includes(q.toLowerCase())) &&
          (!p.get('belegnummer') || d.belegnummer === p.get('belegnummer')) &&
          (!p.get('art') || d.art === p.get('art')) &&
          (!p.get('partner') || d.partnerId === p.get('partner')) &&
          (!p.get('richtung') || d.richtung === p.get('richtung')),
      );
      return json(route, 200, { treffer: liste.map((d) => treffer(d, q)), gesamt: liste.length, seite: Number(p.get('seite') ?? 0), groesse: Number(p.get('groesse') ?? 20) });
    }
    const datei = /^\/api\/v1\/dokumente\/([^/]+)\/datei$/.exec(pfad);
    const einzel = /^\/api\/v1\/dokumente\/([^/]+)$/.exec(pfad);
    const bild = /^\/api\/v1\/dokumente\/([^/]+)\/vorschau$/.exec(pfad);
    const d = DOKUMENTE.find((x) => x.id === (datei ?? einzel ?? bild)?.[1]);
    if (!d) {
      return json(route, 404, { status: 404, title: 'Nicht gefunden' });
    }
    if (einzel) {
      return json(route, 200, dokument(d, zustand.stand.get(d.id)));
    }
    if (bild) {
      zustand.bilder.push({ id: d.id, seite: url.searchParams.get('seite') });
      return route.fulfill({ status: 200, headers: { 'content-type': 'image/png', 'cache-control': 'private, no-store' }, body: PNG });
    }
    const variante = url.searchParams.get('variante') ?? 'pdfa';
    zustand.downloads.push({ id: d.id, variante, accept: await anfrage.headerValue('accept') });
    const ziel = `${origin}${S3_PFAD}${d.id}/${variante}.pdf?X-Amz-Expires=300&X-Amz-Signature=attrappe`;
    return json(route, 200, { url: ziel, gueltigBis: new Date(Date.now() + 300_000).toISOString() });
  });

  // Nur, was die Szenarien brauchen: Version, Grund bei ARCHIVIERT, Titel; die volle Prüfung belegt aendern.feature.
  async function aenderungBeantworten(route, anfrage, pfad) {
    const d = DOKUMENTE.find((x) => pfad.endsWith(`/${x.id}`));
    if (!d) {
      return json(route, 404, { status: 404, title: 'Nicht gefunden' });
    }
    const body = anfrage.postDataJSON();
    const ifMatch = await anfrage.headerValue('if-match');
    zustand.aenderungen.push({ id: d.id, ifMatch, body });
    const bisher = dokument(d, zustand.stand.get(d.id));
    if (ifMatch !== `"${bisher.version}"`) {
      return json(route, 412, { status: 412, title: 'Version veraltet', fehlercode: 'VERSION_KONFLIKT' });
    }
    if (!body.grund) {
      return json(route, 422, { status: 422, title: 'Pflichtfeld fehlt', fehlercode: 'PFLICHTFELD_FEHLT', felder: ['grund'] });
    }
    const fassung = { version: bisher.version, zeit: new Date().toISOString(), geaendertVon: welt.keycloak.benutzer.id, grund: body.grund };
    const stand = { titel: body.titel ?? bisher.titel, version: bisher.version + 1, versionen: [fassung, ...bisher.versionen] };
    zustand.stand.set(d.id, stand);
    return json(route, 200, dokument(d, stand));
  }

  await welt.seite.route(`${origin}${S3_PFAD}**`, (route) => {
    zustand.dateiAbrufe.push(route.request().url());
    return route.fulfill({ status: 200, headers: { 'content-type': 'application/pdf' }, body: PDF });
  });
  return zustand;
}
