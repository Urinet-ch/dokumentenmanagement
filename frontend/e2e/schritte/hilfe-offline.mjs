import { After, Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { archivAttrappe } from '../hilfen/archiv-attrappe.mjs';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';

async function installieren(welt, url) {
  await welt.kontext.close();
  welt.kontext = await welt.browser.newContext({
    locale: 'de-CH',
    viewport: { width: 390, height: 844 },
    serviceWorkers: 'allow',
  });
  welt.seite = await welt.kontext.newPage();
  welt.archiv = undefined;
  welt.keycloak = undefined;
  await archivAttrappe(welt);
  await welt.seite.goto(url);
  await welt.seite.waitForFunction(async () => {
    const registrierung = await navigator.serviceWorker.getRegistration();
    if (!registrierung?.active) return false;
    const manifest = await (await fetch('/ngsw.json')).json();
    const gruppen = manifest.assetGroups.filter((gruppe) => ['app', 'anleitung'].includes(gruppe.name));
    if (gruppen.length !== 2 || gruppen.some((gruppe) => gruppe.urls.length === 0)) return false;
    for (const gruppe of gruppen) {
      const name = (await caches.keys()).find((n) => n.endsWith(`:assets:${gruppe.name}:cache`));
      if (!name) return false;
      const geladen = (await (await caches.open(name)).keys()).map((anfrage) => new URL(anfrage.url).pathname);
      if (!gruppe.urls.every((url) => geladen.includes(url))) return false;
    }
    return true;
  }, undefined, { timeout: 110_000, polling: 500 });
  const dateien = readdirSync('public/anleitung').map((name) => `/anleitung/${name}`).sort();
  const manifestDateien = await welt.seite.evaluate(async () =>
    (await (await fetch('/ngsw.json')).json()).assetGroups.find((gruppe) => gruppe.name === 'anleitung').urls.sort(),
  );
  assert.deepEqual(manifestDateien, dateien);
  await welt.seite.reload();
  await welt.seite.waitForFunction(() => !!navigator.serviceWorker.controller);
}

Given('die PWA mit der Anleitung ist installiert', { timeout: 120_000 }, async function () {
  await installieren(this, basisUrl);
});

Then('sind alle sichtbaren Abschnitte der Anleitung geladen', async function () {
  const texte = this.seite.locator('app-hilfe-seite app-anleitung-text');
  assert.ok(await texte.count() > 0);
  await this.seite.waitForFunction(() =>
    [...document.querySelectorAll('app-hilfe-seite app-anleitung-text')]
      .every((text) => text.querySelector('.text') && !text.querySelector('[data-test=anleitung-fehler]')),
  );
  assert.equal(await this.seite.locator('app-hilfe-seite [data-test=anleitung-fehler]').count(), 0);
});

Then('sind alle sichtbaren Abschnitte der Hilfe-Leiste geladen', { timeout: 120_000 }, async function () {
  const leiste = this.seite.getByRole('dialog', { name: 'Hilfe', exact: true });
  const nav = leiste.getByRole('navigation', { name: 'Inhalt der Anleitung' });
  const links = nav.getByRole('link');
  const titel = (await links.allInnerTexts()).map((text) => text.trim());
  assert.ok(titel.length > 0);
  for (const name of titel) {
    const link = nav.getByRole('link', { name, exact: true });
    await link.click({ timeout: 10_000 });
    await this.seite.waitForFunction(
      (titel) => [...document.querySelectorAll('[role="dialog"] nav a')].some(
        (element) => element.textContent?.trim() === titel && element.getAttribute('aria-current') === 'location',
      ),
      name,
    );
    await leiste.getByRole('heading', { name, exact: true }).waitFor({ timeout: 10_000 });
    await this.seite.waitForFunction(
      (titel) => {
        const dialog = document.querySelector('[role="dialog"]');
        if (!dialog) return false;
        const ausgewaehlt = [...dialog.querySelectorAll('nav a')].some(
          (element) =>
            element.textContent?.trim() === titel &&
            element.getAttribute('aria-current') === 'location',
        );
        const text = dialog.querySelector('app-anleitung-text');
        return ausgewaehlt &&
          (text?.querySelector('.text') || text?.querySelector('[data-test=anleitung-fehler]'));
      },
      name,
      { timeout: 10_000 },
    );
    assert.equal(await leiste.locator('[data-test=anleitung-fehler]').count(), 0, name);
    assert.ok((await leiste.locator('app-anleitung-text .text').innerText()).trim().length > 0, name);
  }
});

Then(/^zeigt die Hilfe-Leiste den geladenen Abschnitt «(.+)»$/, async function (titel) {
  const leiste = this.seite.getByRole('dialog', { name: 'Hilfe', exact: true });
  await leiste.getByRole('heading', { name: titel, exact: true }).waitFor();
  await leiste.locator('app-anleitung-text .text').waitFor();
  assert.ok((await leiste.locator('app-anleitung-text .text').innerText()).trim().length > 0);
});

Then('zeigt die Offline-Anleitung nur die Abschnitte für Abgemeldete', async function () {
  const anker = await this.seite.locator('app-hilfe-seite .abschnitt').evaluateAll(
    (abschnitte) => abschnitte.map((abschnitt) => abschnitt.dataset.anker),
  );
  assert.deepEqual(anker, ['erste-schritte', 'erfassen-offline', 'probleme']);
  await this.seite.locator('[data-test=hilfe-abgemeldet]').waitFor();
});

const MARKER = 'Synthetische neue Anleitungsversion.';
const APP_MARKER = '<!-- synthetische neue App-Version -->';
const hash = (inhalt) => createHash('sha1').update(inhalt).digest('hex');

Given('eine PWA mit zwei synthetischen Versionen ist installiert', { timeout: 120_000 }, async function () {
  const holen = async (pfad) => {
    const antwort = await fetch(new URL(pfad, basisUrl));
    if (!antwort.ok) throw new Error(`Testdatei ${pfad}: HTTP ${antwort.status}`);
    return Buffer.from(await antwort.arrayBuffer());
  };
  const datei = '/anleitung/erste-schritte.md';
  const manifest = JSON.parse((await holen('/ngsw.json')).toString());
  const index = Buffer.concat([await holen('/index.html'), Buffer.from(`\n${APP_MARKER}\n`)]);
  const anleitung = Buffer.concat([await holen(datei), Buffer.from(`\n\n${MARKER}\n`)]);
  manifest.hashTable['/index.html'] = hash(index);
  manifest.hashTable[datei] = hash(anleitung);
  const neu = new Map([
    ['/ngsw.json', Buffer.from(JSON.stringify(manifest))],
    ['/index.html', index],
    [datei, anleitung],
  ]);
  this.neueVersion = false;
  this.versionsServer = createServer(async (anfrage, antwort) => {
    try {
      const url = new URL(anfrage.url, basisUrl);
      const upstream = await fetch(url);
      if (!upstream.ok) {
        antwort.writeHead(upstream.status).end();
        return;
      }
      const inhalt = this.neueVersion && neu.get(url.pathname);
      antwort.writeHead(200, {
        'content-type': upstream.headers.get('content-type') ?? 'application/octet-stream',
        'cache-control': 'no-store',
      });
      antwort.end(inhalt || Buffer.from(await upstream.arrayBuffer()));
    } catch (fehler) {
      antwort.writeHead(502).end(String(fehler));
    }
  });
  await new Promise((resolve) => this.versionsServer.listen(0, '127.0.0.1', resolve));
  const adresse = this.versionsServer.address();
  await installieren(this, `http://127.0.0.1:${adresse.port}`);
});

After(async function () {
  if (this.versionsServer) {
    await new Promise((resolve, reject) => this.versionsServer.close((fehler) => fehler ? reject(fehler) : resolve()));
  }
});

When('eine neue App-Version mit neuer Anleitung bereitsteht', { timeout: 150_000 }, async function () {
  this.neueVersion = true;
  await this.seite.evaluate(() =>
    navigator.serviceWorker.controller.postMessage({ action: 'CHECK_FOR_UPDATES', nonce: 20261121 }),
  );
  await this.seite.locator('[data-test=update-hinweis]').waitFor({ timeout: 120_000 });
});

async function pruefeVersion(seite, neu) {
  const version = await seite.evaluate(async () => ({
    app: await (await fetch('/index.html')).text(),
    anleitung: await (await fetch('/anleitung/erste-schritte.md')).text(),
  }));
  assert.equal(version.app.includes(APP_MARKER), neu);
  assert.equal(version.anleitung.includes(MARKER), neu);
}

Then('zeigt die PWA weiterhin die alte App und die alte Anleitung', async function () {
  await pruefeVersion(this.seite, false);
});

async function updateKnopfImZugänglichkeitsbaum(seite) {
  const sitzung = await seite.context().newCDPSession(seite);
  try {
    const { root } = await sitzung.send('DOM.getDocument');
    const { nodeId } = await sitzung.send('DOM.querySelector', {
      nodeId: root.nodeId,
      selector: '[data-test=update-hinweis] button',
    });
    const { nodes } = await sitzung.send('Accessibility.getPartialAXTree', { nodeId, fetchRelatives: false });
    return !nodes[0].ignored;
  } finally {
    await sitzung.detach();
  }
}

Then('ist der Updateknopf durch die Hilfe abgeschirmt', async function () {
  const hinweis = this.seite.locator('[data-test=update-hinweis]');
  const knopf = hinweis.locator('button');
  await knopf.evaluate((element) => {
    window.updateTestKlicks = 0;
    element.addEventListener('click', (ereignis) => {
      window.updateTestKlicks++;
      ereignis.stopImmediatePropagation();
    }, { capture: true });
  });
  assert.equal(await hinweis.evaluate((element) => element.inert), true);
  assert.equal(await updateKnopfImZugänglichkeitsbaum(this.seite), false);
  assert.equal(await knopf.evaluate((element) => {
    element.focus();
    return document.activeElement === element;
  }), false);
  const box = await knopf.boundingBox();
  assert.ok(box);
  await this.seite.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  assert.equal(await this.seite.evaluate(() => window.updateTestKlicks), 0);
});

Then('ist der Updateknopf wieder bedienbar', async function () {
  const hinweis = this.seite.locator('[data-test=update-hinweis]');
  assert.equal(await hinweis.evaluate((element) => element.inert), false);
  assert.equal(await updateKnopfImZugänglichkeitsbaum(this.seite), true);
  await hinweis.locator('button').click();
  assert.equal(await this.seite.evaluate(() => window.updateTestKlicks), 1);
});

When('ich die neue App-Version aktiviere', async function () {
  await this.seite.locator('[data-test=update-hinweis] button').click();
  await this.seite.waitForLoadState();
  await this.seite.waitForFunction(() => !!navigator.serviceWorker.controller);
});

Then('zeigt die PWA die neue App und die neue Anleitung', async function () {
  await pruefeVersion(this.seite, true);
});
