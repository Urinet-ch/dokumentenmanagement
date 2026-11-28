import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { AUSSTELLER } from '../hilfen/keycloak-attrappe.mjs';
import { ohneUeberlaufUndAxe } from './mein-konto.mjs';

const KOPF_EINSTIEG = (seite, name) => seite.locator('header.kopf').getByRole('link', { name: new RegExp(`^${name}`) });
const KONTO_EINSTIEG = (seite, name) => seite.locator('app-konto-seite').getByRole('link', { name: new RegExp(`^${name}`) });

Given('die Kontoverwaltung des Realms liefert eine synthetische Seite', async function () {
  this.kontoAnfragen = [];
  // Später registrierte Routen gehen vor, darum erst nach der Anmeldung: die Kontoverwaltung ist nur eine Attrappe, nie das echte Keycloak.
  const attrappe = (route) => {
    this.kontoAnfragen.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Kontoverwaltung</title><p>Kontoverwaltung</p>' });
  };
  await this.kontext.route(`${AUSSTELLER}/account`, attrappe);
  await this.kontext.route(`${AUSSTELLER}/account/**`, attrappe);
});

Then(/^sehe ich den Einstieg «(.+)» auf «Mein Konto» ohne Überlauf und axe-Befund$/, async function (name) {
  const link = KONTO_EINSTIEG(this.seite, name);
  await link.waitFor();
  assert.equal(await link.getAttribute('href'), `${AUSSTELLER}/account/account-security/signing-in`);
  assert.equal(await link.getAttribute('target'), '_blank');
  assert.equal(await link.getAttribute('rel'), 'noopener noreferrer');
  await ohneUeberlaufUndAxe(this, 'passkey-einstieg', process.env.PASSKEY_BILDER);
});

When(/^ich den Einstieg «(.+)» auf «Mein Konto» wähle$/, async function (name) {
  const neuerTab = this.kontext.waitForEvent('page');
  await KONTO_EINSTIEG(this.seite, name).click();
  this.neuerTab = await neuerTab;
});

Then(/^zeigt ein neuer Tab die Kontoverwaltung des Realms unter «(.+)»$/, async function (pfad) {
  await this.neuerTab.waitForLoadState();
  assert.equal(this.neuerTab.url(), `${AUSSTELLER}/account/${pfad}`);
  assert.equal(await this.neuerTab.title(), 'Kontoverwaltung');
  assert.equal(await this.neuerTab.evaluate(() => window.opener), null, 'der neue Tab darf die App nicht erreichen');
  await this.neuerTab.close();
});

Then(/^fehlt der Einstieg «(.+)» in der Kopfzeile$/, async function (name) {
  await this.seite.locator('header.kopf').waitFor();
  assert.equal(await KOPF_EINSTIEG(this.seite, name).count(), 0);
});
