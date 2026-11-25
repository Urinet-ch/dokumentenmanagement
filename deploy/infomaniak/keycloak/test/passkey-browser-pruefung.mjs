// Browser-Prüfung für passkey-einrichten-keycloak-test.sh (Aufgabe 24.3, Vorbereitung): Auf dem per Vorlage nachgerüsteten
// Realm richtet ein synthetischer Benutzer in der Kontoverwaltung einen Passkey ein (virtueller Authenticator, Chromium) und
// meldet sich danach ohne Passwort und TOTP mit dem Passkey an. Nur synthetische Werte, nur lokal.
//
//   node passkey-browser-pruefung.mjs <Keycloak-Basisadresse> <Playwright-Verzeichnis>
//
// Das Playwright-Verzeichnis enthält node_modules/playwright (z. B. frontend/ nach npm ci).
import assert from 'node:assert/strict';
import { createHmac, randomBytes } from 'node:crypto';
import { createRequire } from 'node:module';
import { join } from 'node:path';

const [basis, playwrightVerzeichnis] = process.argv.slice(2);
assert.ok(basis && playwrightVerzeichnis, 'Aufruf: node passkey-browser-pruefung.mjs <Basisadresse> <Playwright-Verzeichnis>');
const { chromium } = createRequire(join(playwrightVerzeichnis, 'package.json'))('playwright');

const REALM = `${basis}/realms/urinet-archiv`;
const ADMIN = `${basis}/admin/realms/urinet-archiv`;
const PASSWORT = 'Synthetisch-Passwort-1';
const OTP_GEHEIMNIS = 'synthetisches-otp-geheimnis-24-3';

function totp(schritt) {
  const zaehler = Buffer.alloc(8);
  zaehler.writeBigUInt64BE(BigInt(schritt));
  const hash = createHmac('sha1', OTP_GEHEIMNIS).update(zaehler).digest();
  const versatz = hash[hash.length - 1] & 15;
  return String((hash.readUInt32BE(versatz) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

async function pruefe(antwort, status, aktion) {
  assert.equal(antwort.status, status, `${aktion}: HTTP ${antwort.status}`);
  return antwort;
}

const tokenAntwort = await fetch(`${basis}/realms/master/protocol/openid-connect/token`, {
  method: 'POST',
  body: new URLSearchParams({
    client_id: 'admin-cli',
    grant_type: 'password',
    username: 'archiv-admin',
    password: 'synthetisch-test',
  }),
});
await pruefe(tokenAntwort, 200, 'Admin anmelden');
const header = { authorization: `Bearer ${(await tokenAntwort.json()).access_token}`, 'content-type': 'application/json' };

const name = `passkey-${randomBytes(6).toString('hex')}`;
await pruefe(
  await fetch(`${ADMIN}/partialImport`, {
    method: 'POST',
    headers: header,
    body: JSON.stringify({
      ifResourceExists: 'FAIL',
      users: [
        {
          username: name,
          enabled: true,
          email: `${name}@example.invalid`,
          emailVerified: true,
          firstName: 'Test',
          lastName: 'Benutzer',
          attributes: { mandant: ['urinet'] },
          realmRoles: ['default-roles-urinet-archiv'],
          requiredActions: [],
          credentials: [
            { type: 'password', value: PASSWORT, temporary: false },
            {
              type: 'otp',
              userLabel: 'Test',
              secretData: JSON.stringify({ value: OTP_GEHEIMNIS }),
              credentialData: JSON.stringify({ subType: 'totp', digits: 6, counter: 0, period: 30, algorithm: 'HmacSHA1' }),
            },
          ],
        },
      ],
    }),
  }),
  200,
  'Testbenutzer anlegen',
);
const benutzer = await (await fetch(`${ADMIN}/users?exact=true&username=${name}`, { headers: header })).json();
assert.equal(benutzer.length, 1);

const browser = await chromium.launch();
try {
  const kontext = await browser.newContext();
  const seite = await kontext.newPage();
  const cdp = await kontext.newCDPSession(seite);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true },
  });

  // Passwort und TOTP, dann die Kontoverwaltung: Sie braucht die Rollen aus der Zuordnung account-console -> account
  await seite.goto(`${REALM}/account/account-security/signing-in`);
  await seite.locator('#username').fill(name);
  await seite.locator('#password').fill(PASSWORT);
  await seite.locator('#kc-login').click();
  await seite.locator('#otp').fill(totp(Math.floor(Date.now() / 30_000)));
  await seite.locator('#kc-login').click();
  const bezeichnung = `Virtueller Passkey ${randomBytes(6).toString('hex')}`;
  seite.once('dialog', (dialog) => dialog.accept(bezeichnung));
  await seite.getByTestId('webauthn-passwordless/create').click();
  await seite.locator('#registerWebAuthn').click();
  await seite.getByText(bezeichnung, { exact: true }).waitFor();
  const { credentials } = await cdp.send('WebAuthn.getCredentials', { authenticatorId });
  assert.equal(credentials.length, 1);
  assert.equal(credentials[0].isResidentCredential, true);
  const admin = await (await fetch(`${ADMIN}/users/${benutzer[0].id}/credentials`, { headers: header })).json();
  assert.equal(admin.filter((c) => c.type === 'webauthn-passwordless').length, 1);
  assert.equal(admin.filter((c) => c.type === 'otp').length, 1, 'TOTP bleibt neben dem Passkey bestehen');
  console.log('Passkey in der Kontoverwaltung eingerichtet (auffindbar, ein Passkey, TOTP bleibt).');

  // Neue Sitzung: Anmeldung mit dem Passkey, ohne Passwort und ohne TOTP
  await kontext.clearCookies();
  await seite.goto(`${REALM}/account/account-security/signing-in`);
  await seite.waitForURL((url) => url.pathname.startsWith('/realms/urinet-archiv/account/'), { timeout: 20_000 });
  await seite.getByText(bezeichnung, { exact: true }).waitFor();
  console.log('Anmeldung mit dem Passkey ohne Passwort und TOTP erfolgreich.');

  // Rückfall: Eine Sitzung ohne den Passkey braucht weiterhin Passwort und TOTP
  const ohnePasskey = await browser.newContext();
  const zweite = await ohnePasskey.newPage();
  await zweite.goto(`${REALM}/account/account-security/signing-in`);
  await zweite.locator('#username').fill(name);
  await zweite.locator('#password').fill(PASSWORT);
  await zweite.locator('#kc-login').click();
  await zweite.locator('#otp').waitFor({ timeout: 20_000 });
  console.log('Passwort-Anmeldung verlangt weiterhin TOTP.');
} finally {
  await browser.close();
  await fetch(`${ADMIN}/users/${benutzer[0].id}`, { method: 'DELETE', headers: header });
}
