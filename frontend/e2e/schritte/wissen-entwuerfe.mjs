import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';

Given('synthetische Wissensentwürfe sind für die Oberfläche verfügbar', async function () {
  this.wissensEntscheide = [];
  const kopf = { id: 'e1', status: 'OFFEN', anlass: 'INGEST', erstellt: '2026-01-01T10:00:00Z' };
  const widerspruch = { text: 'Synthetischer Widerspruch', fussnoten: ['alt', 'neu'] };
  const ansicht = {
    entwurf: kopf,
    abschnittFehler: [{ nummer: 1, seite: 1, verworfen: 2, behalten: 1, antwortUngueltig: false }],
    kernaussagen: [{ nr: 1, schluessel: 'q1', text: 'Synthetische Kernaussage', seiten: [1],
      auszug: 'Synthetischer Auszug', belegt: true, zugeordnet: false }],
    aenderungen: [{
      nr: 2, pfad: 'konzepte/probe', status: 'OFFEN', etag: 7, vertraulichkeitKontext: 'INTERN',
      daten: { titel: 'Probe', beschreibung: 'Eine Probe.', tags: [], quellen: [] }, inhalt: 'Neue Fassung.',
      diff: { zeilen: [{ alt: ['Alte Zeile ' + 'a'.repeat(400)], neu: ['Neue Zeile'] }],
        widersprueche: { alt: [widerspruch], neu: [] }, entfernteWidersprueche: [widerspruch] },
      befunde: [], hinweise: [{ code: 'STUFE_STEIGT', text: 'Stufe steigt' }],
      begruendung: 'Synthetische Begründung', widersprueche: [],
      entfernteAussagen: [{ schluessel: 'alt', begruendung: 'Synthetische Entfernung' }],
    }],
  };
  await this.seite.route((url) => url.pathname.startsWith('/api/v1/wissensentwuerfe'), async (route) => {
    const request = route.request();
    if (request.method() === 'POST') {
      const pfad = new URL(request.url()).pathname;
      if (pfad.endsWith('/wiederholen')) {
        await route.fulfill({ status: 202, contentType: 'application/json',
          body: JSON.stringify({ entwurfId: 'e1', status: 'IN_ARBEIT' }) });
      } else {
        this.wissensEntscheide.push({ pfad,
          etag: request.headers()['if-match'], angaben: request.postDataJSON() });
        ansicht.aenderungen[0].status = 'ANGENOMMEN';
        kopf.status = 'ERLEDIGT';
        await route.fulfill({ status: 204 });
      }
    } else {
      const detail = new URL(request.url()).pathname.endsWith('/e1');
      await route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(detail ? ansicht : kopf.status === 'OFFEN' ? [{ entwurf: kopf }] : []) });
    }
  });
});

When('ich einen Wissensentwurf auf {int} Pixeln öffne', async function (breite) {
  this.entwurfBreite = breite;
  await this.seite.setViewportSize({ width: breite, height: 900 });
  const menue = this.seite.getByRole('button', { name: 'Menü öffnen' });
  if (breite === 375) await menue.click();
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' })
    .getByRole('link', { name: /^Wissensentwürfe/ }).click();
  await this.seite.getByRole('link', { name: /INGEST/ }).click();
  await this.seite.getByRole('heading', { name: 'konzepte/probe' }).waitFor();
});

When('ich den betroffenen Abschnitt erneut verarbeite', async function () {
  await this.seite.getByRole('button', { name: 'Abschnitt neu verarbeiten' }).click();
  await this.seite.getByRole('status')
    .filter({ hasText: 'Der Abschnitt wird neu verarbeitet.' }).waitFor();
  this.abschnittWiederholung = true;
});

Then('ist der Abschnittsfehler sichtbar', async function () {
  await this.seite.getByText(/Abschnitt 1, Seite 1: 2 Aussagen verworfen, 1 übernommen/).waitFor();
  if (this.abschnittWiederholung) {
    const status = this.seite.getByRole('status')
      .filter({ hasText: 'Der Abschnitt wird neu verarbeitet.' });
    await status.waitFor();
    assert.equal(await status.evaluate((element) => element === document.activeElement), true);
  }
});

Then('sehe ich Diffzeichen, Befunde, Kontext und nicht zugeordnete Kernaussagen', async function () {
  const seite = this.seite;
  await seite.getByText(/− Alte Zeile/).waitFor();
  await seite.getByText('+ Neue Zeile', { exact: true }).waitFor();
  await seite.getByText('Kontextstufe: INTERN', { exact: true }).waitFor();
  await seite.getByText('Keiner Änderung zugeordnet', { exact: true }).waitFor();
  await seite.getByRole('heading', { name: 'Sperrende Befunde' }).waitFor();
  await seite.getByRole('heading', { name: 'Entfernte Aussagen' }).waitFor();
  await seite.getByText('Synthetischer Widerspruch', { exact: false }).first().waitFor();
  await seite.getByText('Widerspruch wird aufgelöst', { exact: true }).waitFor();
});

Then('die Entwurfsansicht hat keinen horizontalen Überlauf und keinen axe-Befund', async function () {
  const alt = await this.seite.locator('.diff .alt').first().boundingBox();
  const neu = await this.seite.locator('.diff .neu').first().boundingBox();
  assert.ok(alt && neu);
  if (this.entwurfBreite < 1024) {
    assert.ok(neu.y >= alt.y + alt.height, 'Diff steht am Handy nicht untereinander');
  } else {
    assert.ok(neu.x >= alt.x + alt.width, 'Diff steht am Desktop nicht nebeneinander');
  }
  await this.seite.addScriptTag({ content: axe.source });
  const breite = await this.seite.evaluate(() => ({
    inhalt: document.documentElement.scrollWidth, fenster: document.documentElement.clientWidth,
  }));
  assert.ok(breite.inhalt <= breite.fenster, `Horizontaler Überlauf: ${JSON.stringify(breite)}`);
  const pruefung = await this.seite.evaluate(() => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
  }));
  assert.deepEqual(pruefung.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((n) => n.target) })), []);
});

When('ich nur diese Wissensänderung mit Bestätigung annehme', async function () {
  await this.seite.getByRole('button', { name: 'Prüfen und entscheiden' }).click();
  await this.seite.getByRole('button', { name: 'Einzeln annehmen', exact: true }).click();
  assert.equal(this.wissensEntscheide.length, 0);
  await this.seite.getByRole('dialog').getByRole('button', { name: 'Einzeln annehmen', exact: true }).click();
  await this.seite.getByText('INGEST · ERLEDIGT', { exact: false }).waitFor();
});

Then('wurde genau diese Änderung mit If-Match gesendet und der Navigationszähler aktualisiert', async function () {
  assert.equal(this.wissensEntscheide.length, 1);
  assert.equal(this.wissensEntscheide[0].pfad, '/api/v1/wissensentwuerfe/e1/aenderungen/2/annehmen');
  assert.equal(this.wissensEntscheide[0].etag, '"7"');
  const menue = this.seite.getByRole('button', { name: 'Menü öffnen' });
  if (await menue.isVisible()) await menue.click();
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' })
    .getByRole('link', { name: /Wissensentwürfe.*0 offene Wissensentwürfe/ }).waitFor();
});
