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

// Wie im Seed: additionalProperties false, darum muss ein Artwechsel die Zusatzwerte der alten Art weglassen.
const schema = (properties) => ({ type: 'object', properties, additionalProperties: false });
ARTEN.push(
  { code: 'OFFERTE', name: 'Offerte', gruppen: ['finanz'], pflichtfelder: [], zusatzSchema: schema({ angebot_gueltig_bis: { type: 'string', format: 'date' } }) },
  { code: 'QUITTUNG', name: 'Quittung', gruppen: ['finanz'], pflichtfelder: [], zusatzSchema: schema({}) },
  {
    code: 'LOHNABRECHNUNG',
    name: 'Lohnabrechnung',
    gruppen: [],
    pflichtfelder: ['zusatz.periode', 'zusatz.person'],
    zusatzSchema: schema({ periode: { type: 'string' }, person: { type: 'string' } }),
  },
);
for (const a of ARTEN.slice(2)) {
  Object.assign(a, { vertraulichkeit: 'NORMAL', gesetzlichePflicht: true, aktiv: true });
}

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
  // Ohne Titel (Aufgabe 4.10, Req 14.9, 14.10): Die PWA zeigt den Dateinamen als Platzhalter, nie als Titel.
  {
    id: '0b6f1d3e-0000-4000-8000-000000000003',
    titel: null,
    dateiname: 'Scan_Kassenzettel_Testladen_2026-11-13_Seite_01_bis_12_unbearbeitet.pdf',
    status: 'VERARBEITUNG',
    tags: [],
    text: '',
  },
  {
    id: '0b6f1d3e-0000-4000-8000-000000000004',
    titel: null,
    art: 'LIEFERANTENRECHNUNG',
    dateiname: 'קבלה_<b>Fotobeleg</b>.pdf',
    status: 'FEHLER',
    tags: [],
    text: '',
  },
  {
    id: '0b6f1d3e-0000-4000-8000-000000000005',
    titel: 'Offerte Testclub Altdorf',
    art: 'OFFERTE',
    datum: '2026-04-01',
    partnerId: '0b6f1d3e-0000-4000-8000-0000000000a3',
    partnerName: 'Testclub Altdorf',
    belegnummer: 'O-2026-005',
    betrag: 500,
    richtung: 'EINNAHME',
    tags: [],
    zusatz: { angebot_gueltig_bis: '2026-12-31' },
    nurMitSuchbegriff: true,
    text: 'Offerte für das Clubheim Altdorf.',
  },
];

// Hybridsuche (Spec 02, Aufgabe 6.6): 45 Dokumente mit einem gemeinsamen Wort. Die Reihenfolge der Antwort im Modus
// hybrid ist die des Rerankers; sie ist absichtlich weder nach Datum noch nach Titel geordnet, damit die PWA nicht
// selbst sortieren kann. Im Volltext liefert die Attrappe die Dokumente in der Reihenfolge ihrer Nummer.
export const SAMMEL_BEGRIFF = 'Sammelbeleg';
export const SAMMEL_ANZAHL = 45;
const sammelNummer = (i) => ((i * 17) % SAMMEL_ANZAHL) + 1;
const nummerText = (n) => String(n).padStart(2, '0');
export const sammelTitel = (n) => `${SAMMEL_BEGRIFF} ${nummerText(n)}`;
export const SAMMEL_DOKUMENTE = Array.from({ length: SAMMEL_ANZAHL }, (_, i) => {
  const n = i + 1;
  return {
    id: `0b6f1d3e-0000-4000-8000-0000000001${nummerText(n)}`,
    titel: sammelTitel(n),
    art: 'QUITTUNG',
    // Datum nicht in der Reihenfolge der Nummer
    datum: `2026-${nummerText(((n * 5) % 12) + 1)}-${nummerText(((n * 3) % 27) + 1)}`,
    tags: [],
    text: `Gemeinsames Wort ${SAMMEL_BEGRIFF}, Nummer ${n}.`,
    sammel: true,
  };
});
/** Titel der ersten 30 Treffer im Modus hybrid in der Reihenfolge des Rerankers. */
export const HYBRID_REIHENFOLGE = Array.from({ length: SAMMEL_ANZAHL }, (_, i) => sammelTitel(sammelNummer(i))).slice(0, 30);

const HYBRID_RANG = new Map(
  Array.from({ length: SAMMEL_ANZAHL }, (_, i) => [sammelTitel(sammelNummer(i)), i]),
);

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

const EINGANG = (d) => `${d.datum ?? '2026-11-13'}T08:00:00Z`;

function treffer(d, q) {
  return {
    id: d.id,
    art: d.art,
    titel: d.titel,
    dateiname: d.dateiname ?? null,
    datum: d.datum,
    eingang: EINGANG(d),
    status: d.status ?? 'ARCHIVIERT',
    vertraulichkeit: 'NORMAL',
    tags: d.tags,
    geschaeftsjahr: d.datum ? Number(d.datum.slice(0, 4)) : null,
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
    art: stand?.art ?? d.art,
    titel: stand ? stand.titel : d.titel,
    dateiname: d.dateiname ?? null,
    datum: d.datum,
    eingang: EINGANG(d),
    quelle: 'UPLOAD',
    tags: d.tags,
    vertraulichkeit: 'NORMAL',
    status: d.status ?? 'ARCHIVIERT',
    ...(d.datum
      ? {
          bestaetigt: `${d.datum}T09:00:00Z`,
          geschaeftsjahr: Number(d.datum.slice(0, 4)),
          aufbewahrenBis: `${Number(d.datum.slice(0, 4)) + 11}-01-01`,
          finanz: { belegnummer: d.belegnummer, richtung: d.richtung, betrag: d.betrag, waehrung: 'CHF' },
        }
      : {}),
    fristAbgelaufen: false,
    zusatz: stand?.zusatz ?? d.zusatz ?? {},
    partnerId: d.partnerId,
    partnerName: d.partnerName,
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
  const zustand = { rerankerAusfall: false, anfragen: [], downloads: [], abgelehnt: 0, dateiAbrufe: [], aenderungen: [], bilder: [], stand: new Map() };
  welt.suche = zustand;
  const json = (route, status, body) =>
    route.fulfill({ status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' }, body: JSON.stringify(body) });

  const ungueltig = (route, was) => json(route, 400, { status: 400, title: 'Anfrage ungültig', fehlercode: 'ANFRAGE_UNGUELTIG', detail: was });
  // Rang im Modus hybrid: erst die Sammeldokumente in der Reihenfolge des Rerankers, dann die übrigen in Listenreihenfolge
  const rangHybrid = (d) => (d.sammel ? HYBRID_RANG.get(d.titel) : 1000 + DOKUMENTE.indexOf(d));
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
      const modus = p.get('modus');
      if (modus !== null && !['volltext', 'hybrid'].includes(modus)) {
        return ungueltig(route, `modus ${modus}`);
      }
      if (modus === 'hybrid' && (!q.trim() || p.get('sortierung') === 'DATUM' || p.has('seite') || p.has('groesse'))) {
        return ungueltig(route, `hybrid mit ${p}`);
      }
      const gefunden = (d) =>
        (!d.nurMitSuchbegriff || q) &&
        (!q || [d.titel, d.text, d.partnerName, d.belegnummer, d.dateiname].join(' ').toLowerCase().includes(q.toLowerCase())) &&
        (!p.get('belegnummer') || d.belegnummer === p.get('belegnummer')) &&
        (!p.get('art') || d.art === p.get('art')) &&
        (!p.get('partner') || d.partnerId === p.get('partner')) &&
        (!p.get('richtung') || d.richtung === p.get('richtung'));
      const liste = [...DOKUMENTE, ...(q.toLowerCase().includes(SAMMEL_BEGRIFF.toLowerCase()) ? SAMMEL_DOKUMENTE : [])].filter(gefunden);
      if (modus === 'hybrid') {
        // Vertrag von Aufgabe 6.4: höchstens 30 Treffer in der Reihenfolge des Rerankers, gesamt = gelieferte Treffer
        const degradiert = zustand.rerankerAusfall;
        const geordnet = degradiert ? liste : [...liste].sort((a, b) => rangHybrid(a) - rangHybrid(b));
        const beste = geordnet.slice(0, 30);
        return json(route, 200, {
          treffer: beste.map((d, i) => ({ ...treffer(d, q), ...(!degradiert && i === 0 ? { fundstelle: { abschnitt: 1, seiteVon: 1, seiteBis: 1 } } : {}) })),
          gesamt: beste.length,
          seite: 0,
          groesse: 30,
          modus: 'hybrid',
          degradiert,
        });
      }
      const groesse = Number(p.get('groesse') ?? 20);
      const seite = Number(p.get('seite') ?? 0);
      return json(route, 200, {
        treffer: liste.slice(seite * groesse, (seite + 1) * groesse).map((d) => treffer(d, q)),
        gesamt: liste.length,
        seite,
        groesse,
      });
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

  // Nur, was die Szenarien brauchen: Version, Grund bei ARCHIVIERT, Titel, Zusatz-Schlüssel; die volle Prüfung belegt aendern.feature.
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
    if (bisher.status === 'ARCHIVIERT' && !body.grund) {
      return json(route, 422, { status: 422, title: 'Pflichtfeld fehlt', fehlercode: 'PFLICHTFELD_FEHLT', felder: ['grund'] });
    }
    // Wie das Archiv: Bei Artwechsel oder neuem Zusatz gilt das Schema der (neuen) Art, unbekannte Schlüssel → 422.
    const art = ARTEN.find((a) => a.code === (body.art ?? bisher.art));
    const zusatz = body.zusatz ?? bisher.zusatz;
    if (zusatz && art?.zusatzSchema?.additionalProperties === false && ('zusatz' in body || (body.art && body.art !== bisher.art))) {
      const bekannt = Object.keys(art.zusatzSchema.properties ?? {});
      if (Object.keys(zusatz).some((k) => !bekannt.includes(k))) {
        return json(route, 422, { status: 422, title: 'Zusatz ungültig', fehlercode: 'ZUSATZ_UNGUELTIG' });
      }
    }
    const fassung = { version: bisher.version, zeit: new Date().toISOString(), geaendertVon: welt.keycloak.benutzer.id, grund: body.grund ?? null };
    const stand = { titel: 'titel' in body ? body.titel : bisher.titel, art: body.art ?? bisher.art, zusatz: body.zusatz ?? bisher.zusatz, version: bisher.version + 1, versionen: [fassung, ...bisher.versionen] };
    zustand.stand.set(d.id, stand);
    return json(route, 200, dokument(d, stand));
  }

  await welt.seite.route(`${origin}${S3_PFAD}**`, (route) => {
    zustand.dateiAbrufe.push(route.request().url());
    return route.fulfill({ status: 200, headers: { 'content-type': 'application/pdf' }, body: PDF });
  });
  return zustand;
}
