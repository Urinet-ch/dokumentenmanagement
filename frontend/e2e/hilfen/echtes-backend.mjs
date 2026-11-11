// Echtes Backend für die Upload-Szenarien (Aufgabe 2.4): Die PWA meldet sich weiter bei der Keycloak-Attrappe an, ihre
// Uploads leitet upload.mjs aber an das Archiv im Testmodus weiter (BrowserBackendTest: PostgreSQL, Keycloak mit
// Test-Realm, MinIO). Das unsignierte Token der Attrappe wird dabei gegen ein echtes Token desselben Testbenutzers aus
// dem Test-Realm getauscht (Passwort und TOTP mit dem bekannten Test-Secret, nur lokal und in Tests).
// Adressen aus ARCHIV_API_URL und ARCHIV_KEYCLOAK_URL, sonst aus ../backend/target/browser-backend.json.
import { createHmac, randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const PASSWORT = 'Test-Passwort-1';
const OTP_SECRET = 'urinet-archiv-test-otp-nur-lokal';
const BEREIT = new URL('../../../backend/target/browser-backend.json', import.meta.url);
// Keycloak nimmt denselben TOTP-Code je Benutzer nur einmal an, auch über mehrere Läufe hinweg: Der zuletzt
// verwendete Zeitschritt je Benutzer steht darum in einer Datei neben der Bereit-Datei.
const SCHRITTE = new URL('../../../backend/target/browser-backend-totp.json', import.meta.url);

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

/** Lädt Dateien direkt über die API hoch (ohne Browser), z. B. um eine Dublette vorzubereiten. */
export async function direktHochladen(rolle, mandant, dateien) {
  const formular = new FormData();
  for (const datei of dateien) {
    formular.append('dateien', new Blob([datei.buffer], { type: datei.mimeType }), datei.name);
  }
  const antwort = await fetch(`${backend().api}/api/v1/dokumente`, {
    method: 'POST',
    headers: { authorization: `Bearer ${await echtesToken(rolle, mandant)}` },
    body: formular,
  });
  if (antwort.status !== 202) {
    throw new Error(`Upload über die API: Status ${antwort.status}`);
  }
  return antwort.json();
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
