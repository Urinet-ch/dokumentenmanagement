// Suche und Dokumentansicht der PWA (Aufgabe 4.6) gegen die Attrappe aus hilfen/suche-attrappe.mjs.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { DOKUMENTE, S3_PFAD, sucheAttrappe } from '../hilfen/suche-attrappe.mjs';
import { platzhalterPruefen } from '../hilfen/platzhalter.mjs';

const FELDER = {
  Suchbegriff: 'q',
  Dokumentart: 'art',
  'Belegdatum von': 'datumVon',
  'Belegdatum bis': 'datumBis',
  'Betrag von (CHF)': 'betragVon',
  'Betrag bis (CHF)': 'betragBis',
  Richtung: 'richtung',
  Status: 'status',
  Tags: 'tags',
  Geschäftsjahr: 'geschaeftsjahr',
  Belegnummer: 'belegnummer',
};

const dokumentId = (titel) => {
  // Ohne Titel nennt das Szenario den Dateinamen (Platzhalter, Req 14.9)
  const d = DOKUMENTE.find((x) => x.titel === titel || (!x.titel && x.dateiname === titel));
  assert.ok(d, `kein synthetisches Dokument «${titel}»`);
  return d.id;
};

async function filterOeffnen(seite) {
  const schalter = seite.locator('.filter-schalter');
  if ((await schalter.getAttribute('aria-expanded')) !== 'true') {
    await schalter.click();
  }
}

async function letzteAnfrage(welt, anzahl) {
  for (let versuch = 0; versuch < 100 && welt.suche.anfragen.length < anzahl; versuch++) {
    await welt.seite.waitForTimeout(50);
  }
  return welt.suche.anfragen.at(-1);
}

Given('das Archiv liefert synthetische Suchtreffer', async function () {
  await sucheAttrappe(this);
});

Given(/^das Browserfenster ist (\d+) Pixel breit$/, async function (breite) {
  await this.seite.setViewportSize({ width: Number(breite), height: 800 });
});

When('ich die Suche über das Menü öffne', async function () {
  const menue = this.seite.getByRole('button', { name: 'Menü öffnen' });
  if (await menue.isVisible()) {
    await menue.click();
  }
  await this.seite.getByRole('navigation', { name: 'Hauptnavigation' }).getByRole('link', { name: 'Suche' }).click();
  await this.seite.getByRole('heading', { name: 'Suche', level: 1 }).waitFor();
  await this.seite.locator('[data-test=anzahl]').waitFor();
});

When(/^ich nach «(.+)» suche$/, async function (begriff) {
  const vorher = this.suche.anfragen.length;
  await this.seite.getByRole('searchbox', { name: 'Suchbegriff' }).fill(begriff);
  await this.seite.getByRole('searchbox', { name: 'Suchbegriff' }).press('Enter');
  await letzteAnfrage(this, vorher + 1);
});

When('ich die Filter setze und suche:', async function (tabelle) {
  await filterOeffnen(this.seite);
  const vorher = this.suche.anfragen.length;
  for (const { filter, wert } of tabelle.hashes()) {
    const feld = this.seite.locator(`#filterleiste [name=${FELDER[filter]}]`);
    if ((await feld.evaluate((e) => e.tagName)) === 'SELECT') {
      await feld.selectOption({ label: wert });
    } else {
      await feld.fill(wert);
    }
  }
  await this.seite.locator('#filterleiste').getByRole('button', { name: 'Suchen', exact: true }).click();
  await letzteAnfrage(this, vorher + 1);
});

When(/^ich den Treffer «(.+)» öffne$/, async function (titel) {
  await this.seite.locator('[data-test=treffer]').getByRole('link', { name: titel }).click();
});

When(/^ich den Link «(.+)» wähle$/, async function (link) {
  await this.seite.getByRole('link', { name: link }).click();
});

Then('hat die PWA die Suche mit diesen Parametern angefragt:', function (tabelle) {
  const p = this.suche.anfragen.at(-1);
  assert.ok(p, 'keine Suchanfrage');
  for (const { parameter, wert } of tabelle.hashes()) {
    assert.deepEqual(p.getAll(parameter), wert.split('|'), `${parameter} in ${p}`);
  }
});

Then(/^sendet die PWA keinen Parameter «(.+)»$/, function (liste) {
  const p = this.suche.anfragen.at(-1);
  for (const name of liste.split(', ')) {
    assert.equal(p.has(name), false, `${name} in ${p}`);
  }
});

Then(/^sehe ich genau die Treffer «(.*)»$/, async function (liste) {
  const titel = this.seite.locator('[data-test=treffer] a.titel');
  const erwartet = liste ? liste.split(', ') : [];
  await this.seite.waitForFunction((n) => document.querySelectorAll('[data-test=treffer]').length === n, erwartet.length);
  assert.deepEqual(await titel.allInnerTexts(), erwartet);
});

Then(/^hebt der Treffer «(.+)» genau «(.+)» hervor$/, async function (titel, begriff) {
  const eintrag = this.seite.locator('[data-test=treffer]').filter({ has: this.seite.getByRole('link', { name: titel }) });
  assert.deepEqual(await eintrag.locator('[data-test=auszug] mark').allInnerTexts(), [begriff]);
});

Then(/^zeigt der Treffer «(.+)» die Belegnummer «(.+)» und den Betrag «(.+)»$/, async function (titel, nummer, betrag) {
  const eintrag = this.seite.locator('[data-test=treffer]').filter({ has: this.seite.getByRole('link', { name: titel }) });
  assert.equal(await eintrag.locator('[data-test=belegnummer]').innerText(), nummer);
  assert.ok((await eintrag.innerText()).includes(betrag), await eintrag.innerText());
});

Then('ist die Filterleiste eingeklappt', async function () {
  assert.equal(await this.seite.locator('.filter-schalter').getAttribute('aria-expanded'), 'false');
  assert.equal(await this.seite.locator('#filterleiste').isVisible(), false);
});

Then('lässt sich die Seite nicht waagrecht scrollen', async function () {
  const breiten = await this.seite.evaluate(() => ({
    inhalt: document.documentElement.scrollWidth,
    fenster: document.documentElement.clientWidth,
  }));
  assert.ok(breiten.inhalt <= breiten.fenster, JSON.stringify(breiten));
});

Then(/^sehe ich die Dokumentansicht von «(.+)»$/, async function (titel) {
  await this.seite.waitForURL((url) => url.pathname === `/dokument/${dokumentId(titel)}`);
  await this.seite.getByRole('heading', { name: titel, level: 1 }).waitFor();
});

Then(/^zeigt die Dokumentansicht «(.+)» mit «(.+)»$/, async function (angabe, wert) {
  const dt = this.seite.locator('dt', { hasText: angabe }).first();
  assert.equal(await dt.locator('xpath=following-sibling::dd[1]').innerText(), wert);
});

Then(/^hat die PWA die PDF\/A-Adresse von «(.+)» als JSON angefragt$/, async function (titel) {
  await this.seite.locator('[data-test=download]').waitFor();
  assert.deepEqual(this.suche.downloads, [{ id: dokumentId(titel), variante: 'pdfa', accept: 'application/json' }]);
  assert.equal(this.suche.abgelehnt, 0, 'Anfrage ohne Token');
});

Then('zeigt die Vorschau die vorsignierte Adresse als PDF', async function () {
  const vorschau = this.seite.locator('object[data-test=vorschau]');
  assert.equal(await vorschau.getAttribute('type'), 'application/pdf');
  assert.ok((await vorschau.getAttribute('data')).includes(S3_PFAD), await vorschau.getAttribute('data'));
});

Then(/^öffnet «(.+)» die vorsignierte Adresse in einem neuen Tab$/, async function (name) {
  const link = this.seite.getByRole('link', { name, exact: true });
  assert.ok((await link.getAttribute('href')).includes(`${S3_PFAD}`), await link.getAttribute('href'));
  assert.equal(await link.getAttribute('target'), '_blank');
  assert.equal(await link.getAttribute('rel'), 'noopener noreferrer');
});

Then(/^steht «(.+)» weder in der Adresse noch im Browser-Speicher$/, async function (begriff) {
  assert.ok(!this.seite.url().includes(begriff), this.seite.url());
  const gespeichert = await this.seite.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  assert.ok(!gespeichert.includes(begriff), gespeichert);
});

Then(/^steht im Suchfeld «(.+)»$/, async function (begriff) {
  assert.equal(await this.seite.getByRole('searchbox', { name: 'Suchbegriff' }).inputValue(), begriff);
});

Then(/^zeigt der Treffer «(.+)» den Dateinamen als Platzhalter statt eines Titels$/, async function (name) {
  const link = this.seite.locator('[data-test=treffer] a.titel', { has: this.seite.locator(`[title="${name}"]`) });
  await platzhalterPruefen(link, name);
  await this.seite.getByRole('link', { name: `Ohne Titel, Dateiname ${name}`, exact: true }).waitFor();
});

Then(/^zeigt die Dokumentansicht den Dateinamen «(.+)» als Platzhalter und in der Zeile «Dateiname»$/, async function (name) {
  await platzhalterPruefen(this.seite.locator('h1'), name);
  const zeile = this.seite.locator('dd[data-test=dateiname]');
  assert.equal(await this.seite.locator('dt', { hasText: 'Dateiname' }).locator('xpath=following-sibling::dd[1]').getAttribute('data-test'), 'dateiname');
  assert.equal(await zeile.locator('bdi').textContent(), name);
  assert.equal(await zeile.locator('bdi *').count(), 0, 'der Dateiname erscheint als Markup');
});
