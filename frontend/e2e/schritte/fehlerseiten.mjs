import { Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';

When('ich die geschützte Verwaltungsseite ohne Neuladen aufrufe', async function () {
  await this.seite.evaluate(() => {
    history.pushState(null, '', '/verwaltung/einstellungen');
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  });
});

Then(/^sehe ich die Überschrift «(.+)»$/, async function (titel) {
  await this.seite.getByRole('heading', { name: titel, level: 1, exact: true }).waitFor();
});

Then(/^sehe ich den Link «(.+)»$/, async function (name) {
  await this.seite.getByRole('link', { name, exact: true }).waitFor();
});

Then('ist die Fehleransicht bei 375 und 1280 Pixeln in hell und dunkel ohne axe-Befund',
  { timeout: 60_000 }, async function () {
    await this.seite.addScriptTag({ content: axe.source });
    for (const breite of [375, 1280]) {
      await this.seite.setViewportSize({ width: breite, height: 900 });
      for (const schema of ['light', 'dark']) {
        await this.seite.emulateMedia({ colorScheme: schema });
        const ergebnis = await this.seite.evaluate(async () => ({
          ueberlauf: document.documentElement.scrollWidth > document.documentElement.clientWidth,
          befunde: (await window.axe.run(document)).violations.map(({ id, nodes }) => ({
            id, targets: nodes.map((node) => node.target),
          })),
        }));
        assert.equal(ergebnis.ueberlauf, false, `${breite}px, ${schema}: horizontaler Überlauf`);
        assert.deepEqual(ergebnis.befunde, [], `${breite}px, ${schema}: ${JSON.stringify(ergebnis.befunde)}`);
      }
    }
  });
