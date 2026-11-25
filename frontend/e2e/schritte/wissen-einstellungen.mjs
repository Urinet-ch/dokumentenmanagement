import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import axe from 'axe-core';

const PFAD = '/api/v1/wissensbasis/einstellungen';
const WERTE = { budgetTokens: 600000, lintBudgetTokens: 100000, lintZeitplan: '0 0 4 ? * SUN',
  monatsbudgetTokens: 1000000 };

Given('die Wissenseinstellungen liefern synthetische Daten', async function () {
  this.wissenAnfragen = [];
  await this.seite.route((url) => url.pathname === PFAD, async (route) => {
    const anfrage = route.request();
    if (anfrage.method() === 'GET') {
      return route.fulfill({ status: 200, headers: { ETag: '"7"' },
        contentType: 'application/json', body: JSON.stringify(WERTE) });
    }
    this.wissenAnfragen.push({ headers: anfrage.headers(), url: anfrage.url(), body: anfrage.postDataJSON() });
    const bestaetigt = new URL(anfrage.url()).searchParams.get('bestaetigt') === 'true';
    const antwort = bestaetigt
      ? { fehlercode: 'STEP_UP_NOETIG' }
      : { fehlercode: 'BESTAETIGUNG_NOETIG', auswirkung: [{ text: 'Das Budget wird erhöht.' }] };
    return route.fulfill({ status: bestaetigt ? 401 : 409, headers: { ETag: '"7"' },
      contentType: 'application/problem+json', body: JSON.stringify(antwort) });
  });
});

Then('sind die Wissenseinstellungen ohne Überlauf und axe-Befund sichtbar', async function () {
  await this.seite.getByRole('heading', { name: 'Wissensbasis: Einstellungen und Export' }).waitFor();
  await this.seite.getByLabel('Monatliches Tokenbudget').waitFor();
  await this.seite.addScriptTag({ content: axe.source });
  const ergebnis = await this.seite.evaluate(async () => ({
    ueberlauf: [document.documentElement, document.querySelector('mat-sidenav-content')]
      .filter(Boolean).some((element) => element.scrollWidth > element.clientWidth),
    befunde: (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    })).violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) })),
  }));
  assert.equal(ergebnis.ueberlauf, false);
  assert.deepEqual(ergebnis.befunde, []);
  if (process.env.WISSEN_BILDER) {
    const breite = this.seite.viewportSize().width;
    const schema = await this.seite.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches ? 'dunkel' : 'hell');
    await this.seite.screenshot({ path: join(process.env.WISSEN_BILDER, `wissen-${breite}-${schema}.png`), fullPage: true });
  }
});

Then('sehe ich den Cloud-Modell-Hinweis vor dem OKF-Export', async function () {
  const bereich = this.seite.getByRole('region', { name: 'Wissensbasis exportieren' });
  const hinweis = bereich.getByText(/^Cloud-Modelle dürfen nur Quellen der Stufe NORMAL ohne Personendaten sehen/);
  const exportknopf = bereich.getByRole('button', { name: 'OKF-Export anfordern' });
  await hinweis.scrollIntoViewIfNeeded();
  await exportknopf.waitFor();
  const stehtVorExport = await hinweis.evaluate((element) => {
    const button = [...(element.closest('section')?.querySelectorAll('button') ?? [])]
      .find((kandidat) => kandidat.textContent?.trim() === 'OKF-Export anfordern');
    return button !== undefined
      && Boolean(element.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING);
  });
  assert.equal(stehtVorExport, true);
});

When('ich ein erhöhtes Wissensbudget bestätige', async function () {
  await this.seite.getByLabel('Monatliches Tokenbudget').fill('2500000');
  await this.seite.getByRole('button', { name: 'Einstellungen speichern' }).click();
  await this.seite.getByText('Das Budget wird erhöht.').waitFor();
  await this.seite.getByRole('button', { name: 'Änderung bestätigen' }).click();
});

Then('wird vor dem Speichern eine erneute Anmeldung verlangt', async function () {
  await this.seite.getByRole('button', { name: 'Erneut anmelden' }).waitFor();
  assert.equal(this.wissenAnfragen.length, 2);
  assert.equal(this.wissenAnfragen[0].headers['if-match'], '"7"');
  assert.equal(this.wissenAnfragen[1].headers['if-match'], '"7"');
  assert.equal(this.wissenAnfragen[1].body.monatsbudgetTokens, 2500000);
  assert.equal(new URL(this.wissenAnfragen[1].url).searchParams.get('bestaetigt'), 'true');
});
