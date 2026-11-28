// Seite «Mein Konto» und aufgeräumte Kopfzeile (Spec 06, Requirement 11) mit der Keycloak-Attrappe.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import axe from 'axe-core';
import { AUSSTELLER, basisUrl } from '../hilfen/keycloak-attrappe.mjs';

const KONTO = /^Mein Konto/;
const seiteKonto = (welt) => welt.seite.locator('app-konto-seite');
const abschnitt = (welt, titel) => seiteKonto(welt).locator('section.karte').filter({ has: welt.seite.getByRole('heading', { name: titel, exact: true }) });

/** Kein waagrechtes Scrollen und kein axe-Befund (WCAG 2.1 A/AA); mit `verzeichnis` zusätzlich ein Bildschirmfoto. */
export async function ohneUeberlaufUndAxe(welt, name, verzeichnis = process.env.KONTO_BILDER) {
  await welt.seite.waitForTimeout(250);
  await welt.seite.addScriptTag({ content: axe.source });
  const ergebnis = await welt.seite.evaluate(async () => ({
    ueberlauf: [document.documentElement, document.querySelector('mat-sidenav-content')]
      .filter(Boolean).some((element) => element.scrollWidth > element.clientWidth),
    befunde: (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    })).violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) })),
  }));
  assert.equal(ergebnis.ueberlauf, false, 'horizontaler Überlauf');
  assert.deepEqual(ergebnis.befunde, []);
  if (verzeichnis) {
    const breite = welt.seite.viewportSize().width;
    const schema = await welt.seite.evaluate(() => (matchMedia('(prefers-color-scheme: dark)').matches ? 'dunkel' : 'hell'));
    await welt.seite.screenshot({ path: `${verzeichnis}/${name}-${breite}-${schema}.png`, fullPage: true });
  }
}

/** Namen der Bedienelemente rechts in der Kopfzeile (ohne Menüknopf, Titel und Suche). */
async function rechtsInDerKopfzeile(welt) {
  const kopf = welt.seite.locator('header.kopf');
  await kopf.waitFor();
  return kopf.evaluate((element) => {
    const abstand = element.querySelector('.abstand');
    return [...element.querySelectorAll('a, button')]
      .filter((e) => abstand && abstand.compareDocumentPosition(e) & Node.DOCUMENT_POSITION_FOLLOWING)
      .map((e) => {
        // Zugänglicher Name vereinfacht: aria-label oder der Text ohne aria-hidden-Teile
        if (e.hasAttribute('aria-label')) return e.getAttribute('aria-label').trim();
        const teile = [];
        const sammeln = (knoten) => {
          if (knoten.nodeType === Node.TEXT_NODE) teile.push(knoten.textContent);
          else if (knoten.nodeType === Node.ELEMENT_NODE && knoten.getAttribute('aria-hidden') !== 'true') knoten.childNodes.forEach(sammeln);
        };
        sammeln(e);
        return teile.join(' ').replace(/\s+/g, ' ').replace(/ :/g, ':').trim();
      });
  });
}

Given(/^im Browser ist das Farbschema «(hell|dunkel)» gespeichert$/, async function (schema) {
  await this.seite.goto(basisUrl);
  await this.seite.evaluate((wert) => localStorage.setItem('archiv.farbschema', wert), schema);
  await this.seite.reload();
});

When('ich «Mein Konto» in der Kopfzeile wähle', async function () {
  // Schon vor der Navigation: jede Anfrage an die API, die Daten senden würde (Req 11.12)
  this.kontoSchreibend = [];
  this.seite.on('request', (anfrage) => {
    if (new URL(anfrage.url()).pathname.startsWith('/api/') && anfrage.method() !== 'GET') {
      this.kontoSchreibend.push(`${anfrage.method()} ${new URL(anfrage.url()).pathname}`);
    }
  });
  await this.seite.locator('header.kopf').getByRole('link', { name: KONTO }).click();
  await this.seite.waitForURL((url) => url.pathname === '/konto');
  await seiteKonto(this).locator('h1').waitFor();
});

Then('meldet «Mein Konto» die abgelaufene Sitzung und zeigt keine Angaben mehr', { timeout: 40_000 }, async function () {
  await seiteKonto(this).locator('[data-test=konto-abgelaufen]').waitFor({ timeout: 30_000 });
  for (const titel of ['Angaben zur Person', 'Anmeldung und Sicherheit', 'Hilfe und Einführung']) {
    assert.equal(await seiteKonto(this).getByRole('heading', { name: titel, exact: true }).count(), 0, titel);
  }
  assert.equal(await seiteKonto(this).getByText(this.keycloak.benutzer.name).count(), 0, 'Name sichtbar');
});

When('ich mich unter «Mein Konto» abmelde', async function () {
  if (new URL(this.seite.url()).pathname !== '/konto') {
    await this.seite.locator('header.kopf').getByRole('link', { name: KONTO }).click();
    await this.seite.waitForURL((url) => url.pathname === '/konto');
  }
  await seiteKonto(this).getByRole('button', { name: 'Abmelden', exact: true }).click();
});

Then(/^zeigt die Kopfzeile rechts nur «(.+)» und «(.+)»$/, async function (erstes, zweites) {
  assert.deepEqual(await rechtsInDerKopfzeile(this), [erstes, zweites]);
});

Then('zeigt die Kopfzeile ohne Anmeldung rechts nur den Farbschema-Knopf und «Hilfe»', async function () {
  const namen = await rechtsInDerKopfzeile(this);
  assert.equal(namen.length, 2, namen.join(', '));
  assert.match(namen[0], /^(Helles|Dunkles) Farbschema verwenden$/);
  assert.equal(namen[1], 'Hilfe');
});

Then('fehlt das Benutzerelement in der Kopfzeile', async function () {
  assert.equal(await this.seite.locator('header.kopf').getByRole('link', { name: KONTO }).count(), 0);
});

Then(/^zeigt das Benutzerelement die Initialen «(.+)» und ab 960 px auch den Namen «(.+)»$/, async function (initialen, name) {
  const element = this.seite.locator('header.kopf [data-test=benutzer]');
  assert.equal((await element.locator('.avatar').innerText()).trim(), initialen);
  assert.equal(await element.locator('.avatar').isVisible(), true);
  const breit = this.seite.viewportSize().width >= 960;
  // Unter 960 px nur sichtbar ausgeblendet (bleibt Teil des zugänglichen Namens)
  assert.equal(((await element.locator('.wer').boundingBox())?.width ?? 0) > 20, breit);
  if (breit) assert.match(await element.locator('.wer').innerText(), new RegExp(`^${name}`));
});

Then('ist das Benutzerelement als aktuelle Seite markiert', async function () {
  assert.equal(await this.seite.locator('header.kopf [data-test=benutzer]').getAttribute('aria-current'), 'page');
});

Then(/^ist «Mein Konto» ohne Überlauf und axe-Befund$/, async function () {
  await ohneUeberlaufUndAxe(this, 'mein-konto');
});

Then('hat «Mein Konto» keine Daten an das Archiv gesendet', async function () {
  await this.seite.waitForLoadState('networkidle');
  assert.deepEqual(this.kontoSchreibend, []);
});

Then(/^zeigt «Mein Konto» die Abschnitte «(.+)»$/, async function (liste) {
  const titel = await seiteKonto(this).locator('section.karte h2').allInnerTexts();
  assert.deepEqual(titel.map((t) => t.trim()), liste.split(', '));
});

Then(/^zeigt «Mein Konto» unter «Angaben zur Person» «(.+)»$/, async function (liste) {
  const karte = abschnitt(this, 'Angaben zur Person');
  const paare = await karte.locator('dl').evaluate((dl) => {
    const dt = [...dl.querySelectorAll('dt')].map((e) => e.textContent.trim());
    const dd = [...dl.querySelectorAll('dd')].map((e) => e.textContent.trim());
    return dt.map((name, i) => `${name}: ${dd[i]}`);
  });
  assert.deepEqual(paare, liste.split('; '));
  assert.equal(await karte.locator('input, textarea, select, [contenteditable]').count(), 0);
});

Then('zeigt «Mein Konto» weder Token noch Benutzerkennung', async function () {
  const text = await seiteKonto(this).innerText();
  const html = await seiteKonto(this).innerHTML();
  assert.equal(text.includes(this.keycloak.benutzer.id) || html.includes(this.keycloak.benutzer.id), false, 'Benutzerkennung');
  for (const token of this.keycloak.tokens) assert.equal(html.includes(token.wert), false, `${token.typ}-Token`);
});

Then(/^bietet «Mein Konto» die Einstiege «(.+)» in einem neuen Tab$/, async function (liste) {
  const links = abschnitt(this, 'Anmeldung und Sicherheit').getByRole('link');
  const gefunden = await links.evaluateAll((alle) => alle.map((a) => ({
    text: a.textContent.trim(), name: a.getAttribute('aria-label'), href: a.getAttribute('href'),
    target: a.getAttribute('target'), rel: a.getAttribute('rel'),
  })));
  const anmeldung = `${AUSSTELLER}/account/account-security/signing-in`;
  const geraete = `${AUSSTELLER}/account/account-security/device-activity`;
  assert.deepEqual(gefunden, liste.split(', ').map((text) => ({
    text,
    name: `${text} (öffnet die Kontoverwaltung in einem neuen Tab)`,
    href: text === 'Angemeldete Geräte' ? geraete : anmeldung,
    target: '_blank',
    rel: 'noopener noreferrer',
  })));
});

Then('enthält «Mein Konto» kein Eingabefeld für Zugangsdaten', async function () {
  assert.equal(await seiteKonto(this).locator('input, textarea, form').count(), 0);
});

Then(/^sind die Einstiege in die Kontoverwaltung deaktiviert und «Mein Konto» sagt warum$/, async function () {
  const karte = abschnitt(this, 'Anmeldung und Sicherheit');
  await karte.locator('[data-test=konto-offline]').waitFor();
  assert.equal(await karte.getByRole('link').count(), 0);
  const knoepfe = karte.getByRole('button');
  assert.equal(await knoepfe.count(), 4);
  for (const knopf of await knoepfe.all()) assert.equal(await knopf.isDisabled(), true);
  assert.match(await karte.locator('[data-test=konto-offline]').innerText(), /Ohne Verbindung/);
});

When(/^ich unter «Darstellung» «(.+)» wähle$/, async function (name) {
  await abschnitt(this, 'Darstellung').getByRole('button', { name, exact: true }).click();
});

Then(/^ist unter «Darstellung» «(.+)» gewählt$/, async function (name) {
  const gewaehlt = abschnitt(this, 'Darstellung').locator('button[aria-pressed=true]');
  assert.equal((await gewaehlt.innerText()).trim(), name);
});

Then(/^gilt das Farbschema «(hell|dunkel)» und ist nur im Browser gemerkt$/, async function (schema) {
  assert.deepEqual(
    await this.seite.evaluate(() => [document.documentElement.dataset.farbschema, localStorage.getItem('archiv.farbschema')]),
    [schema, schema],
  );
});

Then('folgt die App dem Farbschema des Geräts und hat keine Wahl gespeichert', async function () {
  assert.deepEqual(
    await this.seite.evaluate(() => [document.documentElement.dataset.farbschema ?? null, localStorage.getItem('archiv.farbschema')]),
    [null, null],
  );
});

When(/^ich auf «Mein Konto» «(.+)» wähle$/, async function (name) {
  await seiteKonto(this).getByRole(name === 'Lokale Einstellungen entfernen' ? 'button' : 'link', { name, exact: true }).click();
});

Then('zeigt die Startseite die Einführung ab Schritt 1', async function () {
  await this.seite.waitForURL((url) => url.pathname === '/' && url.searchParams.has('einfuehrung'));
  await this.seite.locator('app-start .einfuehrung').getByText(/^Schritt 1 von \d+$/).waitFor();
});

Then('fehlt auf «Mein Konto» der Abschnitt «Hilfe und Einführung»', async function () {
  assert.equal(await seiteKonto(this).getByRole('heading', { name: 'Hilfe und Einführung' }).count(), 0);
});

Then(/^zeigt «Mein Konto» «(.+)»$/, async function (text) {
  await abschnitt(this, 'Dieses Gerät').getByText(text, { exact: true }).waitFor();
});

Then(/^führt auf «Mein Konto» der Link zur Warteschlange zur Seite «(.+)»$/, async function (pfad) {
  const link = abschnitt(this, 'Dieses Gerät').getByRole('link');
  assert.equal(await link.getAttribute('href'), pfad);
});

Then(/^ist auf «Mein Konto» der Knopf «Abmelden» bedienbar$/, async function () {
  assert.equal(await abschnitt(this, 'Abmelden').getByRole('button', { name: 'Abmelden', exact: true }).isEnabled(), true);
});

Given('auf diesem Gerät liegen lokale Einstellungen von mir und von anderen', async function () {
  const sub = this.keycloak.benutzer.id;
  const eigen = createHash('sha256').update(`${this.keycloak.benutzer.mandant}:${sub}`).digest('hex');
  this.lokal = { eigen, fremd: 'f'.repeat(64), sub };
  await this.seite.evaluate(({ eigen, fremd, sub }) => {
    localStorage.setItem('archiv.farbschema', 'dunkel');
    localStorage.setItem(`archiv.branding.${location.hostname}`, JSON.stringify({ name: 'Archiv', logoUrl: null, primaerfarbe: '#eeb111', sprache: 'de-CH' }));
    localStorage.setItem('archiv.einfuehrung', JSON.stringify({ v: 1, eintraege: [
      { k: fremd, version: 1, art: 'UEBERSPRUNGEN', am: '2026-11-01' },
      { k: eigen, version: 1, art: 'ABGESCHLOSSEN', am: '2026-11-02' },
    ] }));
    localStorage.setItem('archiv.fremd', 'bleibt');
    sessionStorage.setItem(`archiv.eingangskorb.entwurf.v1:${encodeURIComponent(sub)}:d1`, '{"version":1}');
    sessionStorage.setItem('archiv.eingangskorb.entwurf.v1:anderer:d2', '{"version":1}');
  }, this.lokal);
});

Then('nennt die Rückfrage alles, was entfernt wird und was bleibt', async function () {
  const frage = await seiteKonto(this).getByRole('alert').innerText();
  for (const teil of ['Farbschemas', 'Einführung', 'Entwürfe', 'Erscheinungsbild', 'Warteschlange bleiben erhalten', 'angemeldet']) {
    assert.ok(frage.includes(teil), `${teil} fehlt in: ${frage}`);
  }
  assert.equal(await this.seite.evaluate(() => localStorage.getItem('archiv.farbschema')), 'dunkel', 'vor der Bestätigung entfernt');
});

When(/^ich die Rückfrage auf «Mein Konto» mit «(.+)» bestätige$/, async function (name) {
  await seiteKonto(this).getByRole('button', { name, exact: true }).click();
  await seiteKonto(this).getByRole('status').filter({ hasText: 'Die lokalen Einstellungen wurden entfernt.' }).waitFor();
});

Then('sind nur meine lokalen Einstellungen entfernt', async function () {
  const stand = await this.seite.evaluate(({ sub }) => ({
    farbschema: localStorage.getItem('archiv.farbschema'),
    attribut: document.documentElement.dataset.farbschema ?? null,
    branding: localStorage.getItem(`archiv.branding.${location.hostname}`),
    vermerke: JSON.parse(localStorage.getItem('archiv.einfuehrung') ?? 'null')?.eintraege.map((e) => e.k),
    fremd: localStorage.getItem('archiv.fremd'),
    eigenerEntwurf: sessionStorage.getItem(`archiv.eingangskorb.entwurf.v1:${encodeURIComponent(sub)}:d1`),
    fremderEntwurf: sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:anderer:d2'),
  }), this.lokal);
  assert.deepEqual(stand, {
    farbschema: null,
    attribut: null,
    branding: null,
    vermerke: [this.lokal.fremd],
    fremd: 'bleibt',
    eigenerEntwurf: null,
    fremderEntwurf: '{"version":1}',
  });
});

Then('bin ich weiterhin angemeldet', async function () {
  await this.seite.locator('header.kopf').getByRole('link', { name: KONTO }).waitFor();
  assert.equal(this.keycloak.abmeldungen.length, 0);
});

When('das Gerät sofort offline geht', async function () {
  // Die Keycloak-Attrappe antwortet über eine Route auch offline; wie im echten Netz scheitert die Erneuerung hier
  await this.kontext.route(`${AUSSTELLER}/**`, (route) => route.abort('internetdisconnected'));
  await this.kontext.setOffline(true);
});

Then(/^ist die Seite «\/konto» als Ziel nach der Anmeldung gemerkt$/, async function () {
  await this.seite.waitForURL((url) => url.pathname === '/' && url.searchParams.get('ziel') === '/konto');
});

Then('zeigt «Dieses Gerät» keinen Zähler und keinen Link zur Warteschlange', async function () {
  const karte = abschnitt(this, 'Dieses Gerät');
  await karte.getByRole('button', { name: 'Lokale Einstellungen entfernen', exact: true }).waitFor();
  assert.equal(await karte.locator('[data-test=konto-aufnahmen]').count(), 0);
  assert.equal(await karte.getByRole('link').count(), 0);
});
