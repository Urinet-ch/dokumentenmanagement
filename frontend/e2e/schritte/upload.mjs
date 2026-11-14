// Upload-Seite gegen das echte Backend (Aufgabe 2.4): Die Anfragen der PWA an POST /api/v1/dokumente leitet
// Playwright mit weiterleiten() aus hilfen/echtes-backend.mjs an das Archiv im Testmodus weiter und tauscht dabei das
// Token der Keycloak-Attrappe gegen ein echtes Token desselben Testbenutzers. Die Dateien sind synthetisch, keine
// echten Belege.
import { After, Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { direktHochladen, echteDokumente, inhalt, weiterleiten } from '../hilfen/echtes-backend.mjs';

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

function zustand(welt) {
  welt.upload ??= { inhalte: new Map() };
  return welt.upload;
}

/** Dateien mit dem Inhalt aus dem Szenario oder nach Endung; dieselbe Datei behält im Szenario ihren Inhalt. */
function dateien(welt, liste) {
  const { inhalte } = zustand(welt);
  return liste.split(', ').map((name) => {
    if (!inhalte.has(name)) {
      inhalte.set(name, inhalt(name));
    }
    return {
      name,
      mimeType: MIME[name.split('.').pop().toLowerCase()] ?? 'application/octet-stream',
      buffer: inhalte.get(name),
    };
  });
}

/** Leitet nur die Uploads (POST /api/v1/dokumente) an das Archiv weiter; alles andere beantworten die Attrappen. */
async function uploadsWeiterleiten(welt) {
  if (welt.weiterleitung) {
    return;
  }
  await welt.seite.addInitScript(formularMitschreiben);
  await welt.seite.evaluate(formularMitschreiben);
  await weiterleiten(welt, {
    passt: (anfrage) => anfrage.method() === 'POST' && new URL(anfrage.url()).pathname === '/api/v1/dokumente',
    merken: async () =>
      (await welt.seite.evaluate(() => window.__archivUploads?.shift())) ?? { namen: [], zusammenfassen: false },
  });
}

// Chromium gibt den Inhalt von Dateien aus dem Dateisystem nicht an Playwright heraus (postDataBuffer bleibt leer);
// darum hält die Seite selbst fest, welche Felder die PWA im FormData an XMLHttpRequest.send übergibt.
function formularMitschreiben() {
  if (window.__archivUploads) {
    return;
  }
  window.__archivUploads = [];
  const mitschreiben = (inhalt) => {
    if (inhalt instanceof FormData) {
      window.__archivUploads.push({
        namen: inhalt.getAll('dateien').map((datei) => datei.name),
        zusammenfassen: inhalt.get('zusammenfassen') === 'true',
      });
    }
  };
  const senden = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (inhalt) {
    mitschreiben(inhalt);
    return senden.call(this, inhalt);
  };
  const holen = window.fetch;
  window.fetch = function (eingabe, optionen) {
    mitschreiben(optionen?.body);
    return holen.call(this, eingabe, optionen);
  };
}

/** Wartet auf das Ergebnis der Upload-Seite; scheitert die Weiterleitung, kommt ihr Fehler statt eines Timeouts. */
async function ergebnisAbwarten(welt) {
  const ueberschrift = welt.seite.getByRole('heading', { name: 'Ergebnis je Datei' });
  for (let versuch = 0; versuch < 1200; versuch++) {
    if (welt.weiterleitung?.fehler) {
      throw welt.weiterleitung.fehler;
    }
    if (await ueberschrift.isVisible()) {
      return ueberschrift;
    }
    await welt.seite.waitForTimeout(100);
  }
  throw new Error('Kein Ergebnis nach 120 s');
}

After(function () {
  if (this.upload?.verzeichnis) {
    rmSync(this.upload.verzeichnis, { recursive: true, force: true });
  }
});

Given('die API meldet die Sitzung als abgelaufen', async function () {
  await uploadsWeiterleiten(this);
  this.weiterleitung.sitzungAbgelaufen = true;
});

Given(/^die Datei «(.+)» ist (ein Text|leer|abgeschnitten|grösser als 50 MB)$/, function (name, art) {
  zustand(this).inhalte.set(name, inhalt(name, art));
});

Given(/^«(.+)» im Mandanten «(.+)» hat die Datei «(.+)» schon hochgeladen$/, async function (rolle, mandant, name) {
  const [ergebnis] = await direktHochladen(rolle, mandant, dateien(this, name));
  assert.equal(ergebnis.status, 'NEU', JSON.stringify(ergebnis));
  echteDokumente(this).set(name, ergebnis.id);
});

When(/^ich die Dateien «(.+)» auswähle$/, async function (liste) {
  await uploadsWeiterleiten(this);
  // Playwright nimmt Puffer nur bis 50 MB an; darum gehen die Dateien über ein temporäres Verzeichnis. Chromium liest
  // sie erst beim Senden, das Verzeichnis bleibt darum bis zum Ende des Szenarios (After).
  const upload = zustand(this);
  upload.verzeichnis ??= mkdtempSync(join(tmpdir(), 'archiv-e2e-'));
  const pfade = dateien(this, liste).map((datei) => {
    const pfad = join(upload.verzeichnis, datei.name);
    writeFileSync(pfad, datei.buffer);
    return pfad;
  });
  await this.seite.locator('input[type=file]').setInputFiles(pfade);
});

When(/^ich die Dateien «(.+)» in die Ablage ziehe$/, async function (liste) {
  await uploadsWeiterleiten(this);
  const daten = dateien(this, liste).map((d) => ({ name: d.name, typ: d.mimeType, inhalt: d.buffer.toString('base64') }));
  const uebertragung = await this.seite.evaluateHandle((liste) => {
    const dt = new DataTransfer();
    for (const d of liste) {
      const bytes = Uint8Array.from(atob(d.inhalt), (zeichen) => zeichen.charCodeAt(0));
      dt.items.add(new File([bytes], d.name, { type: d.typ }));
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

Then(/^hat das Archiv einen Upload mit «(.+)» (mit|ohne) «zusammenfassen» erhalten$/, { timeout: 150_000 }, async function (liste, art) {
  await ergebnisAbwarten(this);
  const anfragen = this.weiterleitung.anfragen.map(({ namen, zusammenfassen }) => ({ namen, zusammenfassen }));
  assert.deepEqual(anfragen, [{ namen: liste.split(', '), zusammenfassen: art === 'mit' }]);
});

Then(/^hat das Archiv daraus (ein Dokument|je Datei ein Dokument) angelegt$/, async function (art) {
  for (let versuch = 0; versuch < 50 && this.weiterleitung.anfragen[0]?.antwort === undefined; versuch++) {
    await this.seite.waitForTimeout(100);
  }
  const [{ status, antwort }] = this.weiterleitung.anfragen;
  assert.equal(status, 202, JSON.stringify(antwort));
  assert.ok(antwort.every((e) => e.status === 'NEU' && e.id), JSON.stringify(antwort));
  const ids = new Set(antwort.map((e) => e.id));
  assert.equal(ids.size, art === 'ein Dokument' ? 1 : antwort.length, JSON.stringify(antwort));
});

Then('sehe ich je Datei das Ergebnis:', { timeout: 150_000 }, async function (tabelle) {
  const ueberschrift = await ergebnisAbwarten(this);
  assert.equal(await ueberschrift.evaluate((e) => e === document.activeElement), true, 'Fokus nicht auf dem Ergebnis');
  for (const zeile of tabelle.hashes()) {
    const eintrag = this.seite.locator('li.datei', { has: this.seite.getByText(zeile.datei, { exact: true }) });
    assert.equal(await eintrag.getAttribute('data-ausgang'), zeile.ausgang, zeile.datei);
    const text = await eintrag.locator('[data-test=ergebnis]').innerText();
    assert.ok(text.includes(zeile.meldung), `${zeile.datei}: «${text}» enthält nicht «${zeile.meldung}»`);
  }
});

Then(/^führt «(.+)» zum vorhandenen Dokument$/, async function (datei) {
  const eintrag = this.seite.locator('li.datei', { has: this.seite.getByText(datei, { exact: true }) });
  assert.equal(await eintrag.getByRole('link').getAttribute('href'), `/dokument/${echteDokumente(this).get(datei)}`);
});

Then(/^bietet «(.+)» keinen Link$/, async function (datei) {
  const eintrag = this.seite.locator('li.datei', { has: this.seite.getByText(datei, { exact: true }) });
  assert.equal(await eintrag.getByRole('link').count(), 0);
});
