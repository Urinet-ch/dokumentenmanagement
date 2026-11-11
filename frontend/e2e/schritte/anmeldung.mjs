import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { basisUrl, benutzer, keycloakAttrappe } from '../hilfen/keycloak-attrappe.mjs';

const KNOPF_ANMELDEN = 'Anmelden';
const KNOPF_ABMELDEN = 'Abmelden';

async function anmelden(welt) {
  const seite = welt.seite;
  if (!seite.url().startsWith(basisUrl)) {
    await seite.goto(basisUrl);
  }
  await seite.getByRole('button', { name: KNOPF_ANMELDEN, exact: true }).click();
  await seite.getByRole('button', { name: KNOPF_ABMELDEN, exact: true }).waitFor();
  await seite.waitForURL((url) => url.pathname !== '/anmeldung');
}

async function apiAnfragenMerken(welt) {
  welt.apiAnfragen ??= [];
  welt.seite.on('request', (anfrage) => {
    if (new URL(anfrage.url()).pathname.startsWith('/api/')) {
      welt.apiAnfragen.push(anfrage.url());
    }
  });
}

Given(/^Keycloak kennt mich als «(.+)» im Mandanten «(.+)»$/, async function (rolle, mandant) {
  const kc = await keycloakAttrappe(this);
  kc.benutzer = benutzer(rolle, mandant);
  await apiAnfragenMerken(this);
});

Given(/^Keycloak kennt mich als «(.+)» ohne Mandant$/, async function (rolle) {
  const kc = await keycloakAttrappe(this);
  kc.benutzer = benutzer(rolle, null);
  await apiAnfragenMerken(this);
});

Given(/^Keycloak stellt Tokens mit (\d+) Sekunden Gültigkeit aus$/, async function (sekunden) {
  (await keycloakAttrappe(this)).ablaufSekunden = Number(sekunden);
});

Given(/^ich bin als «(.+)» im Mandanten «(.+)» angemeldet$/, async function (rolle, mandant) {
  const kc = await keycloakAttrappe(this);
  kc.benutzer = benutzer(rolle, mandant);
  await apiAnfragenMerken(this);
  await anmelden(this);
});

When('ich mich anmelde', async function () {
  await anmelden(this);
});

When(/^ich die Seite «(.+)» öffne$/, async function (pfad) {
  await this.seite.goto(basisUrl + pfad);
});

When(/^ich auf «(.+)» klicke$/, async function (name) {
  await this.seite.getByRole('button', { name, exact: true }).click();
});

When(/^ich im Menü «(.+)» wähle$/, async function (punkt) {
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' }).getByRole('link', { name: punkt }).click();
});

When('Keycloak die Erneuerung der Sitzung ablehnt', async function () {
  this.keycloak.erneuerungAblehnen = true;
});

Then('hat die Anmeldung PKCE mit S256 verwendet', function () {
  assert.ok(this.keycloak.pkce.length > 0, 'kein Token-Tausch');
  assert.ok(this.keycloak.pkce.every(Boolean), 'code_verifier passt nicht zur code_challenge (S256)');
});

Then('liegt kein Token im Browser-Speicher', async function () {
  const gespeichert = await this.seite.evaluate(() =>
    [localStorage, sessionStorage].flatMap((speicher) =>
      Array.from({ length: speicher.length }, (_, i) => `${speicher.key(i)}=${speicher.getItem(speicher.key(i))}`),
    ),
  );
  const cookies = (await this.kontext.cookies()).map((cookie) => cookie.value);
  const alles = [...gespeichert, ...cookies].join('\n');
  for (const token of this.keycloak.tokens) {
    assert.ok(!alles.includes(token.wert), `${token.typ}-Token im Browser-Speicher`);
  }
  assert.deepEqual(gespeichert, [], 'Browser-Speicher nicht leer');
});

Then(/^bin ich auf der Seite «(.+)»$/, async function (pfad) {
  await this.seite.waitForURL((url) => url.pathname === pfad);
});

Then(/^sehe ich «(.+)» und «(.+)» in der Kopfzeile$/, async function (name, mandant) {
  const kopf = this.seite.locator('[data-test=benutzer]');
  await kopf.waitFor();
  const text = await kopf.innerText();
  assert.ok(text.includes(name) && text.includes(mandant), text);
});

Then('hat Keycloak die Abmeldung erhalten', async function () {
  await this.seite.getByRole('button', { name: KNOPF_ANMELDEN, exact: true }).waitFor();
  assert.equal(this.keycloak.abmeldungen.length, 1);
  const abmeldung = this.keycloak.abmeldungen[0];
  assert.equal(abmeldung.post_logout_redirect_uri, `${new URL(basisUrl).origin}/`);
  assert.ok(abmeldung.id_token_hint, 'id_token_hint fehlt');
});

Then(/^sehe ich den Knopf «(.+)»$/, async function (name) {
  await this.seite.getByRole('button', { name, exact: true }).waitFor();
});

Then(/^sehe ich die Meldung «(.+)»$/, async function (text) {
  await this.seite.getByText(text, { exact: true }).waitFor();
});

Then(/^sehe ich im Menü genau «(.*)»$/, async function (liste) {
  const nav = this.seite.getByRole('navigation', { name: 'Hauptnavigation' });
  await nav.waitFor();
  const punkte = await nav
    .locator('a .punkt')
    .evaluateAll((elemente) => elemente.map((e) => e.firstChild?.textContent?.trim() ?? ''));
  assert.deepEqual(punkte, liste.split(', '));
});

Then('sehe ich kein Menü', async function () {
  assert.equal(await this.seite.getByRole('navigation', { name: 'Hauptnavigation' }).count(), 0);
});

Then('zeigt die Navigation keinen Eingangskorb-Zähler und keine Fehlermeldung', async function () {
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' }).waitFor();
  assert.equal(await this.seite.locator('[data-test=eingangskorb-zaehler]').count(), 0);
  assert.equal(await this.seite.getByRole('alert').count(), 0);
  assert.deepEqual(this.apiAnfragen, [], 'die PWA hat die API aufgerufen');
});
