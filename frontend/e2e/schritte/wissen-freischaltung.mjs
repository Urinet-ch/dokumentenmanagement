// Wissen je Mandant freischalten (Spec 04, Req 22.5): Die Attrappe des Archivs liefert /api/v1/mandant/funktionen; die
// Testdaten schalten Wissen für jeden Mandanten ein, diese Schritte schalten es für einen Mandanten aus oder lassen die
// Abfrage scheitern. Navigiert wird innerhalb der App, weil ein Neuladen die Anmeldung verlöre (Tokens im Speicher).
import { Given, Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';
import { archivAttrappe } from '../hilfen/archiv-attrappe.mjs';
import { DOKUMENTE } from '../hilfen/suche-attrappe.mjs';

const DOKUMENT = `/dokument/${DOKUMENTE[0].id}`;
const EINSTELLUNGEN = '/api/v1/mandant/einstellungen';
const KATALOG = [{
  gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat-Aufbewahrung',
  beschreibung: 'Aufbewahrung der Chatnachrichten', typ: 'GANZZAHL', aktuellerWert: 12, standard: 12,
  minimum: 1, maximum: 60, einheit: 'Monate', wirksamAb: 'Beim nächsten Aufräumlauf',
}, {
  gruppe: 'WISSEN', schluessel: 'wissen.budgetTokens', name: 'Tokenbudget je Ingest',
  beschreibung: 'Synthetisches Budget', typ: 'GANZZAHL', aktuellerWert: 600000, standard: 600000,
  minimum: 10000, maximum: 5000000, einheit: 'Tokens', wirksamAb: 'Beim nächsten Lauf',
}];

/** Ein Pfad der Wissensbasis: ein Abschnitt, der mit «wissen» beginnt (/wissen…, /wissensseiten, /dokumente/{id}/wissensquelle). */
const wissenspfad = (pfad) => pfad.split('/').some((teil) => teil.startsWith('wissen'));

async function inApp(seite, ziel) {
  await seite.evaluate((adresse) => {
    history.pushState(null, '', adresse);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, ziel);
  await seite.waitForFunction((adresse) => location.pathname === adresse, ziel);
}

async function anzahl(locator) {
  return locator.count();
}

Given(/^Wissen ist für den Mandanten «(.+)» nicht freigeschaltet$/, async function (mandant) {
  (await archivAttrappe(this)).wissenAus.add(mandant);
});

Given('die Abfrage der Mandantenfunktionen scheitert', async function () {
  (await archivAttrappe(this)).funktionenStatus = 503;
});

Given('die Einstellungen enthalten die Gruppe der Wissensbasis', async function () {
  await this.seite.route((url) => url.pathname === EINSTELLUNGEN || url.pathname === '/api/v1/konfiguration/versionen',
    (route) => new URL(route.request().url()).pathname === EINSTELLUNGEN
      ? route.fulfill({ json: { version: 3, katalog: KATALOG }, headers: { ETag: '"3"' } })
      : route.fulfill({ json: [] }));
});

Then(/^zeigt das Menü (die|keine) Punkte «Wissen» und «Entwürfe»$/, async function (art) {
  const nav = this.seite.getByRole('navigation', { name: 'Hauptnavigation' });
  await nav.getByRole('link', { name: /Suche/ }).first().waitFor();
  if (art === 'die') {
    await nav.locator('a[href="/wissen"]').waitFor();
    await nav.locator('a[href="/wissen/entwuerfe"]').waitFor();
  } else {
    // erst nach geladenen Funktionen: dann steht fest, dass der Bereich verborgen bleibt
    await this.seite.waitForFunction(() => performance.getEntriesByType('resource')
      .some((eintrag) => new URL(eintrag.name).pathname === '/api/v1/mandant/funktionen'));
    assert.equal(await anzahl(nav.locator('a[href^="/wissen"]')), 0, 'Wissen im Menü');
  }
});

Then(/^zeigt die Dokumentansicht (die|keine) Aktion «Als Wissensquelle verwenden»$/, async function (art) {
  await inApp(this.seite, DOKUMENT);
  await this.seite.getByRole('heading', { level: 1 }).first().waitFor();
  await this.seite.locator('[data-test=status]').waitFor();
  const knopf = this.seite.getByRole('button', { name: 'Als Wissensquelle verwenden', exact: true });
  if (art === 'die') {
    await knopf.waitFor();
  } else {
    assert.equal(await anzahl(knopf), 0, 'Aktion «Als Wissensquelle verwenden»');
    assert.equal(await anzahl(this.seite.locator('app-wissen-verwendung')), 0, 'Wissensseiten in der Dokumentansicht');
  }
});

Then(/^zeigt der Chat (die|keine) Wahl des Modus$/, async function (art) {
  await inApp(this.seite, '/chat');
  await this.seite.getByRole('textbox').first().waitFor();
  const wahl = this.seite.getByRole('group', { name: 'Modus für ein neues Gespräch', exact: true });
  if (art === 'die') {
    await wahl.waitFor();
  } else {
    assert.equal(await anzahl(wahl), 0, 'Wahl des Chat-Modus');
    assert.equal(await anzahl(this.seite.getByText('Wissen befragen', { exact: true })), 0, 'Modus «Wissen befragen»');
  }
});

Then(/^zeigt die Hilfe (den|keinen) Abschnitt «Wissen»$/, async function (art) {
  await inApp(this.seite, '/hilfe');
  const inhalt = this.seite.locator('app-hilfe-seite nav');
  await inhalt.locator('a').first().waitFor();
  const wissen = this.seite.locator('app-hilfe-seite nav a[href$="#wissen"]');
  if (art === 'den') {
    await wissen.waitFor();
  } else {
    assert.equal(await anzahl(wissen), 0, 'Abschnitt «Wissen» in der Hilfe');
    assert.equal(await anzahl(this.seite.locator('app-hilfe-seite nav a[href*="#wissen"]')), 0, 'Unterabschnitte «Wissen»');
  }
});

Then(/^zeigen die Einstellungen (die|keine) Gruppe «Wissensbasis»$/, async function (art) {
  await inApp(this.seite, '/verwaltung/einstellungen');
  await this.seite.getByRole('heading', { level: 2, name: 'Chat', exact: true }).waitFor();
  const gruppe = this.seite.getByRole('heading', { level: 2, name: 'Wissensbasis', exact: true });
  if (art === 'die') {
    await gruppe.waitFor();
  } else {
    assert.equal(await anzahl(gruppe), 0, 'Gruppe «Wissensbasis»');
  }
});

Then('ruft die PWA keine Schnittstelle der Wissensbasis auf', function () {
  const pfade = (this.apiAnfragen ?? []).map((adresse) => new URL(adresse).pathname).filter(wissenspfad);
  assert.deepEqual(pfade, []);
});

Then('weist die App nirgends auf Wissen als Zusatzleistung hin', async function () {
  const text = await this.seite.locator('body').innerText();
  assert.doesNotMatch(text, /freischalt|Zusatzleistung|Wissen befragen/i);
});

Then('sind Dokumentansicht und Chat ohne Wissen bei 375 und 1280 Pixeln in hell und dunkel ohne axe-Befund',
  { timeout: 90_000 }, async function () {
    for (const pfad of [DOKUMENT, '/chat']) {
      await inApp(this.seite, pfad);
      await this.seite.getByRole('heading', { level: 1 }).first().waitFor();
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
          assert.equal(ergebnis.ueberlauf, false, `${pfad}, ${breite}px, ${schema}: horizontaler Überlauf`);
          assert.deepEqual(ergebnis.befunde, [], `${pfad}, ${breite}px, ${schema}: ${JSON.stringify(ergebnis.befunde)}`);
        }
      }
    }
  });

// Abschalten während einer offenen Sitzung (Spec 04, Req 22.2, 22.5): Die App lädt die Funktionen alle 30 s neu (das
// Archiv speichert höchstens 30 s zwischen); bei der ersten Antwort 403 WISSEN_NICHT_FREIGESCHALTET verschwindet der
// Bereich sofort, beides ohne Neuladen. Die Uhr der Seite ist dafür steuerbar.
Given('die Uhr der App ist steuerbar', async function () {
  await this.seite.clock.install();
});

Given(/^Wissen für den Mandanten «(.+)» während der Sitzung abgeschaltet wird$/, async function (mandant) {
  (await archivAttrappe(this)).wissenAus.add(mandant);
  // spätere Routen gehen in Playwright vor: jede Wissensanfrage antwortet wie das Archiv ohne Freischaltung
  await this.seite.route((url) => url.pathname.startsWith('/api/v1/') && wissenspfad(url.pathname),
    (route) => route.fulfill({
      status: 403,
      contentType: 'application/problem+json',
      json: { status: 403, fehlercode: 'WISSEN_NICHT_FREIGESCHALTET', title: 'Nicht verfügbar' },
    }));
});

Given('mehr als 30 Sekunden vergehen', async function () {
  await this.seite.clock.fastForward(31_000);
  // danach wieder mit der Wanduhr, damit Warteschritte mit requestAnimationFrame weiterlaufen
  await this.seite.clock.resume();
});

Given(/^ich in der App die Seite «(.+)» öffne$/, async function (ziel) {
  await this.seite.evaluate((adresse) => {
    history.pushState(null, '', adresse);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, ziel);
});

Given(/^ich im Chat den Modus «(.+)» wähle$/, async function (modus) {
  await this.seite.getByRole('textbox').first().waitFor();
  const knopf = this.seite.getByRole('group', { name: 'Modus für ein neues Gespräch', exact: true })
    .getByRole('button', { name: modus, exact: true });
  await knopf.click();
  await this.seite.waitForFunction((name) => [...document.querySelectorAll('button[aria-pressed="true"]')]
    .some((b) => b.textContent.trim() === name), modus);
});

Then('ist im Chat kein Gespräch im Modus «Wissen befragen» mehr offen', async function () {
  await this.seite.getByRole('group', { name: 'Modus für ein neues Gespräch', exact: true })
    .waitFor({ state: 'detached' });
  assert.equal(await anzahl(this.seite.getByText('Wissen befragen', { exact: true })), 0, 'Modus «Wissen befragen»');
  await this.seite.getByRole('textbox').first().waitFor();
});

Then('verschwinden die Punkte «Wissen» und «Entwürfe» aus dem Menü', async function () {
  // ohne Warten im Seitenkontext: die Uhr der Seite ist in diesem Szenario simuliert
  const nav = this.seite.getByRole('navigation', { name: 'Hauptnavigation' });
  await nav.locator('a[href="/wissen"]').waitFor({ state: 'detached' });
  await nav.locator('a[href="/wissen/entwuerfe"]').waitFor({ state: 'detached' });
  assert.equal(await anzahl(nav.locator('a[href^="/wissen"]')), 0, 'Wissen im Menü');
});
