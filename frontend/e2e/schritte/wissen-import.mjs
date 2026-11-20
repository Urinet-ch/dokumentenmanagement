import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';

Given('ein synthetischer Wissenspaketimport mit Ergebnis {string} ist verfügbar', async function (ergebnis) {
  this.importPosts = [];
  const ansicht = {
    entwurf: { id: 'import1', anlass: 'IMPORT', status: 'OFFEN', erstelltVon: 'synthetischer-importeur',
      erstellt: '2026-11-20T10:00:00Z', herkunft: { paketSha256: 'a'.repeat(64), unveraendert: 3, ignoriert: 4,
        kopf: { erzeugtVon: 'synthetisches-modell', erzeugtMit: 'lokal', kontextStufe: 'INTERN',
          erzeugtAm: '2026-11-20T09:00:00Z' } } },
    kernaussagen: [],
    aenderungen: [{ nr: 1, pfad: 'konzepte/probe', etag: 7, status: 'OFFEN', vertraulichkeitKontext: 'INTERN',
      daten: { titel: 'Probe', beschreibung: 'Synthetische Seite.', tags: [], quellen: [],
        quellenPruefung: [{ schluessel: 'q1', zuordnung: 'ZUGEORDNET', auszug: 'NICHT_GEFUNDEN' }] },
      inhalt: 'Synthetische Fassung.', diff: {}, befunde: [], hinweise: [],
      widersprueche: [], entfernteAussagen: [] }],
  };
  await this.seite.route((url) => url.pathname.startsWith('/api/v1/wissensentwuerfe'), async (route) => {
    const request = route.request();
    const pfad = new URL(request.url()).pathname;
    if (request.method() === 'GET') {
      await route.fulfill({ json: pfad.endsWith('/import1') ? ansicht : [] });
      return;
    }
    this.importPosts.push(pfad);
    if (pfad.endsWith('/neu-pruefen')) {
      ansicht.aenderungen[0].etag = 8;
      ansicht.aenderungen[0].daten.quellenPruefung[0].auszug = 'GEFUNDEN';
      await route.fulfill({ status: 204 });
      return;
    }
    assert.equal(pfad, '/api/v1/wissensentwuerfe/import', 'Unerwarteter Einzelentscheid oder KI-Aufruf');
    assert.match(request.headers()['content-type'], /^multipart\/form-data; boundary=/);
    assert.match(request.postData(), /name="paket"; filename="probe.zip"/);
    if (ergebnis === '422') {
      await route.fulfill({ status: 422, json: { detail: 'Paket ungültig.',
        befunde: [{ code: 'FRONTMATTER_UNGUELTIG', pfad: 'konzepte/probe.md' }] } });
    } else {
      await route.fulfill({ status: 201, json: { entwurfId: 'import1', status: 'OFFEN', paketSha256: 'a'.repeat(64),
        anzahl: { neu: 1, aendern: 2, unveraendert: 3, ignoriert: 4 },
        befunde: [{ code: 'AUSZUG_NICHT_GEFUNDEN', pfad: 'konzepte/probe.md' }], hinweise: [] } });
    }
  });
});

When('ich auf 375 Pixeln ein Wissenspaket hochlade', async function () {
  await this.seite.setViewportSize({ width: 375, height: 900 });
  await this.seite.getByRole('button', { name: 'Menü öffnen' }).click();
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' })
    .getByRole('link', { name: /^Wissensentwürfe/ }).click();
  await this.seite.getByRole('link', { name: 'Wissenspaket importieren', exact: true }).click();
  await this.seite.locator('input[type="file"]').setInputFiles({
    name: 'probe.zip', mimeType: 'application/zip', buffer: Buffer.from('Synthetisches Paket ohne Clientparser'),
  });
  await this.seite.getByRole('button', { name: 'Als Entwurf importieren' }).click();
});

Then('sehe ich Importzähler und Seitenbefunde und kann Quellen ohne KI erneut prüfen', async function () {
  await this.seite.getByRole('heading', { name: 'Prüfbericht' }).waitFor();
  assert.deepEqual(await this.seite.locator('.anzahlen dd').allTextContents(), ['1', '2', '3', '4']);
  await this.seite.getByText('konzepte/probe.md · AUSZUG_NICHT_GEFUNDEN').waitFor();
  await importBarrierefreiheit(this.seite);
  await this.seite.getByRole('link', { name: 'Entwurf prüfen', exact: true }).click();
  await this.seite.getByText('synthetisches-modell', { exact: true }).waitFor();
  await this.seite.getByText(/q1.*Auszug.*Nicht gefunden/).waitFor();
  await this.seite.getByRole('button', { name: 'Prüfung wiederholen' }).click();
  await this.seite.getByText(/q1.*Auszug.*Gefunden/).waitFor();
  await importBarrierefreiheit(this.seite);
  assert.deepEqual(this.importPosts,
    ['/api/v1/wissensentwuerfe/import', '/api/v1/wissensentwuerfe/import1/neu-pruefen']);
});

Then('sehe ich den Paketfehler mit Seitenpfad ohne Entwurf und ohne Wiederholung', async function () {
  await this.seite.getByText('konzepte/probe.md · FRONTMATTER_UNGUELTIG').waitFor();
  assert.equal(await this.seite.getByRole('heading', { name: 'Prüfbericht' }).count(), 0);
  assert.deepEqual(this.importPosts, ['/api/v1/wissensentwuerfe/import']);
  await importBarrierefreiheit(this.seite);
});

Then('ist der Wissenspaketimport weder verlinkt noch über die Route erreichbar', async function () {
  assert.equal(await this.seite.getByRole('link', { name: /^Wissensentwürfe/ }).count(), 0);
  await this.seite.evaluate(() => {
    history.pushState({}, '', '/wissen/entwuerfe/import');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await this.seite.waitForURL((url) => url.pathname === '/');
  assert.equal(await this.seite.locator('input[type="file"]').count(), 0);
  assert.deepEqual(this.importPosts, []);
});

async function importBarrierefreiheit(seite) {
  await seite.addScriptTag({ content: axe.source });
  const ergebnis = await seite.evaluate(async () => ({
    ueberlauf: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    befunde: (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    })).violations.map(({ id, nodes }) => ({ id, targets: nodes.map((n) => n.target) })),
  }));
  assert.equal(ergebnis.ueberlauf, false);
  assert.deepEqual(ergebnis.befunde, []);
}
