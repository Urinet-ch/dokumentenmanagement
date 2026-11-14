// Metadaten bearbeiten, Versionen und Vorschaubild der Dokumentansicht (Aufgabe 4.6) gegen hilfen/suche-attrappe.mjs;
// die Änderung ohne Titel (Aufgabe 4.10) auch gegen das Archiv im Testmodus (weitergeleitete Anfragen).
import { Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { DOKUMENTE } from '../hilfen/suche-attrappe.mjs';

const formular = (seite) => seite.locator('[data-test=bearbeiten-formular]');

When(/^ich den Knopf «(.+)» wähle$/, async function (knopf) {
  await this.seite.getByRole('button', { name: knopf, exact: true }).click();
});

When(/^ich den Titel auf «(.+)» ändere$/, async function (titel) {
  await formular(this.seite).getByLabel('Titel', { exact: true }).fill(titel);
});

When(/^ich die Art auf «(.+)» wechsle$/, async function (art) {
  await formular(this.seite).locator('#bearbeiten-art').selectOption({ label: art });
});

When(/^ich das Zusatzfeld «(.+)» mit «(.+)» ausfülle$/, async function (feld, wert) {
  await formular(this.seite).locator(`#bearbeiten-zusatz-${feld}`).fill(wert);
});

When('ich ohne Grund speichere', async function () {
  await formular(this.seite).getByRole('button', { name: 'Änderungen speichern' }).click();
});

When(/^ich als Grund «(.+)» angebe und speichere$/, async function (grund) {
  await formular(this.seite).getByLabel(/Grund der Änderung/).fill(grund);
  await formular(this.seite).getByRole('button', { name: 'Änderungen speichern' }).click();
});

Then('verlangt das Formular einen Grund und die PWA hat nichts gesendet', async function () {
  const fehler = formular(this.seite).locator('[data-test=formular-fehler]');
  await fehler.waitFor();
  assert.match(await fehler.innerText(), /Grund/);
  assert.equal(await formular(this.seite).getByLabel(/Grund der Änderung/).getAttribute('aria-invalid'), 'true');
  assert.deepEqual(this.suche.aenderungen, []);
});

Then(/^hat die PWA nur den Titel und den Grund mit «If-Match» der Version (\d+) gesendet$/, async function (version) {
  await this.seite.locator('[data-test=bearbeiten-formular]').waitFor({ state: 'detached' });
  assert.equal(this.suche.aenderungen.length, 1);
  const [aenderung] = this.suche.aenderungen;
  assert.equal(aenderung.ifMatch, `"${version}"`);
  assert.deepEqual(Object.keys(aenderung.body).sort(), ['grund', 'titel']);
  assert.equal(this.suche.abgelehnt, 0, 'Anfrage ohne Token');
});

Then(/^meldet die Dokumentansicht «(.+)»$/, async function (text) {
  const meldung = this.seite.locator('[data-test=meldung]');
  await this.seite.waitForFunction(() => document.querySelector('[data-test=meldung]')?.textContent?.trim());
  assert.equal((await meldung.innerText()).trim(), text);
});

Then('nennt die Dokumentansicht keine früheren Fassungen', async function () {
  const versionen = this.seite.locator('[data-test=versionen]');
  await versionen.waitFor();
  assert.equal(await versionen.locator('[data-test=version]').count(), 0);
  assert.match(await versionen.innerText(), /Keine früheren Fassungen/);
});

Then(/^nennt die Dokumentansicht die Fassung (\d+) mit dem Grund «(.+)», geändert von «(.+)»$/, async function (nr, grund, wer) {
  const eintraege = this.seite.locator('[data-test=versionen] [data-test=version]');
  await eintraege.first().waitFor();
  assert.equal(await eintraege.count(), 1);
  const text = await eintraege.first().innerText();
  assert.ok(text.includes(`Fassung ${nr}`), text);
  assert.ok(text.includes(grund), text);
  assert.ok(text.includes(`geändert von ${wer}`), text);
});

Then(/^bietet die Dokumentansicht «(.+)» nicht an$/, async function (knopf) {
  await this.seite.locator('[data-test=versionen]').waitFor();
  assert.equal(await this.seite.getByRole('button', { name: knopf }).count(), 0);
});

Then(/^zeigt die Dokumentansicht das Vorschaubild der Seite (\d+) von «(.+)»$/, async function (seite, titel) {
  const bild = this.seite.locator('img[data-test=bild]');
  await bild.waitFor();
  assert.match(await bild.getAttribute('src'), /^blob:/);
  assert.ok((await bild.getAttribute('alt')).includes(titel), await bild.getAttribute('alt'));
  assert.equal(await this.seite.locator('object[data-test=vorschau]').count(), 0);
  const id = DOKUMENTE.find((d) => d.titel === titel).id;
  assert.deepEqual(this.suche.bilder, [{ id, seite: String(seite) }]);
});

Then(/^trägt die Dokumentansicht den Titel «(.+)»$/, async function (titel) {
  await this.seite.getByRole('heading', { name: titel, level: 1 }).waitFor();
});

Then(/^ist im Formular das Feld «(.+)» leer$/, async function (feld) {
  assert.equal(await formular(this.seite).getByLabel(feld, { exact: true }).inputValue(), '');
});

When(/^ich die Tags auf «(.+)» ändere$/, async function (tags) {
  await formular(this.seite).locator('#bearbeiten-tags').fill(tags);
});

Then(/^hat die PWA die Änderung ohne Titel und ohne den Dateinamen «(.+)» gesendet$/, async function (name) {
  await formular(this.seite).waitFor({ state: 'detached' });
  // An die Attrappe oder, weitergeleitet, an das echte Archiv (dort mit dessen Antwort)
  const aenderungen = this.suche?.aenderungen ?? this.weiterleitung.anfragen.filter((a) => a.methode === 'PATCH');
  assert.equal(aenderungen.length, 1);
  const [{ body }] = aenderungen;
  assert.ok(!('titel' in body), JSON.stringify(body));
  assert.ok(!JSON.stringify(body).includes(name), JSON.stringify(body));
  if (this.suche) {
    assert.equal(this.suche.abgelehnt, 0, 'Anfrage ohne Token');
  } else {
    for (let versuch = 0; versuch < 50 && aenderungen[0].antwort === undefined; versuch++) {
      await this.seite.waitForTimeout(100);
    }
    assert.equal(aenderungen[0].status, 200, JSON.stringify(aenderungen[0].antwort));
  }
});

Then(/^hat die PWA die Art «(.+)» mit den Zusatzwerten (.+) und dem Grund gesendet$/, async function (art, zusatz) {
  await this.seite.locator('[data-test=bearbeiten-formular]').waitFor({ state: 'detached' });
  assert.equal(this.suche.aenderungen.length, 1);
  const [{ body }] = this.suche.aenderungen;
  assert.deepEqual(Object.keys(body).sort(), ['art', 'grund', 'zusatz']);
  assert.equal(body.art, art);
  assert.deepEqual(body.zusatz, JSON.parse(zusatz));
});
