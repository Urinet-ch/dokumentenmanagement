// Schritte für die Hybridsuche als Standard in der PWA (Spec 02, Aufgabe 6.6, Req 2.7). Sie gelten für die Attrappe
// (hilfen/suche-attrappe.mjs) und für das echte Archiv im Testmodus; das Anfrage-Journal liefert `suchanfragen`.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { direktHochladen, echteDokumente, inhalt } from '../hilfen/echtes-backend.mjs';
import { HYBRID_REIHENFOLGE, sucheAttrappe } from '../hilfen/suche-attrappe.mjs';
import { filterOeffnen, letzteAnfrage, suchanfragen } from './suche.mjs';

const KEINE_ZAHL = /\d/;

const treffer = (welt) => welt.seite.locator('[data-test=treffer]');
const titelListe = (welt) => welt.seite.locator('[data-test=treffer] a.titel').allInnerTexts();
const umschalter = (welt) => welt.seite.getByRole('checkbox', { name: 'Volltext' });

async function suchen(welt, begriff, volltext) {
  const vorher = suchanfragen(welt).length;
  const feld = welt.seite.getByRole('searchbox', { name: 'Suchbegriff' });
  await feld.fill(begriff);
  if (volltext) {
    await umschalter(welt).check();
  }
  await feld.press('Enter');
  await letzteAnfrage(welt, vorher + 1);
}

Given('der Reranker des Archivs ist ausgefallen', async function () {
  (await sucheAttrappe(this)).rerankerAusfall = true;
});

// Ein gemeinsames Wort im Dateinamen, je Szenario neu, damit Treffer früherer Läufe im selben Archiv nicht mitzählen.
// Die Inhalte sind verschieden (Dublettenprüfung), alles synthetisch.
Given(
  /^«(.+)» im Mandanten «(.+)» hat (\d+) Dateien mit einem gemeinsamen Wort im Namen hochgeladen$/,
  async function (rolle, mandant, anzahl) {
    const wort = `Sammel${[...randomBytes(8)].map((b) => String.fromCharCode(97 + (b % 26))).join('')}`;
    this.gemeinsamesWort = wort;
    for (let i = 1; i <= Number(anzahl); i++) {
      const name = `${wort}_${String(i).padStart(2, '0')}.pdf`;
      const [ergebnis] = await direktHochladen(rolle, mandant, [{ name, mimeType: 'application/pdf', buffer: inhalt(name) }]);
      assert.equal(ergebnis.status, 'NEU', JSON.stringify(ergebnis));
      echteDokumente(this).set(name, ergebnis.id);
    }
  },
);

When('ich nach dem gemeinsamen Wort suche', async function () {
  await suchen(this, this.gemeinsamesWort, false);
});

When(/^ich nach «(.+)» im Volltext suche$/, async function (begriff) {
  await suchen(this, begriff, true);
});

When(/^ich den Umschalter «Volltext» (ein|aus)schalte$/, async function (richtung) {
  const vorher = suchanfragen(this).length;
  await umschalter(this).setChecked(richtung === 'ein');
  await letzteAnfrage(this, vorher + 1);
});

When(/^ich im Hinweis den Knopf «(.+)» wähle$/, async function (name) {
  const vorher = suchanfragen(this).length;
  await this.seite.locator('[data-test=nur-volltext]').getByRole('button', { name }).click();
  await letzteAnfrage(this, vorher + 1);
});

When(/^ich die Sortierung «(.+)» wähle und suche$/, async function (sortierung) {
  await filterOeffnen(this.seite);
  const vorher = suchanfragen(this).length;
  await this.seite.locator('#filterleiste [name=sortierung]').selectOption({ label: sortierung });
  await this.seite.locator('#filterleiste').getByRole('button', { name: 'Suchen', exact: true }).click();
  await letzteAnfrage(this, vorher + 1);
});

When(/^ich nur den Status «(.+)» als Filter setze und suche$/, async function (status) {
  await filterOeffnen(this.seite);
  const vorher = suchanfragen(this).length;
  await this.seite.locator('#filterleiste [name=status]').selectOption({ label: status });
  await this.seite.locator('#filterleiste').getByRole('button', { name: 'Suchen', exact: true }).click();
  await letzteAnfrage(this, vorher + 1);
});

When('ich zur nächsten Seite blättere', async function () {
  const vorher = suchanfragen(this).length;
  await this.seite.getByRole('button', { name: 'Nächste Seite' }).click();
  await letzteAnfrage(this, vorher + 1);
});

When('ich den ersten Treffer öffne', async function () {
  this.trefferVorher = await titelListe(this);
  this.anfragenVorher = suchanfragen(this).length;
  await this.seite.locator('[data-test=treffer] a.titel').first().click();
});

Then(/^sehe ich (\d+) Treffer$/, async function (anzahl) {
  await this.seite.waitForFunction((n) => document.querySelectorAll('[data-test=treffer]').length === n, Number(anzahl));
  assert.equal(await treffer(this).count(), Number(anzahl));
});

Then('sehe ich die Treffer in der Reihenfolge, in der das Archiv sie liefert', async function () {
  await this.seite.waitForFunction((n) => document.querySelectorAll('[data-test=treffer]').length === n, HYBRID_REIHENFOLGE.length);
  assert.deepEqual(await titelListe(this), HYBRID_REIHENFOLGE);
  assert.notDeepEqual(HYBRID_REIHENFOLGE, [...HYBRID_REIHENFOLGE].sort(), 'die Reihenfolge der Attrappe darf nicht nach Titel geordnet sein');
});

Then('zeigt die Seite keine Gesamtzahl', async function () {
  assert.equal((await this.seite.locator('[data-test=anzahl]').innerText()).replace(/^·\s*/, ''), 'Die besten Treffer');
  assert.doesNotMatch(await this.seite.locator('#treffer-titel').innerText(), KEINE_ZAHL);
  assert.doesNotMatch(await this.seite.locator('.treffer .nur-screenreader[aria-live]').innerText(), KEINE_ZAHL);
});

Then(/^zeigt die Seite die Anzahl «(.+)»$/, async function (text) {
  await this.seite.locator('[data-test=anzahl]').filter({ hasText: text }).waitFor();
});

Then(/^zeigt die Seite eine Anzahl von mindestens (\d+) Treffern$/, async function (mindestens) {
  const text = await this.seite.locator('[data-test=anzahl]').innerText();
  const zahl = Number(text.replace(/[^\d]/g, ''));
  assert.ok(zahl >= Number(mindestens), text);
});

Then('gibt es kein Blättern', async function () {
  // Die Anzeige folgt der Antwort erst nach dem nächsten Rendern
  await this.seite.locator('nav.blaettern').waitFor({ state: 'detached', timeout: 5000 });
  assert.equal(await this.seite.locator('nav.blaettern').count(), 0);
});

Then('kann ich blättern', async function () {
  await this.seite.locator('nav.blaettern').waitFor();
});

Then(/^kann ich blättern und stehe auf «(Seite \d+ von \d+)»$/, async function (seite) {
  await this.seite.locator('nav.blaettern').getByText(seite, { exact: true }).waitFor();
});

Then(/^zeigt die Seite den Hinweis «(.+)»$/, async function (text) {
  const hinweis = this.seite.locator('[data-test=nur-volltext]');
  await hinweis.waitFor();
  assert.equal(await hinweis.getAttribute('role'), 'status');
  assert.ok((await hinweis.innerText()).includes(text), await hinweis.innerText());
});

Then('zeigt die Seite keinen Hinweis auf den Volltext', async function () {
  await this.seite.locator('[data-test=nur-volltext]').waitFor({ state: 'detached', timeout: 5000 });
  assert.equal(await this.seite.locator('[data-test=nur-volltext]').count(), 0);
});

Then(/^ist der Umschalter «Volltext» (ein|aus)$/, async function (zustand) {
  assert.equal(await umschalter(this).isChecked(), zustand === 'ein');
});

Then('gibt es den Umschalter «Volltext» nicht', async function () {
  assert.equal(await this.seite.getByRole('checkbox', { name: 'Volltext' }).count(), 0);
});

Then(/^ist die Sortierung «(.+)» gewählt$/, async function (name) {
  await filterOeffnen(this.seite);
  const auswahl = this.seite.locator('#filterleiste [name=sortierung] option:checked');
  for (let versuch = 0; versuch < 40 && (await auswahl.innerText()) !== name; versuch++) {
    await this.seite.waitForTimeout(50);
  }
  assert.equal(await auswahl.innerText(), name);
});

Then('liegen der Umschalter «Volltext» und der Hinweis ganz im Fenster', async function () {
  const breite = this.seite.viewportSize().width;
  for (const element of [umschalter(this), this.seite.locator('[data-test=nur-volltext]')]) {
    await element.waitFor();
    const box = await element.boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= breite, JSON.stringify({ box, breite }));
  }
});

Then('hat das Archiv im Modus hybrid mit degradiert geantwortet', async function () {
  const letzte = () =>
    this.weiterleitung.anfragen
      .filter((a) => a.methode === 'GET' && a.pfad === '/api/v1/dokumente' && a.query.get('modus') === 'hybrid')
      .at(-1);
  // Die Antwort wird im Journal asynchron ergänzt
  for (let versuch = 0; versuch < 100 && !letzte()?.antwort; versuch++) {
    await this.seite.waitForTimeout(50);
  }
  const anfrage = letzte();
  assert.ok(anfrage, 'keine Hybridsuche');
  assert.equal(anfrage.antwort?.modus, 'hybrid');
  assert.equal(anfrage.antwort?.degradiert, true, JSON.stringify(anfrage.antwort?.degradiert));
});

Then('zeigt die Suche dieselben Treffer wie vor dem Öffnen, ohne neue Anfrage', async function () {
  await this.seite.getByRole('heading', { name: 'Suche', level: 1 }).waitFor();
  await this.seite.waitForFunction((n) => document.querySelectorAll('[data-test=treffer]').length === n, this.trefferVorher.length);
  assert.deepEqual(await titelListe(this), this.trefferVorher);
  assert.equal(suchanfragen(this).length, this.anfragenVorher);
});
