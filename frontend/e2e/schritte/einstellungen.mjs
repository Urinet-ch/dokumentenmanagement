import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import axe from 'axe-core';

const PFAD = '/api/v1/mandant/einstellungen';
const KATALOG = [{
  gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat-Aufbewahrung',
  beschreibung: 'Aufbewahrung der Chatnachrichten', typ: 'GANZZAHL', aktuellerWert: 12, standard: 12,
  minimum: 1, maximum: 60, einheit: 'Monate', wirksamAb: 'Beim nächsten Aufräumlauf',
}, {
  gruppe: 'BRANDING', schluessel: 'branding.name', name: 'Name', beschreibung: 'Name des Archivs',
  typ: 'TEXT', aktuellerWert: 'Beispielarchiv', standard: 'Archiv', wirksamAb: 'Nach dem Speichern',
}, {
  gruppe: 'KENNUNGEN', schluessel: 'kennungen.ibans', name: 'IBANs', beschreibung: 'Eigene Kontoverbindungen',
  typ: 'IBANS', aktuellerWert: [], standard: [], wirksamAb: 'Bei der nächsten Erfassung',
}];

Given('die Einstellungen liefern synthetische Daten', async function () {
  this.einstellungenAnfragen = [];
  await this.seite.route((url) => url.pathname === PFAD || url.pathname === `${PFAD}/vorschau`
    || url.pathname === '/api/v1/konfiguration/versionen', async (route) => {
    const anfrage = route.request();
    const url = new URL(anfrage.url());
    if (url.pathname === '/api/v1/konfiguration/versionen') {
      return route.fulfill({ json: [] });
    }
    if (anfrage.method() === 'GET') {
      return route.fulfill({ json: { version: 3, katalog: KATALOG }, headers: { ETag: '"3"' } });
    }
    if (url.pathname.endsWith('/vorschau')) {
      return route.fulfill({ json: { version: 3, auswirkungen: [
        { schluessel: 'chat.aufbewahrungMonate', text: 'Ältere Nachrichten werden gelöscht.' },
      ] } });
    }
    this.einstellungenAnfragen.push({ body: anfrage.postDataJSON(), headers: anfrage.headers(),
      bestaetigt: url.searchParams.get('bestaetigt') });
    if (this.einstellungenAnfragen.length === 1) {
      return route.fulfill({ status: 401, json: { fehlercode: 'STEP_UP_NOETIG' } });
    }
    return route.fulfill({ json: { version: 4, geaendert: true, auswirkungen: [] } });
  });
});

Then('sind die Einstellungen ohne Überlauf und axe-Befund sichtbar', async function () {
  await this.seite.getByRole('heading', { name: 'Chat-Aufbewahrung' }).waitFor();
  await this.seite.addScriptTag({ content: axe.source });
  const ergebnis = await this.seite.evaluate(async () => ({
    ueberlauf: [document.documentElement, document.querySelector('mat-sidenav-content')]
      .filter(Boolean).some((element) => element.scrollWidth > element.clientWidth),
    befunde: (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    })).violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) })),
  }));
  assert.equal(ergebnis.ueberlauf, false);
  assert.deepEqual(ergebnis.befunde, []);
  if (process.env.EINSTELLUNGEN_BILDER) {
    const breite = this.seite.viewportSize().width;
    const schema = await this.seite.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches ? 'dunkel' : 'hell');
    await this.seite.screenshot({ path: join(process.env.EINSTELLUNGEN_BILDER, `einstellungen-${breite}-${schema}.png`),
      fullPage: true });
  }
});

When('ich die Chat-Aufbewahrung prüfe und bestätige', async function () {
  await this.seite.getByLabel('Neuer Wert: Chat-Aufbewahrung').fill('6');
  await this.seite.getByRole('button', { name: 'Änderungen prüfen' }).click();
  await this.seite.getByText('Ältere Nachrichten werden gelöscht.').waitFor();
  await this.seite.getByRole('button', { name: 'Änderungen speichern' }).click();
  await this.seite.getByRole('dialog').getByText('Ältere Nachrichten werden gelöscht.').waitFor();
  await this.seite.getByRole('button', { name: 'Bestätigen und speichern' }).click();
});

Then('wird die Änderung nach Step-up genau einmal wiederholt', async function () {
  await this.seite.getByText('Die Einstellungen wurden gespeichert.').waitFor();
  assert.equal(this.einstellungenAnfragen.length, 2);
  for (const anfrage of this.einstellungenAnfragen) {
    assert.deepEqual(anfrage.body, { chat: { aufbewahrungMonate: 6 } });
    assert.equal(anfrage.headers['if-match'], '"3"');
    assert.equal(anfrage.bestaetigt, 'chat.aufbewahrungMonate');
  }
  assert.equal(this.keycloak.pkce.length, 2);
  assert.ok(this.keycloak.pkce.every(Boolean));
});
