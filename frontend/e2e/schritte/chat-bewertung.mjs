import { Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';

const bewertung = (seite) => seite.locator('[data-test=bewertung]').first();

Then('sind die Sterne und der Datenschutzhinweis sichtbar', async function () {
  const bereich = bewertung(this.seite);
  await bereich.getByRole('button', { name: 'Antwort bewerten, 1 von 5 Sternen' }).waitFor();
  assert.equal(await bereich.locator('[data-test=stern]').count(), 5);
  assert.match(await bereich.innerText(), /12 Monate gespeichert und vom Inhaber ausgewertet/);
});

When('ich die erste Antwort mit zwei Sternen per Tastatur bewerte', async function () {
  const stern = bewertung(this.seite).getByRole('button', { name: 'Antwort bewerten, 2 von 5 Sternen' });
  await stern.focus();
  await stern.press('Space');
  await this.seite.waitForFunction(() =>
    document.querySelectorAll('[data-test=bewertung]')[0]?.querySelectorAll('[data-test=stern]')[1]
      ?.getAttribute('aria-pressed') === 'true');
  assert.equal(await stern.getAttribute('aria-pressed'), 'true');
});

When('ich den Grund «Antwort falsch» und einen Freitext angebe und die Bewertung speichere', async function () {
  const bereich = bewertung(this.seite);
  await bereich.getByLabel('Grund (freiwillig)').selectOption('FALSCH');
  await bereich.getByLabel('Freitext (freiwillig, höchstens 500 Zeichen)').fill('Synthetische Bewertung');
  await bereich.getByRole('button', { name: 'Bewertung speichern' }).click();
  await bereich.getByRole('button', { name: 'Bewertung entfernen' }).waitFor();
});

Then('hat die Bewertung keinen axe-Befund und keinen horizontalen Überlauf', async function () {
  await this.seite.addScriptTag({ content: axe.source });
  const ergebnis = await this.seite.evaluate(() => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
  }));
  assert.deepEqual(ergebnis.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((n) => n.target) })), []);
  const breite = await this.seite.evaluate(() => ({
    inhalt: document.documentElement.scrollWidth, fenster: document.documentElement.clientWidth,
  }));
  assert.ok(breite.inhalt <= breite.fenster, `Horizontaler Überlauf: ${JSON.stringify(breite)}`);
});

Then('ist die Bewertung mit zwei Sternen und ihrem Freitext wieder da', async function () {
  const bereich = bewertung(this.seite);
  await bereich.getByRole('button', { name: 'Bewertung entfernen' }).waitFor();
  assert.equal(await bereich.getByRole('button', { name: 'Antwort bewerten, 2 von 5 Sternen' })
    .getAttribute('aria-pressed'), 'true');
  assert.equal(await bereich.getByLabel('Freitext (freiwillig, höchstens 500 Zeichen)').inputValue(),
    'Synthetische Bewertung');
});

When('ich die Bewertung entferne', async function () {
  await bewertung(this.seite).getByRole('button', { name: 'Bewertung entfernen' }).click();
});

Then('ist die Bewertung entfernt', async function () {
  await this.seite.waitForFunction(() => !document.querySelector('[data-test=bewertung] textarea'));
  assert.equal(this.chat.bewertungen.size, 0);
});
