import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';

const selektoren = {
  Warteschlange: '.schlange > a[href="/hilfe#erfassen-offline"]',
  Uploadfehler: 'app-upload a[href="/hilfe#erfassen-offline"]',
  Anmeldekarte: '.willkommen a[href="/hilfe#erfassen-offline"]',
  Zugang: 'app-start a[href="/hilfe#probleme"]',
};

Given('der Browser meldet keine Verbindung', async function () {
  await this.kontext.addInitScript(() => Object.defineProperty(navigator, 'onLine', { get: () => false }));
});

When('ein synthetischer Datei-Upload fehlschlägt', async function () {
  await this.seite.route('**/api/v1/dokumente', (route) =>
    route.fulfill({ status: 503, contentType: 'application/problem+json', body: '{}' }),
  );
  await this.seite.locator('app-upload input[type=file]').setInputFiles({
    name: 'test.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4'),
  });
  await this.seite.getByRole('button', { name: 'Hochladen', exact: true }).click();
  await this.seite.locator('app-upload [role=alert]').waitFor();
});

When(/^ich den Kontextlink bei «(.+)» wähle$/, async function (ort) {
  assert.ok(selektoren[ort], `Unbekannter Ort: ${ort}`);
  const link = this.seite.locator(selektoren[ort]);
  assert.equal((await link.innerText()).trim(), 'So funktioniert es');
  await link.click();
  await this.seite.getByRole('dialog', { name: 'Hilfe', exact: true }).waitFor();
});

Then(/^ist der Kontextlink bei «(.+)» wieder fokussiert$/, async function (ort) {
  assert.ok(selektoren[ort], `Unbekannter Ort: ${ort}`);
  await this.seite.waitForFunction((selektor) => document.activeElement?.matches(selektor), selektoren[ort]);
});

Then('bleibt die synthetische Datei ausgewählt', async function () {
  assert.deepEqual(await this.seite.locator('app-upload .datei-name').allTextContents(), ['test.pdf']);
});
