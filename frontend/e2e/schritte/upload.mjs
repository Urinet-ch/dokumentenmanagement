// Upload-Seite gegen eine Attrappe von POST /api/v1/dokumente (Teilnachweis bis Aufgabe 2.4): Die Attrappe antwortet
// wie in design.md «Upload» beschrieben und prüft Token, Reihenfolge der Dateien und «zusammenfassen».
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';

const MIME = {
  pdf: 'application/pdf',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  heic: 'image/heic',
  tif: 'image/tiff',
  tiff: 'image/tiff',
  txt: 'text/plain',
};

function dateien(liste) {
  return liste.split(', ').map((name) => ({
    name,
    mimeType: MIME[name.split('.').pop().toLowerCase()] ?? 'application/octet-stream',
    buffer: Buffer.from(`Testinhalt ${name}`),
  }));
}

function ergebnis(datei, antwort) {
  const [art, wert] = antwort.split(' ');
  if (art === 'NEU') {
    return { datei, id: randomUUID(), status: 'NEU' };
  }
  if (art === 'DUBLETTE') {
    return wert ? { datei, status: 'DUBLETTE', vorhandenId: wert } : { datei, status: 'DUBLETTE', meldung: 'bereits vorhanden' };
  }
  return { datei, status: 'ABGELEHNT', fehler: { status: Number(art), fehlercode: wert } };
}

async function uploadAttrappe(welt) {
  if (welt.upload) {
    return welt.upload;
  }
  const upload = { antworten: new Map(), anfragen: [], sitzungAbgelaufen: false };
  welt.upload = upload;
  await welt.seite.route(`${basisUrl}/api/v1/dokumente`, async (route) => {
    const anfrage = route.request();
    const inhalt = (anfrage.postDataBuffer() ?? Buffer.alloc(0)).toString('latin1');
    const namen = [...inhalt.matchAll(/name="dateien"; filename="([^"]+)"/g)].map((treffer) => treffer[1]);
    const zusammenfassen = /name="zusammenfassen"\r\n\r\ntrue\r\n/.test(inhalt);
    upload.anfragen.push({ namen, zusammenfassen });
    const problem = (status, fehlercode, erweiterung = {}) =>
      route.fulfill({
        status,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Fehler', status, fehlercode, instance: '/api/v1/dokumente', ...erweiterung }),
      });
    if (upload.sitzungAbgelaufen || (await anfrage.headerValue('authorization')) !== `Bearer ${welt.keycloak.accessToken}`) {
      return problem(401, 'NICHT_ANGEMELDET');
    }
    const ergebnisse = namen.map((name) => ergebnis(name, upload.antworten.get(name) ?? 'NEU'));
    if (zusammenfassen) {
      const id = randomUUID();
      ergebnisse.filter((e) => e.status === 'NEU').forEach((e) => (e.id = id));
    }
    const abgelehnt = ergebnisse.filter((e) => e.status === 'ABGELEHNT');
    if (ergebnisse.length > 0 && abgelehnt.length === ergebnisse.length) {
      return problem(abgelehnt[0].fehler.status, abgelehnt[0].fehler.fehlercode, { ergebnisse });
    }
    return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(ergebnisse) });
  });
  return upload;
}

Given('das Archiv antwortet auf Uploads so:', async function (tabelle) {
  const upload = await uploadAttrappe(this);
  for (const zeile of tabelle.hashes()) {
    upload.antworten.set(zeile.datei, zeile.antwort);
  }
});

Given('die API meldet die Sitzung als abgelaufen', async function () {
  (await uploadAttrappe(this)).sitzungAbgelaufen = true;
});

When(/^ich die Dateien «(.+)» auswähle$/, async function (liste) {
  await uploadAttrappe(this);
  await this.seite.locator('input[type=file]').setInputFiles(dateien(liste));
});

When(/^ich die Dateien «(.+)» in die Ablage ziehe$/, async function (liste) {
  await uploadAttrappe(this);
  const daten = dateien(liste).map((d) => ({ name: d.name, typ: d.mimeType, inhalt: d.buffer.toString() }));
  const uebertragung = await this.seite.evaluateHandle((liste) => {
    const dt = new DataTransfer();
    for (const d of liste) {
      dt.items.add(new File([d.inhalt], d.name, { type: d.typ }));
    }
    return dt;
  }, daten);
  const ablage = this.seite.locator('[data-test=ablage]');
  await ablage.dispatchEvent('dragover', { dataTransfer: uebertragung });
  await ablage.dispatchEvent('drop', { dataTransfer: uebertragung });
});

When(/^ich «(.+)» nach (oben|unten) verschiebe$/, async function (datei, richtung) {
  await this.seite.getByRole('button', { name: `«${datei}» nach ${richtung}`, exact: true }).click();
});

When('ich «als ein Dokument» wähle', async function () {
  await this.seite.getByRole('checkbox', { name: /Als ein Dokument zusammenfassen/ }).check();
});

When('ich hochlade', async function () {
  await this.seite.getByRole('button', { name: 'Hochladen', exact: true }).click();
});

Then(/^sehe ich die Dateien «(.+)» in dieser Reihenfolge$/, async function (liste) {
  const erwartet = liste.split(', ');
  let namen = [];
  for (let versuch = 0; versuch < 20; versuch++) {
    namen = await this.seite.locator('.datei-name').allInnerTexts();
    if (JSON.stringify(namen) === JSON.stringify(erwartet)) {
      break;
    }
    await this.seite.waitForTimeout(100);
  }
  assert.deepEqual(namen, erwartet);
});

Then(/^hat das Archiv einen Upload mit «(.+)» (mit|ohne) «zusammenfassen» erhalten$/, async function (liste, art) {
  await this.seite.getByRole('heading', { name: 'Ergebnis je Datei' }).waitFor();
  assert.deepEqual(this.upload.anfragen, [{ namen: liste.split(', '), zusammenfassen: art === 'mit' }]);
});

Then('sehe ich je Datei das Ergebnis:', async function (tabelle) {
  const ueberschrift = this.seite.getByRole('heading', { name: 'Ergebnis je Datei' });
  await ueberschrift.waitFor();
  assert.equal(await ueberschrift.evaluate((e) => e === document.activeElement), true, 'Fokus nicht auf dem Ergebnis');
  for (const zeile of tabelle.hashes()) {
    const eintrag = this.seite.locator('li.datei', { has: this.seite.getByText(zeile.datei, { exact: true }) });
    assert.equal(await eintrag.getAttribute('data-ausgang'), zeile.ausgang, zeile.datei);
    const text = await eintrag.locator('[data-test=ergebnis]').innerText();
    assert.ok(text.includes(zeile.meldung), `${zeile.datei}: «${text}» enthält nicht «${zeile.meldung}»`);
  }
});

Then(/^führt «(.+)» zum vorhandenen Dokument «(.+)»$/, async function (datei, id) {
  const eintrag = this.seite.locator('li.datei', { has: this.seite.getByText(datei, { exact: true }) });
  assert.equal(await eintrag.getByRole('link').getAttribute('href'), `/dokument/${id}`);
});

Then(/^bietet «(.+)» keinen Link$/, async function (datei) {
  const eintrag = this.seite.locator('li.datei', { has: this.seite.getByText(datei, { exact: true }) });
  assert.equal(await eintrag.getByRole('link').count(), 0);
});
