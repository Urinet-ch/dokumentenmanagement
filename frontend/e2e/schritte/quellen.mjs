import { Given, Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';
import { ohneUeberlaufUndAxe } from './mein-konto.mjs';

const origin = new URL(basisUrl).origin;
const LAUF = {
  id: '00000000-0000-4000-8000-000000000001',
  quelle: 'probe',
  art: 'TROCKEN',
  status: 'FERTIG',
  zaehler: {
    gefunden: 8,
    neu: 3,
    dublettePruefsumme: 2,
    dubletteFachlich: 0,
    uebersprungen: 2,
    fehler: 1,
  },
  meldungen: [],
  gestartet: '2026-11-28T10:00:00Z',
  beendet: '2026-11-28T10:01:00Z',
};
const QUELLE = {
  code: 'probe',
  name: 'Synthetische Ablage',
  typ: 'DRIVE',
  konfiguration: {},
  status: 'OFFEN',
  bemerkung: 'Synthetische Browserprobe',
  geheimnisGesetzt: false,
  version: 3,
  letzterLauf: LAUF,
};

Given('die Quellen-Schnittstelle ist eine Attrappe', async function () {
  this.quellen = { anfragen: [] };
  await this.seite.route(
    (url) => url.origin === origin && url.pathname.startsWith('/api/v1/quellen'),
    async (route) => {
      const anfrage = route.request();
      const url = new URL(anfrage.url());
      this.quellen.anfragen.push(`${anfrage.method()} ${url.pathname}`);
      if (!(await anfrage.headerValue('authorization'))?.startsWith('Bearer ')) {
        return route.fulfill({
          status: 401,
          contentType: 'application/problem+json',
          body: '{"fehlercode":"NICHT_ANGEMELDET"}',
        });
      }
      if (anfrage.method() === 'GET' && url.pathname === '/api/v1/quellen') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([QUELLE]),
        });
      }
      if (
        anfrage.method() === 'GET' &&
        url.pathname === '/api/v1/quellen/probe/laeufe'
      ) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([LAUF]),
        });
      }
      return route.fallback();
    },
  );
});

Then('sehe ich die synthetische Quelle mit Quelllauf-Zählern', async function () {
  const quelle = this.seite.locator('[data-test="quelle-probe"]');
  await quelle.getByRole('heading', { name: 'Synthetische Ablage', exact: true }).waitFor();
  assert.match(await quelle.locator('.zaehler').innerText(), /8[\s\S]*3[\s\S]*2/);
  assert.equal(await quelle.locator('tbody tr').count(), 1);
});

Then('kann ich Quellen pflegen', async function () {
  await this.seite.getByRole('button', { name: 'Quelle hinzufügen', exact: true }).waitFor();
  await this.seite.getByRole('button', { name: 'Quelle bearbeiten', exact: true }).waitFor();
  await this.seite.getByRole('button', { name: 'Trockenlauf starten', exact: true }).waitFor();
  await this.seite.getByLabel('Geheimnis', { exact: true }).waitFor();
});

Then('kann ich Quellen nicht pflegen', async function () {
  assert.equal(await this.seite.getByRole('button', { name: 'Quelle hinzufügen', exact: true }).count(), 0);
  assert.equal(await this.seite.getByRole('button', { name: 'Quelle bearbeiten', exact: true }).count(), 0);
  assert.equal(await this.seite.getByRole('button', { name: 'Trockenlauf starten', exact: true }).count(), 0);
  assert.equal(await this.seite.locator('input[type="password"]').count(), 0);
});

Then('ist die Seite «Quellen» ohne Überlauf und axe-Befund', async function () {
  await ohneUeberlaufUndAxe(this, 'quellen', process.env.QUELLEN_BILDER);
});

Then('hat die PWA das Quellenregister nicht abgerufen', function () {
  assert.deepEqual(this.quellen.anfragen, []);
});
