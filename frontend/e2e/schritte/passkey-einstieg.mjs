import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';
import { AUSSTELLER } from '../hilfen/keycloak-attrappe.mjs';

const EINSTIEG = (seite, name) => seite.locator('header.kopf').getByRole('link', { name: new RegExp(`^${name}`) });

Given('die Kontoverwaltung des Realms liefert eine synthetische Seite', async function () {
  this.kontoAnfragen = [];
  // Später registrierte Routen gehen vor, darum erst nach der Anmeldung: die Kontoverwaltung ist nur eine Attrappe, nie das echte Keycloak.
  await this.kontext.route(`${AUSSTELLER}/account`, (route) => {
    this.kontoAnfragen.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Kontoverwaltung</title><p>Kontoverwaltung</p>' });
  });
});

Then(/^sehe ich den Einstieg «(.+)» in der Kopfzeile ohne Überlauf und axe-Befund$/, async function (name) {
  const link = EINSTIEG(this.seite, name);
  await link.waitFor();
  assert.equal(await link.getAttribute('href'), `${AUSSTELLER}/account`);
  assert.equal(await link.getAttribute('target'), '_blank');
  assert.equal(await link.getAttribute('rel'), 'noopener noreferrer');
  await this.seite.waitForTimeout(250);
  await this.seite.addScriptTag({ content: axe.source });
  const ergebnis = await this.seite.evaluate(async () => ({
    ueberlauf: [document.documentElement, document.querySelector('mat-sidenav-content')]
      .filter(Boolean).some((element) => element.scrollWidth > element.clientWidth),
    befunde: (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    })).violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) })),
  }));
  assert.equal(ergebnis.ueberlauf, false, 'horizontaler Überlauf');
  assert.deepEqual(ergebnis.befunde, []);
  if (process.env.PASSKEY_BILDER) {
    const breite = this.seite.viewportSize().width;
    const schema = await this.seite.evaluate(() => (matchMedia('(prefers-color-scheme: dark)').matches ? 'dunkel' : 'hell'));
    await this.seite.screenshot({ path: `${process.env.PASSKEY_BILDER}/passkey-einstieg-${breite}-${schema}.png` });
  }
});

When(/^ich den Einstieg «(.+)» in der Kopfzeile wähle$/, async function (name) {
  const neuerTab = this.kontext.waitForEvent('page');
  await EINSTIEG(this.seite, name).click();
  this.neuerTab = await neuerTab;
});

Then('zeigt ein neuer Tab die Kontoverwaltung des Realms', async function () {
  await this.neuerTab.waitForLoadState();
  assert.equal(this.neuerTab.url(), `${AUSSTELLER}/account`);
  assert.equal(await this.neuerTab.title(), 'Kontoverwaltung');
  assert.equal(await this.neuerTab.evaluate(() => window.opener), null, 'der neue Tab darf die App nicht erreichen');
  await this.neuerTab.close();
});

Then(/^fehlt der Einstieg «(.+)» in der Kopfzeile$/, async function (name) {
  await this.seite.locator('header.kopf').waitFor();
  assert.equal(await EINSTIEG(this.seite, name).count(), 0);
});
