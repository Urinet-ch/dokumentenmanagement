// Echtes Backend für die Browser-Szenarien (Aufgabe 2.4, verallgemeinert mit 4.10): Die PWA meldet sich weiter bei der
// Keycloak-Attrappe an, ihre Anfragen an /api/v1/** leitet weiterleiten() aber an das Archiv im Testmodus weiter
// (BrowserBackendTest: PostgreSQL, Keycloak mit Test-Realm, MinIO). Das unsignierte Token der Attrappe wird dabei gegen
// ein echtes Token desselben Testbenutzers aus dem Test-Realm getauscht (Passwort und TOTP mit dem bekannten
// Test-Secret, nur lokal und in Tests). Adressen aus ARCHIV_API_URL und ARCHIV_KEYCLOAK_URL, sonst aus
// ../backend/target/browser-backend.json.
import assert from 'node:assert/strict';
import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basisUrl } from './keycloak-attrappe.mjs';

const PASSWORT = 'Test-Passwort-1';
const OTP_SECRET = 'urinet-archiv-test-otp-nur-lokal';
const BEREIT = new URL('../../../backend/target/browser-backend.json', import.meta.url);
// Keycloak nimmt denselben TOTP-Code je Benutzer nur einmal an, auch über mehrere Läufe hinweg: Der zuletzt
// verwendete Zeitschritt je Benutzer steht darum in einer Datei neben der Bereit-Datei.
const SCHRITTE = new URL('../../../backend/target/browser-backend-totp.json', import.meta.url);
// Testhilfe für den Status (BrowserBackendTest): Anfrage- und Antwortdateien, nur unter target/
const ANFRAGEN = new URL('../../../backend/target/browser-backend-anfragen/', import.meta.url);

let adressen;

export function backend() {
  if (adressen) {
    return adressen;
  }
  const datei = existsSync(BEREIT) ? JSON.parse(readFileSync(BEREIT, 'utf8')) : {};
  adressen = { api: process.env.ARCHIV_API_URL ?? datei.api, keycloak: process.env.ARCHIV_KEYCLOAK_URL ?? datei.keycloak };
  if (!adressen.api || !adressen.keycloak) {
    throw new Error(
      'Kein echtes Backend: zuerst «mvn -f backend/pom.xml test -Dtest=BrowserBackendTest -Darchiv.browser-backend=true» starten',
    );
  }
  return adressen;
}

/** RFC 6238 wie Testanmeldung.totp: HmacSHA1, 30 s, 6 Stellen, Schlüssel sind die UTF-8-Bytes des Secrets. */
function totp(schritt) {
  const zaehler = Buffer.alloc(8);
  zaehler.writeBigUInt64BE(BigInt(schritt));
  const hash = createHmac('sha1', Buffer.from(OTP_SECRET, 'utf8')).update(zaehler).digest();
  const versatz = hash[hash.length - 1] & 0x0f;
  return String((hash.readUInt32BE(versatz) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

const tokens = new Map();

function letzteSchritte() {
  return existsSync(SCHRITTE) ? JSON.parse(readFileSync(SCHRITTE, 'utf8')) : {};
}

/** Echtes Access-Token des Testbenutzers «<rolle>-<mandant>» (z. B. inhaber-urinet), solange es noch 30 s gilt. */
export async function echtesToken(rolle, mandant) {
  const name = `${rolle}-${mandant}`;
  const vorhanden = tokens.get(name);
  if (vorhanden && vorhanden.gueltigBis > Date.now() + 30_000) {
    return vorhanden.wert;
  }
  let schritt = Math.floor(Date.now() / 30_000);
  if ((letzteSchritte()[name] ?? -1) >= schritt) {
    await new Promise((weiter) => setTimeout(weiter, (schritt + 1) * 30_000 - Date.now() + 100));
    schritt = Math.floor(Date.now() / 30_000);
  }
  writeFileSync(SCHRITTE, JSON.stringify({ ...letzteSchritte(), [name]: schritt }));
  const antwort = await fetch(`${backend().keycloak}/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'archiv-test',
      username: name,
      password: PASSWORT,
      totp: totp(schritt),
    }),
  });
  if (!antwort.ok) {
    throw new Error(`Anmeldung von ${name} am Test-Realm fehlgeschlagen: ${antwort.status}`);
  }
  const inhalt = await antwort.json();
  tokens.set(name, { wert: inhalt.access_token, gueltigBis: Date.now() + inhalt.expires_in * 1000 });
  return inhalt.access_token;
}

/**
 * Lädt Dateien direkt über die API hoch (ohne Browser), z. B. um eine Dublette vorzubereiten. Eine Datei mit
 * `name: null` geht als Teil ohne `filename` (wie ein Client ohne Dateinamen); FormData kennt das nicht.
 */
export async function direktHochladen(rolle, mandant, dateien) {
  let body;
  const headers = { authorization: `Bearer ${await echtesToken(rolle, mandant)}` };
  if (dateien.every((datei) => datei.name !== null)) {
    body = new FormData();
    for (const datei of dateien) {
      body.append('dateien', new Blob([datei.buffer], { type: datei.mimeType }), datei.name);
    }
  } else {
    assert.equal(dateien.length, 1, 'ohne Namen nur eine Datei je Upload');
    const grenze = `----archiv-e2e-${zufall()}`;
    const [datei] = dateien;
    body = Buffer.concat([
      Buffer.from(`--${grenze}\r\nContent-Disposition: form-data; name="dateien"\r\nContent-Type: ${datei.mimeType}\r\n\r\n`),
      datei.buffer,
      Buffer.from(`\r\n--${grenze}--\r\n`),
    ]);
    headers['content-type'] = `multipart/form-data; boundary=${grenze}`;
  }
  const antwort = await fetch(`${backend().api}/api/v1/dokumente`, { method: 'POST', headers, body });
  if (antwort.status !== 202) {
    throw new Error(`Upload über die API: Status ${antwort.status}`);
  }
  return antwort.json();
}

/** Dokument direkt über die API lesen (ohne Browser), z. B. um den gespeicherten Titel zu prüfen. */
export async function direktLesen(rolle, mandant, id) {
  const antwort = await fetch(`${backend().api}/api/v1/dokumente/${id}`, {
    headers: { authorization: `Bearer ${await echtesToken(rolle, mandant)}` },
  });
  assert.equal(antwort.status, 200, `GET /dokumente/${id}`);
  return antwort.json();
}

/** Dokumente, die ein Szenario im echten Archiv angelegt hat: Dateiname (oder Bezeichnung) → ID. */
export function echteDokumente(welt) {
  welt.echteDokumente ??= new Map();
  return welt.echteDokumente;
}

/**
 * Testhilfe für den Status (Aufgabe 4.10): Im Testmodus läuft kein Worker, ein hochgeladenes Dokument bleibt in NEU.
 * BrowserBackendTest setzt es auf Anfrage per SQL auf EINGANGSKORB oder FEHLER, ohne Titel und ohne Vorschlag.
 */
export async function statusSetzen(mandant, id, status) {
  mkdirSync(ANFRAGEN, { recursive: true });
  const name = randomUUID();
  const entwurf = new URL(`${name}.tmp`, ANFRAGEN);
  const antwort = new URL(`${name}.antwort.json`, ANFRAGEN);
  writeFileSync(entwurf, JSON.stringify({ mandant, dokument: id, status }));
  renameSync(entwurf, new URL(`${name}.json`, ANFRAGEN));
  for (const ende = Date.now() + 30_000; Date.now() < ende; ) {
    if (existsSync(antwort)) {
      const ergebnis = JSON.parse(readFileSync(antwort, 'utf8'));
      rmSync(antwort);
      assert.ok(ergebnis.ok, `Testhilfe für den Status: ${ergebnis.fehler}`);
      return;
    }
    await new Promise((weiter) => setTimeout(weiter, 100));
  }
  throw new Error('Die Testhilfe für den Status antwortet nicht: läuft BrowserBackendTest mit diesem Stand?');
}

/**
 * Sendet eine abgefangene Anfrage der PWA an das Archiv im Testmodus: Methode, Körper, Query und Header (auch
 * If-Match) bleiben, nur die Adresse und das Token ändern sich. Getauscht wird das Token der Keycloak-Attrappe gegen
 * ein echtes Token desselben Testbenutzers; mit `tokenTauschen: false` bleibt es, und das Archiv lehnt es mit 401 ab.
 * continue statt fetch: Den Körper (auch Dateien) sendet der Browser selbst, und die Antwort erreicht die PWA samt
 * Headern (ETag, Location) unverändert.
 */
export async function anfrageWeiterleiten(route, benutzer, { tokenTauschen = true } = {}) {
  const url = new URL(route.request().url());
  const headers = { ...route.request().headers() };
  if (tokenTauschen) {
    const {
      rollen: [rolle],
      mandant,
    } = benutzer;
    headers.authorization = `Bearer ${await echtesToken(rolle, mandant)}`;
  }
  await route.continue({ url: `${backend().api}${url.pathname}${url.search}`, headers });
}

const istJson = (typ) => /[/+]json\b/.test(typ ?? '');

/**
 * Leitet die Anfragen der PWA an /api/v1/** an das Archiv im Testmodus weiter (einmal je Szenario, vor der Anmeldung
 * oder vor der ersten Anfrage). `passt(anfrage)` wählt aus, Standard alle; die übrigen gehen mit route.fallback() an die
 * Routen dahinter (Attrappen). Jede weitergeleitete Anfrage steht in welt.weiterleitung.anfragen mit Methode, Pfad,
 * Query, If-Match, JSON-Körper und, sobald da, Status, Headern und JSON der Antwort; `merken(anfrage)` ergänzt den
 * Eintrag (z. B. die Felder eines FormData, die Chromium nicht herausgibt). Scheitert die Weiterleitung, bricht die
 * Anfrage ab und der Fehler steht in welt.weiterleitung.fehler. Mit welt.weiterleitung.sitzungAbgelaufen bleibt das
 * Token der Attrappe.
 */
export async function weiterleiten(welt, { passt = () => true, merken = async () => ({}) } = {}) {
  if (welt.weiterleitung) {
    return welt.weiterleitung;
  }
  const zustand = { anfragen: [], fehler: null, sitzungAbgelaufen: false };
  welt.weiterleitung = zustand;
  const eintraege = new Map();
  welt.seite.on('response', async (antwort) => {
    const eintrag = eintraege.get(antwort.request());
    if (eintrag) {
      eintrag.status = antwort.status();
      eintrag.headers = antwort.headers();
      eintrag.antwort = istJson(eintrag.headers['content-type']) ? await antwort.json().catch(() => null) : null;
    }
  });
  const origin = new URL(basisUrl).origin;
  await welt.seite.route(
    (url) => url.origin === origin && url.pathname.startsWith('/api/v1/'),
    async (route) => {
      const anfrage = route.request();
      if (!passt(anfrage)) {
        return route.fallback();
      }
      try {
        const url = new URL(anfrage.url());
        const json = istJson(await anfrage.headerValue('content-type'));
        const eintrag = {
          methode: anfrage.method(),
          pfad: url.pathname,
          query: url.searchParams,
          ifMatch: await anfrage.headerValue('if-match'),
          body: json ? anfrage.postDataJSON() : null,
          ...(await merken(anfrage)),
        };
        zustand.anfragen.push(eintrag);
        eintraege.set(anfrage, eintrag);
        // Die PWA muss das Token der Anmeldung senden; getauscht wird nur dieses.
        assert.equal(await anfrage.headerValue('authorization'), `Bearer ${welt.keycloak.accessToken}`);
        await anfrageWeiterleiten(route, welt.keycloak.benutzer, { tokenTauschen: !zustand.sitzungAbgelaufen });
      } catch (fehler) {
        zustand.fehler ??= fehler;
        return route.abort('failed');
      }
    },
  );
  return zustand;
}

// Synthetische Testdateien wie Testdateien.java im Backend: jede mit Zufall, damit keine ungewollten Dubletten entstehen.
const zufall = () => randomBytes(16).toString('hex');

function pdf(groesse) {
  const kopf = Buffer.from(`%PDF-1.4\n% Testdokument ${zufall()}\n`, 'ascii');
  const ende = Buffer.from('\n%%EOF\n', 'ascii');
  if (!groesse) {
    return Buffer.concat([kopf, Buffer.from('1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>', 'ascii'), ende]);
  }
  const inhalt = Buffer.alloc(groesse, ' ');
  kopf.copy(inhalt, 0);
  inhalt[kopf.length] = '%'.charCodeAt(0);
  ende.copy(inhalt, groesse - ende.length);
  return inhalt;
}

function jpeg() {
  const kommentar = Buffer.from(zufall(), 'ascii');
  return Buffer.concat([
    Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]),
    Buffer.from([0xff, 0xfe, 0x00, kommentar.length + 2]),
    kommentar,
    Buffer.from([0xff, 0xd9]),
  ]);
}

const CRC_TABELLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return c >>> 0;
});

function crc32(daten) {
  let c = 0xffffffff;
  for (const byte of daten) {
    c = CRC_TABELLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(typ, daten) {
  const laenge = Buffer.alloc(4);
  laenge.writeUInt32BE(daten.length);
  const name = Buffer.from(typ, 'ascii');
  const pruefsumme = Buffer.alloc(4);
  pruefsumme.writeUInt32BE(crc32(Buffer.concat([name, daten])));
  return Buffer.concat([laenge, name, daten, pruefsumme]);
}

function png() {
  const ihdr = Buffer.from([0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0]);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('tEXt', Buffer.from(`Comment\0${zufall()}`, 'latin1')),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function heic() {
  const ftyp = Buffer.alloc(24);
  ftyp.writeUInt32BE(24, 0);
  ftyp.write('ftypheic', 4, 'ascii');
  ftyp.write('mif1heic', 16, 'ascii');
  const inhalt = Buffer.from(zufall(), 'ascii');
  const frei = Buffer.alloc(8);
  frei.writeUInt32BE(8 + inhalt.length, 0);
  frei.write('free', 4, 'ascii');
  return Buffer.concat([ftyp, frei, inhalt]);
}

function tiff() {
  const ifd = Buffer.alloc(26);
  ifd.write('II', 0, 'ascii');
  ifd.writeUInt16LE(42, 2);
  ifd.writeUInt32LE(8, 4);
  ifd.writeUInt16LE(1, 8);
  ifd.writeUInt16LE(256, 10);
  ifd.writeUInt16LE(3, 12);
  ifd.writeUInt32LE(1, 14);
  ifd.writeUInt16LE(1, 18);
  return Buffer.concat([ifd, Buffer.from(zufall(), 'ascii')]);
}

const NACH_ENDUNG = { pdf: () => pdf(), jpg: jpeg, jpeg, png, heic, tif: tiff, tiff };

/** Inhalt nach Endung, oder nach einer Art aus dem Szenario («ein Text», «leer», «abgeschnitten», «grösser als 50 MB»). */
export function inhalt(name, art) {
  switch (art) {
    case 'ein Text':
      return Buffer.from(`Notiz ${zufall()}\n`, 'utf8');
    case 'leer':
      return Buffer.alloc(0);
    case 'abgeschnitten':
      return Buffer.from(`%PDF-1.4\n% Testdokument ${zufall()}\n1 0 obj << /Type /Catalog >> endobj\ntrai`, 'ascii');
    case 'grösser als 50 MB':
      return pdf(50 * 1024 * 1024 + 1);
    case undefined:
      return (NACH_ENDUNG[name.split('.').pop().toLowerCase()] ?? (() => Buffer.from(zufall())))();
    default:
      throw new Error(`Unbekannte Art «${art}»`);
  }
}
