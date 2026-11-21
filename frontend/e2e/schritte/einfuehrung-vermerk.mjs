// Einführungsvermerk im echten Browser (Spec 07, Aufgabe 23.1, Req 1.3–1.5): lädt das Modul
// src/app/hilfe/einfuehrung-vermerk-speicher.ts (ohne Importe, nur die Typen entfernt) in Chromium auf den Ursprung der
// App und legt Vermerke so ab wie EinfuehrungVermerk.ablegen. Die Bedienung über die Karte der Startseite folgt mit den
// Aufgaben 23.2 und 23.3.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';

const MODUL = new URL('../../src/app/hilfe/einfuehrung-vermerk-speicher.ts', import.meta.url);
const SCHLUESSEL = 'archiv.einfuehrung';

let modulCode;

async function modul() {
  modulCode ??= ts.transpileModule(await readFile(MODUL, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return modulCode;
}

function person(welt, name) {
  welt.personen ??= new Map();
  if (!welt.personen.has(name)) {
    welt.personen.set(name, randomUUID());
  }
  return welt.personen.get(name);
}

function heute() {
  const teile = new Intl.DateTimeFormat('de-CH', {
    timeZone: 'Europe/Zurich',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const teil = (typ) => teile.find((t) => t.type === typ).value;
  return `${teil('year')}-${teil('month')}-${teil('day')}`;
}

async function ablegen(welt, art, name, mandant) {
  return welt.seite.evaluate(
    async ({ art, mandant, sub }) => {
      const m = globalThis.einfuehrungVermerk;
      const k = await m.vermerkKennung(mandant, sub, globalThis.crypto?.subtle);
      return (
        !!k &&
        m.vermerkAblegen(localStorage, { k, version: m.EINFUEHRUNG_VERSION, art, am: m.vermerkDatum(new Date()) })
      );
    },
    { art, mandant, sub: person(welt, name) },
  );
}

async function vermerke(welt) {
  return welt.seite.evaluate(() => globalThis.einfuehrungVermerk.vermerkeLesen(localStorage));
}

async function vermerkVon(welt, name, mandant) {
  return welt.seite.evaluate(
    async ({ mandant, sub }) => {
      const m = globalThis.einfuehrungVermerk;
      const k = await m.vermerkKennung(mandant, sub, globalThis.crypto?.subtle);
      return m.vermerkeLesen(localStorage).find((v) => v.k === k) ?? null;
    },
    { mandant, sub: person(welt, name) },
  );
}

Given('der Vermerk der Einführung ist auf der Startseite geladen', async function () {
  await this.seite.goto(basisUrl);
  await this.seite.evaluate(
    async (code) => {
      const url = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
      globalThis.einfuehrungVermerk = await import(url);
      URL.revokeObjectURL(url);
    },
    await modul(),
  );
  this.anfragenNachLaden = [];
  this.seite.on('request', (anfrage) => {
    if (/^https?:/.test(anfrage.url())) {
      this.anfragenNachLaden.push(anfrage.url());
    }
  });
});

Given(/^unter «(.+)» steht «(.+)»$/, async function (schluessel, wert) {
  await this.seite.evaluate(({ schluessel, wert }) => localStorage.setItem(schluessel, wert), { schluessel, wert });
});

Given('der Browser sperrt den localStorage', async function () {
  await this.seite.evaluate(() => {
    for (const name of ['getItem', 'setItem', 'removeItem', 'key', 'clear']) {
      Storage.prototype[name] = () => {
        throw new DOMException('Speicher gesperrt', 'SecurityError');
      };
    }
  });
});

Given(/^der Vermerk «(.+)» für «(.+)» im Mandanten «(.+)» ist abgelegt$/, async function (art, name, mandant) {
  assert.equal(await ablegen(this, art, name, mandant), true);
  this.gemerkt = await vermerkVon(this, name, mandant);
});

When(/^der Vermerk «(.+)» für «(.+)» im Mandanten «(.+)» abgelegt wird$/, async function (art, name, mandant) {
  this.gespeichert = await ablegen(this, art, name, mandant);
});

Then(
  /^liegt unter «(.+)» genau ein Vermerk mit der Art «(.+)», Version 1 und dem heutigen Datum$/,
  async function (schluessel, art) {
    assert.equal(schluessel, SCHLUESSEL);
    const roh = JSON.parse(await this.seite.evaluate((s) => localStorage.getItem(s), schluessel));
    assert.equal(roh.v, 1);
    assert.equal(roh.eintraege.length, 1);
    assert.deepEqual(Object.keys(roh.eintraege[0]).sort(), ['am', 'art', 'k', 'version']);
    assert.equal(roh.eintraege[0].art, art);
    assert.equal(roh.eintraege[0].version, 1);
    assert.equal(roh.eintraege[0].am, heute());
  },
);

Then(
  /^ist seine Kennung der SHA-256 von Mandant und Benutzer-ID von «(.+)» im Mandanten «(.+)»$/,
  async function (name, mandant) {
    const erwartet = createHash('sha256')
      .update(`${mandant}:${person(this, name)}`)
      .digest('hex');
    assert.deepEqual(
      (await vermerke(this)).map((v) => v.k),
      [erwartet],
    );
  },
);

Then(
  /^enthält der Browser-Speicher weder die Benutzer-ID von «(.+)» noch den Mandanten «(.+)»$/,
  async function (name, mandant) {
    const speicher = await this.seite.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    const vermerk = await this.seite.evaluate((s) => localStorage.getItem(s), SCHLUESSEL);
    assert.ok(!speicher.includes(person(this, name)), 'Benutzer-ID im Klartext');
    assert.ok(!vermerk.includes(mandant), 'Mandant im Klartext');
  },
);

Then('hat die Seite dabei keine Anfrage gesendet', function () {
  assert.deepEqual(this.anfragenNachLaden, []);
});

Then(/^hat «(.+)» im Mandanten «(.+)» (einen|keinen) Vermerk$/, async function (name, mandant, art) {
  assert.equal((await vermerkVon(this, name, mandant)) !== null, art === 'einen');
});

Then(/^ist der Vermerk von «(.+)» im Mandanten «(.+)» unverändert$/, async function (name, mandant) {
  assert.deepEqual(await vermerkVon(this, name, mandant), this.gemerkt);
});

Then(/^liegen unter «(.+)» (\d+) Vermerke?$/, async function (schluessel, anzahl) {
  assert.equal(schluessel, SCHLUESSEL);
  assert.equal((await vermerke(this)).length, Number(anzahl));
});

Then('bleibt der Vermerk ohne Fehler ungespeichert', function () {
  assert.equal(this.gespeichert, false);
});
