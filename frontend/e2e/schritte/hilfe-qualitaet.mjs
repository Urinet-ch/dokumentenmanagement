import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import axe from 'axe-core';
import { AUSSTELLER, basisUrl } from '../hilfen/keycloak-attrappe.mjs';

Given(/^das Farbschema ist «(hell|dunkel)»$/, async function (schema) {
  await this.seite.emulateMedia({ colorScheme: schema === 'hell' ? 'light' : 'dark' });
  const origin = new URL(basisUrl).origin;
  this.fremdeAnfragenBeimLaden = [];
  this.seite.on('request', (anfrage) => {
    const url = new URL(anfrage.url());
    const anmeldung = `${AUSSTELLER}/protocol/openid-connect/`;
    if (url.origin !== origin && ![`${anmeldung}auth`, `${anmeldung}token`].includes(url.origin + url.pathname)) {
      this.fremdeAnfragenBeimLaden.push(`${anfrage.method()} ${url.origin}${url.pathname}`);
    }
  });
  await this.kontext.route('**/*', (route) =>
    new URL(route.request().url()).origin === origin ? route.continue() : route.abort('blockedbyclient'),
  );
});

async function pruefen(seite, ort) {
  const ergebnis = await seite.evaluate(async () => ({
    ueberlauf: [document.documentElement, document.querySelector('mat-sidenav-content')]
      .some((e) => e.scrollWidth > e.clientWidth),
    befunde: (await window.axe.run(document)).violations.map(({ id, nodes }) => ({
      id, targets: nodes.map((node) => ({ target: node.target, html: node.html, detail: node.failureSummary })),
    })),
  }));
  assert.equal(ergebnis.ueberlauf, false, `${ort}: waagrechter Bildlauf`);
  assert.deepEqual(ergebnis.befunde, [], `${ort}: ${JSON.stringify(ergebnis.befunde)}`);
}

async function aufzeichnungStarten(welt) {
  const seite = welt.seite;
  await seite.waitForLoadState('networkidle');
  await seite.addScriptTag({ content: axe.source });
  welt.hilfeAnfragen = [];
  seite.on('request', (anfrage) => {
    const url = new URL(anfrage.url());
    if (url.origin !== new URL(basisUrl).origin || url.pathname.startsWith('/api/')) {
      welt.hilfeAnfragen.push(`${anfrage.method()} ${url.origin}${url.pathname}`);
    }
  });
}

async function hilfePruefen(seite) {
  await seite.getByRole('button', { name: 'Hilfe', exact: true }).click();
  const dialog = seite.getByRole('dialog', { name: 'Hilfe', exact: true });
  await dialog.waitFor();
  await pruefen(seite, 'Hilfe-Leiste auf der Startseite');
  await dialog.getByRole('button', { name: 'Schliessen', exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  await seite.evaluate(() => {
    history.pushState(null, '', '/hilfe');
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  });
  await seite.locator('app-hilfe-seite app-anleitung-text').first().waitFor();
  await seite.waitForFunction(() => !document.querySelector('app-hilfe-seite mat-progress-bar'));
  await pruefen(seite, 'Ganze Anleitung');

  await seite.getByRole('button', { name: 'Hilfe', exact: true }).click();
  await dialog.waitFor();
  const navigation = dialog.getByRole('navigation', { name: 'Inhalt der Anleitung' });
  const links = await navigation.getByRole('link').allTextContents();
  await pruefen(seite, 'Hilfe-Leiste über der Anleitung');
  for (const titel of links) {
    await navigation.getByRole('link', { name: titel.trim(), exact: true }).click();
    await dialog.getByRole('heading', { name: titel.trim(), level: 3, exact: true }).waitFor();
    await seite.waitForFunction(() => !document.querySelector('app-hilfe-leiste mat-progress-bar'));
    await pruefen(seite, `Hilfe-Leiste: ${titel.trim()}`);
  }
  await dialog.getByRole('button', { name: 'Schliessen', exact: true }).click();
  await seite.waitForLoadState('networkidle');
}

When('ich Einführung und Hilfe auf Barrierefreiheit und Datenschutz prüfe', { timeout: 240_000 }, async function () {
  const seite = this.seite;
  const karte = seite.locator('app-start .einfuehrung');
  await karte.waitFor();
  await aufzeichnungStarten(this);
  while (true) {
    await pruefen(seite, 'Einführung');
    const weiter = karte.getByRole('button', { name: 'Weiter', exact: true });
    if (!(await weiter.count())) break;
    await weiter.click();
  }
  await karte.getByRole('button', { name: 'Fertig', exact: true }).click();
  await karte.waitFor({ state: 'hidden' });
  const vermerk = await seite.evaluate(() => JSON.parse(localStorage.getItem('archiv.einfuehrung')));
  const kennung = createHash('sha256')
    .update(`${this.keycloak.benutzer.mandant}:${this.keycloak.benutzer.id}`)
    .digest('hex');
  assert.deepEqual(Object.keys(vermerk).sort(), ['eintraege', 'v']);
  assert.equal(vermerk.v, 1);
  assert.equal(vermerk.eintraege.length, 1);
  const eintrag = vermerk.eintraege[0];
  assert.deepEqual(Object.keys(eintrag).sort(), ['am', 'art', 'k', 'version']);
  assert.equal(eintrag.k, kennung);
  assert.equal(eintrag.art, 'ABGESCHLOSSEN');
  assert.equal(eintrag.version, 1);
  assert.match(eintrag.am, /^\d{4}-\d{2}-\d{2}$/);
  await hilfePruefen(seite);
});

When('ich die Hilfe ohne Anmeldung auf Barrierefreiheit und Datenschutz prüfe', { timeout: 240_000 }, async function () {
  await aufzeichnungStarten(this);
  await hilfePruefen(this.seite);
});

When('ich die Hilfe wiederholt sofort mit Escape schliesse', async function () {
  const knopf = this.seite.getByRole('button', { name: 'Hilfe', exact: true });
  for (let i = 0; i < 10; i++) {
    await knopf.click();
    await this.seite.getByRole('dialog', { name: 'Hilfe', exact: true }).waitFor();
    await this.seite.waitForFunction(() => document.activeElement?.id === 'hilfe-leiste-titel');
    await this.seite.keyboard.press('Escape');
    await this.seite.waitForFunction(() =>
      !document.querySelector('mat-sidenav-content').inert &&
      document.activeElement?.matches('button[aria-label="Hilfe"]'),
    );
  }
});

Then('meldet axe keinen Befund und die Hilfe sendet keine zusätzlichen API- oder fremden Anfragen', function () {
  assert.deepEqual(this.hilfeAnfragen, []);
  assert.deepEqual(this.fremdeAnfragenBeimLaden, []);
});
