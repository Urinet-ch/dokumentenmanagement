import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';
import { DOKUMENTE, sucheAttrappe } from '../hilfen/suche-attrappe.mjs';

const dokument = DOKUMENTE[0];
const zeile = {
  id: 'seite-1', pfad: 'konzepte/probe', typ: 'Konzept', titel: 'Synthetische Wissensseite',
  beschreibung: 'Synthetische Beschreibung', status: 'AKTIV', quellePruefen: true,
};

Given('synthetische Wissensseiten sind für die Oberfläche verfügbar', async function () {
  await sucheAttrappe(this);
  await this.seite.route((url) => url.pathname.startsWith('/api/v1/wissensseiten')
    || url.pathname === '/api/v1/wissensbasis/log', async (route) => {
    const url = new URL(route.request().url());
    let antwort;
    if (url.pathname === '/api/v1/wissensbasis/log') {
      antwort = { eintraege: [{ id: 1, text: 'Synthetischer Logeintrag', seiten: [] }] };
    } else if (url.pathname.endsWith('/eingehend')) {
      antwort = [];
    } else if (url.pathname.endsWith('/versionen')) {
      antwort = { versionen: [] };
    } else if (url.searchParams.has('pfad')) {
      antwort = {
        ...zeile, version: 1, freigegebenAm: '2026-01-01T10:00:00Z',
        inhaltHtml: '<p>Belegte Aussage.<sup class="footnote-ref">'
          + '<a href="#fn-q1" data-footnote-ref>1</a></sup></p>',
        quellen: [{ schluessel: 'q1', dokumentId: dokument.id, seiteVon: 2 }],
      };
    } else {
      antwort = { gesamt: 1, gruppen: [{ typ: 'Konzept', seiten: [zeile] }] };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(antwort) });
  });
});

When('ich die Wissensoberfläche auf 375 Pixeln öffne', async function () {
  await this.seite.setViewportSize({ width: 375, height: 812 });
  await this.seite.getByRole('button', { name: 'Menü öffnen' }).click();
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' })
    .getByRole('link', { name: 'Wissen' }).click();
  await this.seite.getByRole('heading', { name: 'Wissen', exact: true }).waitFor();
  await this.seite.addScriptTag({ content: axe.source });
});

async function pruefen(seite) {
  const breite = await seite.evaluate(() => ({
    inhalt: document.documentElement.scrollWidth,
    fenster: document.documentElement.clientWidth,
  }));
  assert.ok(breite.inhalt <= breite.fenster, `Horizontaler Überlauf: ${JSON.stringify(breite)}`);
  const ergebnis = await seite.evaluate(() => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
  }));
  assert.deepEqual(ergebnis.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((n) => n.target) })), []);
}

Then('sind Index, Seite, Quellen und Log ohne horizontales Scrollen und ohne axe-Befund bedienbar', async function () {
  const seite = this.seite;
  await seite.getByText('Quelle prüfen', { exact: true }).waitFor();
  await pruefen(seite);
  await seite.getByRole('link', { name: zeile.titel }).click();
  await seite.getByRole('heading', { name: zeile.titel }).waitFor();
  await pruefen(seite);
  const fussnote = seite.locator('article').getByRole('link', { name: `[1] ${dokument.titel} · Seite 2` });
  assert.equal(await fussnote.getAttribute('href'), `/dokument/${dokument.id}?seite=2`);
  await fussnote.click();
  await seite.getByRole('heading', { name: dokument.titel }).waitFor();
  await seite.getByRole('heading', { name: 'In der Wissensbasis verwendet' }).waitFor();
  await pruefen(seite);
  await seite.getByRole('link', { name: zeile.titel }).click();
  await seite.getByRole('link', { name: 'Zurück zum Wissen' }).click();
  await seite.getByRole('link', { name: 'Änderungslog' }).click();
  await seite.getByText('Synthetischer Logeintrag').waitFor();
  await pruefen(seite);
});
