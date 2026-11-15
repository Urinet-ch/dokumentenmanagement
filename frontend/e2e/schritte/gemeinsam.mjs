import { After, Before, Then, When, setDefaultTimeout } from '@cucumber/cucumber';
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { archivAttrappe } from '../hilfen/archiv-attrappe.mjs';

const basisUrl = process.env.ARCHIV_URL ?? 'http://localhost:8080';

setDefaultTimeout(30_000);

Before(async function ({ pickle }) {
  // Der Standard (chromium-headless-shell) hat keinen PDF-Viewer und lädt ein <object type=application/pdf> nie; Szenarien
  // mit @pdf-viewer laufen im neuen Headless-Modus des vollen Chromium, der PDFs wie ein Browser einbettet.
  const mitPdfViewer = pickle.tags.some((tag) => tag.name === '@pdf-viewer');
  this.browser = await chromium.launch(mitPdfViewer ? { channel: 'chromium' } : {});
  // Service Worker aus: Seine Anfragen liefen an den Attrappen (page.route) vorbei
  this.kontext = await this.browser.newContext({ locale: 'de-CH', serviceWorkers: 'block' });
  this.seite = await this.kontext.newPage();
  // Eingangskorb und Zähler (Aufgabe 3.6): Das Archiv, das die PWA ausliefert, hat keine Datenbank; ohne Attrappe
  // lehnte es das Token der Keycloak-Attrappe ab, und die PWA beendete nach dem Anmelden die Sitzung.
  await archivAttrappe(this);
});

After(async function () {
  await this.browser?.close();
});

When('ich die Startseite öffne', async function () {
  await this.seite.goto(basisUrl);
});

Then(/^sehe ich den Titel «(.+)»$/, async function (titel) {
  await this.seite.getByText(titel, { exact: true }).first().waitFor();
  assert.equal(await this.seite.title(), titel);
});

Then(/^liefert das Archiv «(.+)» mit Status (\d+)$/, async function (pfad, status) {
  const antwort = await this.seite.request.get(basisUrl + pfad);
  assert.equal(antwort.status(), Number(status));
});

Then(/^liefert der Deep Link «(.+)» die Startseite$/, async function (pfad) {
  const antwort = await this.seite.request.get(basisUrl + pfad);
  assert.equal(antwort.status(), 200);
  assert.match(antwort.headers()['content-type'] ?? '', /text\/html/);
  assert.match(await antwort.text(), /<app-root/);
});
