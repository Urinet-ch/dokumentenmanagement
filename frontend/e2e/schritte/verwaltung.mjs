import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';

const ETAG = '"konfig-7"';
const ART = {
  code: 'RECHNUNG',
  name: 'Rechnung',
  gruppen: ['finanz'],
  pflichtfelder: ['belegdatum'],
  zusatzSchema: { type: 'object', properties: {}, additionalProperties: false },
  vertraulichkeit: 'NORMAL',
  aufbewahrungJahre: 10,
  aufbewahrungAb: 'GESCHAEFTSJAHR',
  gesetzlichePflicht: true,
  aktiv: true,
  selbstAusgestellt: false,
  belegnummerMuster: [],
  kiVorschlag: false,
  kiFelder: [],
  reihenfolge: 1,
};
const REGELN = [
  {
    code: 'test_regel',
    art: 'RECHNUNG',
    typ: 'EINDEUTIG',
    parameter: { felder: ['titel'] },
    schwere: 'WARNUNG',
    meldung: 'Titel muss eindeutig sein.',
    aktiv: true,
  },
];

// Auswahlfelder über ihre Rolle: getByLabel findet ein <select> im umschliessenden <label> nicht zuverlässig.
function json(route, status, body, etag = ETAG) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    headers: { ETag: etag },
    body: JSON.stringify(body),
  });
}

Given('die Verwaltungsseite liefert synthetische Konfigurationsdaten', async function () {
  this.verwaltung = { aenderungen: [] };
  await this.seite.route('**/api/v1/**', async (route) => {
    const anfrage = route.request();
    const url = new URL(anfrage.url());
    const methode = anfrage.method();
    if (methode === 'GET' && url.pathname === '/api/v1/dokumentarten') {
      return json(route, 200, [ART]);
    }
    if (methode === 'GET' && url.pathname === '/api/v1/klassifikationen') {
      return json(route, 200, []);
    }
    if (methode === 'GET' && url.pathname === '/api/v1/regeln') {
      return json(route, 200, REGELN);
    }
    if (methode === 'GET' && url.pathname === '/api/v1/konfiguration/versionen') {
      return json(route, 200, [
        {
          version: 7,
          zeit: '2026-11-19T08:00:00Z',
          wer: 'Inhaber',
          quelle: 'APP',
          sha256: 'a'.repeat(64),
        },
      ]);
    }
    if (
      methode === 'POST' &&
      ['/api/v1/regeln', '/api/v1/dokumentarten'].includes(url.pathname)
    ) {
      const body = anfrage.postDataJSON();
      this.verwaltung.aenderungen.push({
        pfad: url.pathname,
        ifMatch: await anfrage.headerValue('if-match'),
        body,
      });
      return json(
        route,
        201,
        {
          ...body,
          betroffeneDokumente: url.pathname === '/api/v1/regeln' ? 2 : undefined,
        },
        '"konfig-8"',
      );
    }
    return route.fallback();
  });
});

Given('die Verwaltungsseite wird auf 375 px geprüft', async function () {
  await this.seite.setViewportSize({ width: 375, height: 812 });
});

When(
  /^ich die neue Regel «(.+)» mit der Meldung «(.+)» anlege$/,
  async function (code, meldung) {
    const regeln = this.seite.locator('#regeln');
    await regeln.getByRole('button', { name: 'Neue Regel', exact: true }).click();
    await regeln.getByLabel('Code', { exact: true }).fill(code);
    await regeln
      .getByRole('combobox', { name: 'Dokumentart (leer = alle)', exact: true })
      .selectOption('RECHNUNG');
    await regeln.getByRole('combobox', { name: 'Regeltyp', exact: true }).selectOption('EINDEUTIG');
    await regeln.getByLabel('Parameter (JSON)').fill('{"felder":["titel"]}');
    await regeln.getByLabel('Meldung', { exact: true }).fill(meldung);
    await regeln.getByRole('button', { name: 'Änderungen speichern', exact: true }).click();
  },
);

When(
  /^ich die Dokumentart «(.+)» mit dem Auswahlfeld «(.+)» anlege$/,
  async function (code, feldcode) {
    const dokumentarten = this.seite.locator('#dokumentarten');
    await dokumentarten
      .getByRole('button', { name: 'Neue Dokumentart', exact: true })
      .click();
    await dokumentarten.getByLabel('Code', { exact: true }).fill(code);
    await dokumentarten.getByLabel('Name', { exact: true }).fill('Testart');
    await dokumentarten
      .getByRole('button', { name: 'Zusatzfeld hinzufügen', exact: true })
      .click();
    const feld = dokumentarten.locator('.feld-editor');
    await feld.getByLabel('Feldcode', { exact: true }).fill(feldcode);
    await feld.getByLabel('Name', { exact: true }).fill('Priorität');
    await feld.getByRole('combobox', { name: 'Typ', exact: true }).selectOption('auswahl');
    await feld.getByLabel('Pflicht', { exact: true }).check();
    await feld.getByRole('button', { name: 'Wert hinzufügen', exact: true }).click();
    await feld.getByLabel('Wert', { exact: true }).fill('hoch');
    await feld.getByLabel('Anzeigetext', { exact: true }).fill('Hoch');
    await dokumentarten.getByRole('button', { name: 'Änderungen speichern', exact: true }).click();
  },
);

Then('wurde die Regel mit dem aktuellen ETag gespeichert', function () {
  assert.equal(this.verwaltung.aenderungen.length, 1);
  const [aenderung] = this.verwaltung.aenderungen;
  assert.equal(aenderung.pfad, '/api/v1/regeln');
  assert.equal(aenderung.ifMatch, ETAG);
  assert.deepEqual(aenderung.body.parameter, { felder: ['titel'] });
});

Then('wurde die Dokumentart mit dem aktuellen ETag gespeichert', function () {
  assert.equal(this.verwaltung.aenderungen.length, 1);
  const [aenderung] = this.verwaltung.aenderungen;
  assert.equal(aenderung.pfad, '/api/v1/dokumentarten');
  assert.equal(aenderung.ifMatch, ETAG);
});

Then('zeigt die Verwaltungsseite zwei betroffene Dokumente', async function () {
  // Die Erfolgsmeldung nennt zuerst «Regel gespeichert.», danach die Anzahl
  await this.seite
    .getByRole('status')
    .filter({ hasText: '2 bestätigte Dokumente mit Regelverstössen' })
    .waitFor();
});

Then('enthält das gesendete Zusatz-Schema die Werte und Beschriftungen des Feld-Editors', function () {
  const aenderung = this.verwaltung.aenderungen[0];
  assert.deepEqual(aenderung.body.zusatzSchema, {
    type: 'object',
    properties: {
      prioritaet: {
        title: 'Priorität',
        type: 'string',
        enum: ['hoch'],
        'x-labels': { hoch: 'Hoch' },
      },
    },
    required: ['prioritaet'],
  });
});

Then('scrollt die Verwaltungsseite nicht horizontal', async function () {
  const breite = await this.seite.evaluate(() => ({
    inhalt: document.documentElement.scrollWidth,
    fenster: document.documentElement.clientWidth,
  }));
  assert.ok(breite.inhalt <= breite.fenster, JSON.stringify(breite));
});
