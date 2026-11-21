import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';

// Seite /hilfe (Spec 07, Aufgabe 22.2; features/hilfe/hilfe-seite-pwa.feature)

const inhalt = (seite) => seite.getByRole('navigation', { name: 'Inhalt der Anleitung' });
const abschnitt = (seite, anker) => seite.locator(`.abschnitte [data-anker="${anker}"]`);

/** Wartet, bis keine Datei der Anleitung mehr lädt (Fortschrittsbalken weg). */
async function geladen(seite) {
  await seite.waitForFunction(
    () =>
      document.querySelectorAll('app-hilfe-seite app-anleitung-text').length > 0 &&
      !document.querySelector('app-hilfe-seite mat-progress-bar'),
  );
}

// Navigation innerhalb der App wie ein Link: Ein Neuladen verlöre die Anmeldung (Tokens nur im Arbeitsspeicher).
// Der Router der App folgt dem popstate-Ereignis.
When(/^ich in der App «(.+)» aufrufe$/, async function (adresse) {
  await this.seite.evaluate((ziel) => {
    history.pushState(null, '', ziel);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, adresse);
  await geladen(this.seite);
});

Then(/^sehe ich die Seite «(.+)» mit dem Namen aus dem Branding$/, async function (titel) {
  const kopf = this.seite.locator('app-hilfe-seite .seitenkopf');
  await kopf.getByRole('heading', { level: 1, name: titel, exact: true }).waitFor();
  // textContent, nicht innerText: Die Vorzeile steht per CSS in Versalien
  const name = ((await kopf.locator('.vorzeile').textContent()) ?? '').trim();
  assert.ok(name.length > 0, 'Vorzeile ohne Name');
  await this.seite.waitForFunction(([t, n]) => document.title === `${t} · ${n}`, [titel, name]);
});

Then(/^sehe ich das Inhaltsverzeichnis «(.+)» von «(.+)» bis «(.+)»$/, async function (name, erster, letzter) {
  await geladen(this.seite);
  const nav = this.seite.getByRole('navigation', { name, exact: true });
  const eintraege = (await nav.getByRole('link').allInnerTexts()).map((t) => t.trim());
  assert.equal(eintraege[0], erster, eintraege.join(', '));
  assert.equal(eintraege.at(-1), letzter, eintraege.join(', '));
});

Then('folgen die Abschnitte dem Inhaltsverzeichnis', async function () {
  const ziele = await inhalt(this.seite)
    .getByRole('link')
    .evaluateAll((links) => links.map((a) => new URL(a.href).hash.slice(1)));
  const abschnitte = await this.seite
    .locator('app-hilfe-seite .abschnitte')
    .locator('h2[tabindex="-1"], h3[tabindex="-1"]')
    .evaluateAll((h) => h.map((e) => e.id));
  assert.deepEqual(abschnitte, ziele);
});

Then(/^zeigt die Anleitung keinen der Abschnitte «(.+)»$/, async function (liste) {
  const ziele = await inhalt(this.seite)
    .getByRole('link')
    .evaluateAll((links) => links.map((a) => new URL(a.href).hash.slice(1)));
  for (const anker of liste.split(', ')) {
    assert.ok(!ziele.includes(anker), `«${anker}» im Inhaltsverzeichnis`);
    assert.equal(await this.seite.locator(`app-hilfe-seite [id="${anker}"], [data-anker="${anker}"]`).count(), 0, anker);
  }
});

When(/^ich im Inhaltsverzeichnis «(.+)» wähle$/, async function (titel) {
  await inhalt(this.seite).getByRole('link', { name: titel, exact: true }).click();
});

When('ich im Browser zurückgehe', async function () {
  await this.seite.goBack();
});

Then(/^steht die Adresse auf «(.+)»$/, async function (adresse) {
  await this.seite.waitForURL((url) => url.pathname + url.hash === adresse);
});

Then(/^liegt der Fokus auf der sichtbaren Überschrift «(.+)»$/, async function (titel) {
  const ueberschrift = this.seite.locator('app-hilfe-seite .abschnitte').getByRole('heading', { name: titel, exact: true });
  await this.seite.waitForFunction(
    (t) => document.activeElement?.matches('h2, h3') && document.activeElement.textContent.trim() === t,
    titel,
  );
  // Nicht unter der klebenden Kopfzeile und nicht unterhalb des Fensters
  const lage = await ueberschrift.evaluate((h) => {
    const r = h.getBoundingClientRect();
    return { oben: r.top, unten: r.bottom, kopf: document.querySelector('header.kopf').getBoundingClientRect().bottom, hoehe: innerHeight };
  });
  assert.ok(lage.oben >= lage.kopf - 1 && lage.unten <= lage.hoehe, JSON.stringify(lage));
});

Then(/^zeigt die Seite «(.+)» von oben, ohne Meldung$/, async function (titel) {
  await this.seite.locator('app-hilfe-seite').getByRole('heading', { level: 1, name: titel }).waitFor();
  await this.seite.waitForFunction(() => document.querySelector('mat-sidenav-content').scrollTop === 0);
  assert.equal(await this.seite.getByRole('alert').count(), 0);
  assert.equal(await inhalt(this.seite).locator('[aria-current]').count(), 0);
});

Then(/^steht das Inhaltsverzeichnis (als Karte über den Abschnitten|links neben den Abschnitten und bleibt beim Scrollen sichtbar)$/, async function (lage) {
  await geladen(this.seite);
  const nav = inhalt(this.seite);
  const erster = this.seite.locator('app-hilfe-seite .abschnitte .abschnitt').first();
  const n = await nav.boundingBox();
  const a = await erster.boundingBox();
  if (lage.startsWith('als Karte')) {
    assert.match((await nav.getAttribute('class')) ?? '', /\bkarte\b/);
    assert.ok(n.y + n.height <= a.y, `Inhalt ${JSON.stringify(n)} nicht über ${JSON.stringify(a)}`);
  } else {
    assert.ok(n.x + n.width <= a.x && Math.abs(n.y - a.y) < 2, `Inhalt ${JSON.stringify(n)} nicht links von ${JSON.stringify(a)}`);
    assert.equal(await nav.evaluate((e) => getComputedStyle(e).position), 'sticky');
    await this.seite.evaluate(() => {
      const bereich = document.querySelector('mat-sidenav-content');
      bereich.scrollTop = bereich.scrollHeight;
    });
    const danach = await nav.boundingBox();
    assert.ok(danach.y >= 0 && danach.y < 800, `Inhalt nach dem Scrollen bei ${danach.y}`);
  }
});

Then('hat die Seite keinen waagrechten Bildlauf', async function () {
  const breiter = await this.seite.evaluate(() =>
    [document.documentElement, document.querySelector('mat-sidenav-content')].some((e) => e.scrollWidth > e.clientWidth),
  );
  assert.equal(breiter, false);
});

// Weder Server noch Service Worker liefern die Datei (Req 7.6; der Service Worker ist in den Szenarien aus)
Given(/^die Datei «(.+)» ist nicht ladbar$/, async function (pfad) {
  this.sperren ??= new Map();
  const sperre = { url: (url) => url.pathname === pfad, antwort: (route) => route.abort('failed') };
  this.sperren.set(pfad, sperre);
  await this.seite.route(sperre.url, sperre.antwort);
});

When(/^die Datei «(.+)» wieder ladbar ist$/, async function (pfad) {
  const sperre = this.sperren.get(pfad);
  await this.seite.unroute(sperre.url, sperre.antwort);
});

When(/^ich beim Abschnitt «(.+)» «(.+)» wähle$/, async function (anker, knopf) {
  await abschnitt(this.seite, anker).getByRole('button', { name: knopf, exact: true }).click();
});

Then(/^zeigt der Abschnitt «(.+)» den Hinweis «(.+)» mit dem Knopf «(.+)»$/, async function (anker, text, knopf) {
  const teil = abschnitt(this.seite, anker);
  const hinweis = teil.locator('[data-test="anleitung-fehler"]');
  await hinweis.waitFor();
  assert.equal((await hinweis.innerText()).trim(), text);
  assert.equal(await hinweis.getAttribute('role'), 'alert');
  await teil.getByRole('button', { name: knopf, exact: true }).waitFor();
});

Then(/^zeigt der Abschnitt «(.+)» seinen Text$/, async function (anker) {
  const teil = abschnitt(this.seite, anker);
  await teil.locator('app-anleitung-text .text').waitFor();
  assert.ok((await teil.locator('app-anleitung-text .text').innerText()).trim().length > 0);
  assert.equal(await teil.locator('[data-test="anleitung-fehler"]').count(), 0);
});
