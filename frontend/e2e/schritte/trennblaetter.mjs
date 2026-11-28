// Seite «Trennblätter» (Spec 09, Aufgabe 27.5) gegen eine Attrappe von GET /dokumentarten und POST /trennblaetter.
// Die Attrappe hält fest, was die PWA sendet, und antwortet mit einem kleinen PDF; das echte PDF prüfen die
// Java-Szenarien (features/produktivimport/trennblaetter.feature). Alle Daten sind synthetisch.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';
import { ohneUeberlaufUndAxe } from './mein-konto.mjs';

const origin = new URL(basisUrl).origin;
const ARTEN = [
  { code: 'QUITTUNG', name: 'Quittung / Spesen', aktiv: true },
  { code: 'BANK', name: 'Bank', aktiv: true },
  { code: 'ALT', name: 'Alte Art', aktiv: false },
];

function attrappe(welt) {
  assert.ok(welt.trennblaetter, 'die Trennblatt-Schnittstelle ist keine Attrappe');
  return welt.trennblaetter;
}

Given('die Trennblatt-Schnittstelle ist eine Attrappe', async function () {
  this.trennblaetter = { anfragen: [], download: null };
  this.seite.on('download', (download) => {
    this.trennblaetter.download = download;
  });
  await this.seite.route(
    (url) => url.origin === origin && ['/api/v1/dokumentarten', '/api/v1/trennblaetter'].includes(url.pathname),
    async (route) => {
      const anfrage = route.request();
      const pfad = new URL(anfrage.url()).pathname;
      this.trennblaetter.anfragen.push(`${anfrage.method()} ${pfad}`);
      if (!(await anfrage.headerValue('authorization'))?.startsWith('Bearer ')) {
        return route.fulfill({ status: 401, contentType: 'application/problem+json', body: '{"fehlercode":"NICHT_ANGEMELDET"}' });
      }
      if (pfad === '/api/v1/dokumentarten') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ARTEN) });
      }
      this.trennblaetter.body = anfrage.postDataJSON();
      return route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        headers: { 'content-disposition': 'attachment; filename="trennblaetter.pdf"', 'cache-control': 'no-store' },
        body: Buffer.from('%PDF-1.7\n%%EOF\n'),
      });
    },
  );
});

When(/^ich für «(.+)» (\d+) Trennblätter wähle$/, async function (name, anzahl) {
  await this.seite.getByLabel(`Anzahl Blätter für ${name}`, { exact: true }).fill(String(anzahl));
});

When(/^ich das Ordner-Deckblatt «(.+)» hinzufüge$/, async function (titel) {
  await this.seite.getByLabel('Titel des Deckblatts').fill(titel);
  await this.seite.getByRole('button', { name: 'Deckblatt hinzufügen' }).click();
});

Then(/^zeigt die Seite «Trennblätter» die Arten «(.+)»$/, async function (liste) {
  const zeilen = this.seite.locator('[data-test="trenn-art"]');
  await zeilen.first().waitFor();
  assert.deepEqual(await zeilen.evaluateAll((z) => z.map((e) => e.getAttribute('data-art'))), liste.split(', '));
});

Then(/^hat das Archiv diese Trennblätter erhalten «(.+)»$/, async function (erwartet) {
  const tb = attrappe(this);
  for (let i = 0; i < 100 && !tb.body; i++) {
    await this.seite.waitForTimeout(100);
  }
  assert.ok(tb.body, 'keine Anfrage an /trennblaetter');
  const ist = tb.body.blaetter.map((b) => [b.typ, b.art ?? b.titel ?? '-', b.anzahl].join(' ')).join(', ');
  assert.equal(ist, erwartet);
});

Then(/^lädt der Browser das PDF «(.+)» herunter$/, async function (name) {
  const tb = attrappe(this);
  for (let i = 0; i < 100 && !tb.download; i++) {
    await this.seite.waitForTimeout(100);
  }
  assert.ok(tb.download, 'kein Download');
  assert.equal(tb.download.suggestedFilename(), name);
});

Then('ist die Seite «Trennblätter» ohne Überlauf und axe-Befund', async function () {
  await ohneUeberlaufUndAxe(this, 'trennblaetter', process.env.TRENNBLAETTER_BILDER);
});

Then('hat die PWA die Trennblatt-Schnittstelle nicht aufgerufen', function () {
  assert.deepEqual(attrappe(this).anfragen.filter((a) => a.endsWith('/trennblaetter')), []);
});
