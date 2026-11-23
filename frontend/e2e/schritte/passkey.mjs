import { After, Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { backend } from '../hilfen/echtes-backend.mjs';

const PASSWORT = 'Test-Passwort-1';
const OTP_SECRET = 'urinet-archiv-test-otp-nur-lokal';

function totp(schritt) {
  const zaehler = Buffer.alloc(8);
  zaehler.writeBigUInt64BE(BigInt(schritt));
  const hash = createHmac('sha1', OTP_SECRET).update(zaehler).digest();
  const versatz = hash[hash.length - 1] & 15;
  return String((hash.readUInt32BE(versatz) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

async function pruefeAntwort(antwort, status, aktion) {
  assert.equal(antwort.status, status, `${aktion}: HTTP ${antwort.status}`);
}

async function testbenutzer(welt) {
  const url = new URL(backend().keycloak);
  assert.ok(['localhost', '127.0.0.1'].includes(url.hostname), 'Keycloak muss lokal laufen');
  url.hostname = 'localhost';
  const realm = url.toString().replace(/\/$/, '');
  const adminAntwort = await fetch(`${url.origin}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      client_id: 'admin-cli',
      grant_type: 'password',
      username: 'admin',
      password: 'admin',
    }),
  });
  await pruefeAntwort(adminAntwort, 200, 'Test-Admin anmelden');
  const admin = (await adminAntwort.json()).access_token;
  const name = `passkey-${randomBytes(8).toString('hex')}`;
  const header = { authorization: `Bearer ${admin}`, 'content-type': 'application/json' };
  const adminUrl = `${url.origin}/admin/realms/urinet-archiv`;
  const anlegen = await fetch(`${adminUrl}/partialImport`, {
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
              secretData: JSON.stringify({ value: OTP_SECRET }),
              credentialData: JSON.stringify({
                subType: 'totp',
                digits: 6,
                counter: 0,
                period: 30,
                algorithm: 'HmacSHA1',
              }),
            },
          ],
        },
      ],
    }),
  });
  await pruefeAntwort(anlegen, 200, 'Testbenutzer anlegen');
  const abfrage = await fetch(`${adminUrl}/users?exact=true&username=${encodeURIComponent(name)}`, {
    headers: header,
  });
  await pruefeAntwort(abfrage, 200, 'Testbenutzer suchen');
  const benutzer = await abfrage.json();
  assert.equal(benutzer.length, 1);
  welt.passkey = { realm, adminUrl, header, name, id: benutzer[0].id, letzterOtpSchritt: -1 };
  const callbackServer = createServer((anfrage, antwort) => antwort.end('OK'));
  welt.passkey.callbackServer = callbackServer;
  await new Promise((weiter, fehler) =>
    callbackServer.once('error', fehler).listen(0, '127.0.0.1', weiter),
  );
  welt.passkey.callback = `http://127.0.0.1:${callbackServer.address().port}/passkey-test-callback`;
  const cdp = await welt.kontext.newCDPSession(welt.seite);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
    },
  });
  Object.assign(welt.passkey, { cdp, authenticatorId });
}

function anmeldeUrl(passkey, optionen = {}) {
  const verifier = randomBytes(32).toString('base64url');
  const parameter = new URLSearchParams({
    client_id: 'archiv-pwa',
    response_type: 'code',
    scope: 'openid',
    redirect_uri: passkey.callback,
    state: randomBytes(16).toString('hex'),
    code_challenge_method: 'S256',
    code_challenge: createHash('sha256').update(verifier).digest('base64url'),
    ...optionen,
  });
  passkey.verifier = verifier;
  return `${passkey.realm}/protocol/openid-connect/auth?${parameter}`;
}

const amCallback = (passkey) => (url) =>
  url.origin === new URL(passkey.callback).origin && url.pathname === '/passkey-test-callback';

async function otpEingeben(welt, seite) {
  let schritt = Math.floor(Date.now() / 30_000);
  if (schritt <= welt.passkey.letzterOtpSchritt) {
    await new Promise((weiter) =>
      setTimeout(weiter, (welt.passkey.letzterOtpSchritt + 1) * 30_000 - Date.now() + 200),
    );
    schritt = Math.floor(Date.now() / 30_000);
  }
  await seite.locator('#otp').fill(totp(schritt));
  welt.passkey.letzterOtpSchritt = schritt;
  await seite.locator('#kc-login').click();
  await seite.waitForURL(amCallback(welt.passkey));
}

async function passwortAnmelden(welt, seite, negativprobe = false) {
  await seite.goto(anmeldeUrl(welt.passkey));
  await seite.locator('#username').fill(welt.passkey.name);
  await seite.locator('#password').fill(PASSWORT);
  await seite.locator('#kc-login').click();
  await seite.locator('#otp').waitFor();
  if (negativprobe) {
    await Promise.all([
      seite.waitForEvent('framenavigated', { predicate: (frame) => frame === seite.mainFrame() }),
      seite.locator('#kc-login').click(),
    ]);
    await seite.waitForLoadState('networkidle');
    assert.equal(amCallback(welt.passkey)(new URL(seite.url())), false);
    await seite.locator('#otp').waitFor();
  }
  await otpEingeben(welt, seite);
}

async function einrichten(welt) {
  const { realm, cdp, authenticatorId } = welt.passkey;
  await welt.seite.goto(`${realm}/account/account-security/signing-in`);
  welt.passkey.label = `Virtueller Passkey ${randomBytes(8).toString('hex')}`;
  welt.seite.once('dialog', (dialog) => dialog.accept(welt.passkey.label));
  await welt.seite.getByTestId('webauthn-passwordless/create').click();
  await welt.seite.locator('#registerWebAuthn').click();
  await welt.seite.getByText(welt.passkey.label, { exact: true }).waitFor();
  const { credentials } = await cdp.send('WebAuthn.getCredentials', { authenticatorId });
  assert.equal(credentials.length, 1);
  assert.equal(credentials[0].isResidentCredential, true);
}

async function accessToken(welt) {
  const code = new URL(welt.seite.url()).searchParams.get('code');
  assert.ok(code, 'Keycloak lieferte keinen Autorisierungscode');
  const antwort = await fetch(`${welt.passkey.realm}/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: 'archiv-pwa',
      code,
      redirect_uri: welt.passkey.callback,
      code_verifier: welt.passkey.verifier,
    }),
  });
  await pruefeAntwort(antwort, 200, 'Access-Token beziehen');
  const token = (await antwort.json()).access_token;
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url'));
}

async function passkeyAnmelden(welt) {
  await welt.kontext.clearCookies();
  await welt.seite.goto(anmeldeUrl(welt.passkey));
  await welt.seite.waitForURL(amCallback(welt.passkey));
  welt.passkey.claims = await accessToken(welt);
}

async function entfernen(welt) {
  await welt.seite.goto(`${welt.passkey.realm}/account/account-security/signing-in`);
  await welt.seite
    .locator('li')
    .filter({ hasText: welt.passkey.label })
    .locator('[data-testrole=remove]')
    .click();
  await welt.seite.getByRole('button', { name: 'Löschung bestätigen' }).click();
  await welt.seite.getByText(welt.passkey.label, { exact: true }).waitFor({ state: 'hidden' });
}

After({ tags: '@passkey' }, async function () {
  try {
    if (this.passkey?.id) {
      const antwort = await fetch(`${this.passkey.adminUrl}/users/${this.passkey.id}`, {
        method: 'DELETE',
        headers: this.passkey.header,
      });
      await pruefeAntwort(antwort, 204, 'Testbenutzer entfernen');
    }
  } finally {
    this.passkey?.callbackServer?.close();
  }
});

Given('ein Testbenutzer ohne Passkey existiert', { timeout: 90_000 }, async function () {
  await testbenutzer(this);
});

Given('ein Testbenutzer ist mit Passwort und TOTP angemeldet', { timeout: 90_000 }, async function () {
  await testbenutzer(this);
  await passwortAnmelden(this, this.seite);
});

Given('ein Testbenutzer hat einen Passkey eingerichtet', { timeout: 90_000 }, async function () {
  await testbenutzer(this);
  await passwortAnmelden(this, this.seite);
  await einrichten(this);
});

When('er in der Kontoverwaltung einen Passkey einrichtet', { timeout: 90_000 }, async function () {
  await einrichten(this);
});

When('er sich mit dem Passkey anmeldet', { timeout: 90_000 }, async function () {
  await passkeyAnmelden(this);
});

Given('er ist mit dem Passkey angemeldet', { timeout: 90_000 }, async function () {
  await passkeyAnmelden(this);
});

When('er sich für den Step-up mit dem Passkey erneut anmeldet', { timeout: 90_000 }, async function () {
  this.passkey.vorher = this.passkey.claims.auth_time;
  await new Promise((weiter) => setTimeout(weiter, 2100));
  await this.seite.goto(anmeldeUrl(this.passkey, { prompt: 'login', max_age: '0' }));
  await this.seite.locator('#authenticateWebAuthnButton').click();
  await this.seite.waitForURL(amCallback(this.passkey));
  this.passkey.claims = await accessToken(this);
});

Then('erhält er ein Access-Token mit auth_time', function () {
  assert.equal(typeof this.passkey.claims.auth_time, 'number');
  assert.ok(this.passkey.claims.acr || this.passkey.claims.amr);
});

Then('enthält das neue Access-Token eine neuere auth_time', function () {
  assert.ok(this.passkey.claims.auth_time > this.passkey.vorher);
  assert.ok(this.passkey.claims.acr || this.passkey.claims.amr);
});

Then('enthält das Access-Token keine WebAuthn- oder biometrischen Daten', function () {
  const erlaubteClaims = new Set([
    'exp',
    'iat',
    'nbf',
    'auth_time',
    'jti',
    'iss',
    'aud',
    'sub',
    'typ',
    'azp',
    'sid',
    'session_state',
    'acr',
    'amr',
    'nonce',
    'allowed-origins',
    'realm_access',
    'resource_access',
    'scope',
    'email_verified',
    'name',
    'preferred_username',
    'given_name',
    'family_name',
    'email',
    'mandant',
  ]);
  assert.deepEqual(
    Object.keys(this.passkey.claims).filter((claim) => !erlaubteClaims.has(claim)),
    [],
  );
  const rollen = (wert) =>
    wert &&
    Object.keys(wert).length === 1 &&
    Array.isArray(wert.roles) &&
    wert.roles.every((rolle) => typeof rolle === 'string');
  if (this.passkey.claims.realm_access) {
    assert.ok(rollen(this.passkey.claims.realm_access));
  }
  if (this.passkey.claims.resource_access) {
    assert.ok(Object.values(this.passkey.claims.resource_access).every(rollen));
  }
});

Then(
  'liefert das Archiv die konfigurierte Keycloak-Origin unter dem WebAuthn-Pfad',
  async function () {
    const antwort = await fetch(new URL('/.well-known/webauthn', backend().api), {
      redirect: 'manual',
    });
    await pruefeAntwort(antwort, 200, 'Related Origins');
    assert.match(antwort.headers.get('content-type'), /^application\/json\b/);
    assert.deepEqual(await antwort.json(), { origins: [new URL(backend().keycloak).origin] });
  },
);

Then('kann er diesen Passkey in der Kontoverwaltung entfernen', async function () {
  await entfernen(this);
});

When('ein Keycloak-Admin diesen Passkey zurücksetzt', async function () {
  const { adminUrl, id, header } = this.passkey;
  const url = `${adminUrl}/users/${id}/credentials`;
  const antwort = await fetch(url, { headers: header });
  await pruefeAntwort(antwort, 200, 'Zugangsdaten lesen');
  const credentials = await antwort.json();
  const passkeys = credentials.filter((credential) => credential.type === 'webauthn-passwordless');
  assert.equal(passkeys.length, 1);
  const geloescht = await fetch(`${url}/${encodeURIComponent(passkeys[0].id)}`, {
    method: 'DELETE',
    headers: header,
  });
  await pruefeAntwort(geloescht, 204, 'Passkey zurücksetzen');
  const erneut = await fetch(url, { headers: header });
  await pruefeAntwort(erneut, 200, 'Zugangsdaten erneut lesen');
  const verbleibend = await erneut.json();
  assert.equal(
    verbleibend.some((credential) => credential.type === 'webauthn-passwordless'),
    false,
  );
  assert.ok(verbleibend.some((credential) => credential.type === 'otp'));
});

Then(
  'verlangt die Passwortanmeldung dieses Benutzers weiterhin TOTP',
  { timeout: 90_000 },
  async function () {
    const kontext = await this.browser.newContext({ locale: 'de-CH' });
    try {
      const seite = await kontext.newPage();
      await passwortAnmelden(this, seite, true);
    } finally {
      await kontext.close();
    }
  },
);
