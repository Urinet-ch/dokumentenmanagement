// Handy-Erfassung der PWA (Aufgabe 3.7, Req 4.2–4.9): Chromium mit Fake-Kamera (synthetisches Y4M aus
// hilfen/testbild.mjs), mobilem Kontext und Offline-Schalter von Playwright. Der Scanner (OpenCV.js und jscanify im Web
// Worker) läuft echt. Erfolgreiche Uploads leitet Playwright wie upload.mjs an das Archiv im Testmodus weiter; Dublette,
// 5xx, 4xx und 401 beantwortet es auf Wunsch des Szenarios selbst. Alle Bilder sind synthetisch, keine echten Belege.
import { After, Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomInt } from 'node:crypto';
import { rmSync } from 'node:fs';
import { chromium } from 'playwright';
import { archivAttrappe } from '../hilfen/archiv-attrappe.mjs';
import { backend, echtesToken } from '../hilfen/echtes-backend.mjs';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';
import { BREITE, HOEHE, kameraVideo, png } from '../hilfen/testbild.mjs';

const LANGE_KANTE = 2480;
const MOTIV_DATEI = { 'blatt.png': 'ein Blatt', 'leer.png': 'eine leere Fläche' };

/** Startet den Browser neu als Handy (390 × 844, Touch); mit Motiv filmt die Rückkamera dieses Bild. */
async function handy(welt, { motiv, serviceWorker = false, zeitzone = 'Europe/Zurich' }) {
  await welt.browser?.close();
  const args = [];
  welt.kennung = randomInt(2 ** 32);
  if (motiv) {
    welt.kamera = kameraVideo(motiv, welt.kennung);
    args.push(
      '--use-fake-device-for-media-stream',
      '--use-fake-ui-for-media-stream',
      `--use-file-for-fake-video-capture=${welt.kamera.datei}`,
    );
  }
  welt.browser = await chromium.launch({ args });
  welt.kontext = await welt.browser.newContext({
    locale: 'de-CH',
    timezoneId: zeitzone,
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    permissions: motiv ? ['camera'] : [],
    // Service Worker nur für den Offline-Start; sonst liefen seine Anfragen an den Attrappen (page.route) vorbei
    serviceWorkers: serviceWorker ? 'allow' : 'block',
  });
  welt.seite = await welt.kontext.newPage();
  welt.archiv = undefined;
  welt.keycloak = undefined;
  welt.handy = {
    antworten: [],
    anfragen: [],
    fehler: null,
    ersteAufnahme: null,
    kamera: [],
    uploads: [],
  };
  // Über eine Funktion des Kontexts, damit nichts verloren geht, wenn die Seite neu lädt (Anmeldung)
  await welt.kontext.exposeFunction('__handyMelden', (art, daten) => welt.handy[art].push(daten));
  await welt.kontext.addInitScript(mitschreiben);
  if (!serviceWorker) {
    await archivAttrappe(welt);
    await uploadsBeantworten(welt);
  }
}

// Im Browser: welche Kamera die App anfordert und welche Dateien sie im FormData sendet (Chromium gibt den Inhalt der
// Dateien nicht an Playwright heraus, siehe upload.mjs).
function mitschreiben() {
  const medien = navigator.mediaDevices;
  if (medien?.getUserMedia) {
    const anfordern = medien.getUserMedia.bind(medien);
    medien.getUserMedia = (vorgaben) => {
      void window.__handyMelden('kamera', JSON.parse(JSON.stringify(vorgaben)));
      return anfordern(vorgaben);
    };
  }
  const beschreiben = async (datei) => {
    let breite = null;
    let hoehe = null;
    try {
      const bild = await createImageBitmap(datei);
      [breite, hoehe] = [bild.width, bild.height];
      bild.close();
    } catch {
      // kein Bild
    }
    return { name: datei.name, typ: datei.type, groesse: datei.size, breite, hoehe };
  };
  const mitschreibenFormular = (inhalt) => {
    if (inhalt instanceof FormData && inhalt.getAll('dateien').length > 0) {
      void Promise.all([
        Promise.all(inhalt.getAll('dateien').map(beschreiben)),
        Promise.all(inhalt.getAll('arbeitsfassungen').map(beschreiben)),
      ]).then(([originale, arbeitsfassungen]) =>
        window.__handyMelden('uploads', {
          originale,
          arbeitsfassungen,
          zusammenfassen: inhalt.get('zusammenfassen') === 'true',
        }),
      );
    }
  };
  // HttpClient sendet je nach Anfrage über fetch oder XMLHttpRequest
  const senden = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (inhalt) {
    mitschreibenFormular(inhalt);
    return senden.call(this, inhalt);
  };
  const holen = window.fetch;
  window.fetch = function (eingabe, optionen) {
    mitschreibenFormular(optionen?.body);
    return holen.call(this, eingabe, optionen);
  };
}

async function uploadsBeantworten(welt) {
  await welt.seite.route(`${basisUrl}/api/v1/dokumente`, async (route) => {
    const anfrage = route.request();
    if (anfrage.method() !== 'POST') {
      return route.fallback();
    }
    const eintrag = { status: null, antwort: null };
    welt.handy.anfragen.push(eintrag);
    try {
      assert.equal(
        await anfrage.headerValue('authorization'),
        `Bearer ${welt.keycloak.accessToken}`,
      );
      const vorgabe = welt.handy.antworten.shift();
      if (vorgabe) {
        eintrag.status = vorgabe.status;
        eintrag.antwort = vorgabe.body;
        return await route.fulfill({
          status: vorgabe.status,
          contentType: vorgabe.status >= 400 ? 'application/problem+json' : 'application/json',
          body: JSON.stringify(vorgabe.body),
        });
      }
      const {
        rollen: [rolle],
        mandant,
      } = welt.keycloak.benutzer;
      const headers = {
        ...anfrage.headers(),
        authorization: `Bearer ${await echtesToken(rolle, mandant)}`,
      };
      await route.continue({ url: `${backend().api}/api/v1/dokumente`, headers });
    } catch (fehler) {
      welt.handy.fehler = fehler;
      return route.abort('failed');
    }
  });
  welt.seite.on('response', async (antwort) => {
    if (
      antwort.request().method() === 'POST' &&
      new URL(antwort.url()).pathname === '/api/v1/dokumente'
    ) {
      const eintrag = welt.handy.anfragen.at(-1);
      if (eintrag && eintrag.status === null) {
        eintrag.status = antwort.status();
        eintrag.antwort = await antwort.json().catch(() => null);
      }
    }
  });
}

After(function () {
  if (this.kamera) {
    rmSync(this.kamera.verzeichnis, { recursive: true, force: true });
  }
});

/** Wartet auf eine Bedingung; scheitert die Weiterleitung an das Archiv, kommt ihr Fehler statt eines Timeouts. */
async function warten(welt, bedingung, beschreibung, ms = 60_000) {
  for (const ende = Date.now() + ms; Date.now() < ende;) {
    if (welt.handy?.fehler) {
      throw welt.handy.fehler;
    }
    if (await bedingung()) {
      return;
    }
    await welt.seite.waitForTimeout(100);
  }
  throw new Error(`Zeitüberschreitung: ${beschreibung}`);
}

const seiten = (welt) => welt.seite.locator('[data-test=seite]');
const knopf = (welt, name) => welt.seite.getByRole('button', { name, exact: true });

Given(/^ein Handy, dessen Rückkamera «(.+)» filmt$/, { timeout: 60_000 }, async function (motiv) {
  await handy(this, { motiv });
});

Given(
  /^ein Handy mit installierter PWA, dessen Rückkamera «(.+)» filmt$/,
  { timeout: 120_000 },
  async function (motiv) {
    await handy(this, { motiv, serviceWorker: true });
    await this.seite.goto(basisUrl);
    // Installiert ist die PWA, wenn der Service Worker alle Dateien der Gruppe «app» vorgeladen hat (ngsw.json), auch
    // den Scanner (Worker) und die Seite «Erfassen»; er lädt sie nacheinander.
    await this.seite.waitForFunction(
      async () => {
        const registrierung = await navigator.serviceWorker.getRegistration();
        if (!registrierung?.active) {
          return false;
        }
        const manifest = await (await fetch('/ngsw.json')).json();
        const app = manifest.assetGroups.find((gruppe) => gruppe.name === 'app');
        for (const name of (await caches.keys()).filter((n) => n.endsWith(':assets:app:cache'))) {
          const geladen = (await (await caches.open(name)).keys()).map(
            (anfrage) => new URL(anfrage.url).pathname,
          );
          if (app.urls.every((url) => geladen.includes(url))) {
            return true;
          }
        }
        return false;
      },
      undefined,
      { timeout: 110_000, polling: 500 },
    );
  },
);

Given(
  /^ein Handy in der Zeitzone «(.+)», dessen Rückkamera «(.+)» filmt$/,
  { timeout: 60_000 },
  async function (zeitzone, motiv) {
    await handy(this, { motiv, zeitzone });
  },
);

Given('ein Handy ohne Kamera', { timeout: 60_000 }, async function () {
  await handy(this, {});
});

Given('die Uhr des Handys lässt sich vorstellen', async function () {
  await this.kontext.clock.install();
});

Given(
  /^das Archiv antwortet auf (?:den nächsten Upload|die nächsten (\d+) Uploads) mit Status (\d+)$/,
  function (anzahl, status) {
    for (let i = 0; i < Number(anzahl ?? 1); i++) {
      this.handy.antworten.push({
        status: Number(status),
        body: { status: Number(status), title: 'Fehler' },
      });
    }
  },
);

Given(
  /^das Archiv lehnt den nächsten Upload mit Status (\d+) und der Meldung «(.+)» ab$/,
  function (status, detail) {
    this.handy.antworten.push({
      status: Number(status),
      body: { status: Number(status), title: 'Abgelehnt', detail, fehlercode: 'ANFRAGE_UNGUELTIG' },
    });
  },
);

Given('das Archiv meldet den nächsten Upload als Dublette', function () {
  this.handy.antworten.push({
    status: 202,
    body: [
      {
        datei: 'Aufnahme',
        status: 'DUBLETTE',
        vorhandenId: '00000000-0000-0000-0000-000000000001',
      },
    ],
  });
});

Given(
  /^die Uhr des Handys steht auf dem (\d{2})\.(\d{2})\.(\d{4}) um (\d{2}):(\d{2}) Uhr in Zürich$/,
  async function (t, m, j, h, min) {
    // Ortszeit Zürich in UTC umrechnen: Versatz der Zeitzone zum Zeitpunkt selbst (Sommer- oder Winterzeit)
    const alsUtc = Date.UTC(Number(j), Number(m) - 1, Number(t), Number(h), Number(min));
    const teile = Object.fromEntries(
      new Intl.DateTimeFormat('en-US', {
        timeZone: 'Europe/Zurich',
        hourCycle: 'h23',
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: 'numeric',
        minute: 'numeric',
      })
        .formatToParts(alsUtc)
        .map(({ type, value }) => [type, Number(value)]),
    );
    const versatz =
      Date.UTC(teile.year, teile.month - 1, teile.day, teile.hour, teile.minute) - alsUtc;
    await this.kontext.clock.install({ time: alsUtc - versatz });
  },
);

When(/^die Uhr des Handys (\d+) Minuten vorrückt$/, async function (minuten) {
  await this.kontext.clock.fastForward(Number(minuten) * 60_000);
});

When('das Handy offline ist', async function () {
  // Laufende Ladevorgänge (Scanner) erst beenden lassen, wie auf dem Handy mit Service Worker
  await this.seite.waitForLoadState('networkidle');
  await this.kontext.setOffline(true);
});

When('das Handy wieder online ist', async function () {
  await this.kontext.setOffline(false);
});

When('ich die Seite neu lade', async function () {
  await this.seite.reload();
});

When('ich auf den Link «Ohne Verbindung erfassen» tippe', async function () {
  await this.seite.getByRole('link', { name: 'Ohne Verbindung erfassen', exact: true }).click();
});

When(/^ich (\d+) Seiten? aufnehme$/, { timeout: 60_000 }, async function (anzahl) {
  const ausloeser = knopf(this, 'Seite aufnehmen');
  await ausloeser.waitFor();
  const vorher = await seiten(this).count();
  for (let i = 1; i <= Number(anzahl); i++) {
    this.handy.ersteAufnahme ??= new Date();
    await ausloeser.click();
    await seiten(this)
      .nth(vorher + i - 1)
      .waitFor();
    // Die Fake-Kamera wechselt das Bild alle 100 ms; so unterscheiden sich die Aufnahmen wie auf dem Handy
    await this.seite.waitForTimeout(250);
  }
});

When(/^ich das Foto «(.+)» wähle$/, async function (name) {
  const motiv = MOTIV_DATEI[name];
  assert.ok(motiv, `unbekanntes Testbild ${name}`);
  this.handy.ersteAufnahme ??= new Date();
  await this.seite
    .locator('input[type=file][capture=environment]')
    .setInputFiles({ name, mimeType: 'image/png', buffer: png(motiv, this.kennung) });
});

When(/^ich die Ecke oben links von Seite (\d+) nach rechts schiebe$/, async function (nr) {
  await knopf(this, `Zuschnitt Seite ${nr} anpassen`).click();
  const ecke = this.seite.getByRole('button', { name: 'Ecke oben links', exact: true });
  await ecke.focus();
  for (let i = 0; i < 4; i++) {
    await ecke.press('Shift+ArrowRight');
  }
  await knopf(this, 'Zuschnitt übernehmen').click();
});

When(/^ich Seite (\d+) verwerfe$/, async function (nr) {
  await knopf(this, `Seite ${nr} verwerfen`).click();
});

When('ich die Erfassung abschliesse', { timeout: 60_000 }, async function () {
  const abschliessen = knopf(this, 'Erfassung abschliessen');
  await warten(this, () => abschliessen.isEnabled(), 'Seiten zugeschnitten', 50_000);
  await abschliessen.click();
});

When(
  'die Wartezeit bis zum nächsten Versuch verstrichen ist',
  { timeout: 60_000 },
  async function () {
    const vorher = this.handy.anfragen.length;
    await this.kontext.clock.fastForward('03:00');
    await warten(this, () => this.handy.anfragen.length > vorher, 'neuer Upload-Versuch');
  },
);

Then('hat die App die Rückkamera geöffnet', async function () {
  await this.seite.locator('video').waitFor();
  const angefordert = this.handy.kamera;
  assert.ok(angefordert.length > 0, 'keine Kamera angefordert');
  assert.deepEqual(
    angefordert.map((vorgaben) => JSON.stringify(vorgaben.video?.facingMode)),
    angefordert.map(() => JSON.stringify({ ideal: 'environment' })),
  );
});

Then(/^zeigt Seite (\d+) «(.+)»$/, { timeout: 60_000 }, async function (nr, hinweis) {
  const seite = seiten(this).nth(Number(nr) - 1);
  await seite.getByText(hinweis, { exact: true }).waitFor({ timeout: 55_000 });
  await seite.getByRole('img', { name: `Vorschau Seite ${nr}` }).waitFor();
});

Then(/^sehe ich (\d+) Seiten?$/, async function (anzahl) {
  await warten(
    this,
    async () => (await seiten(this).count()) === Number(anzahl),
    `${anzahl} Seiten`,
    10_000,
  );
});

Then(/^hat das Archiv (keinen|einen|\d+) Upload-Versuche? erhalten$/, async function (anzahl) {
  const erwartet = { keinen: 0, einen: 1 }[anzahl] ?? Number(anzahl);
  await warten(
    this,
    () => this.handy.anfragen.length >= erwartet,
    `${erwartet} Upload-Versuche`,
    30_000,
  );
  await this.seite.waitForTimeout(500);
  assert.equal(this.handy.anfragen.length, erwartet);
});

Then(
  /^hat das Archiv eine Erfassung mit (\d+) Seiten? als ein Dokument angelegt$/,
  { timeout: 120_000 },
  async function (anzahl) {
    await warten(
      this,
      () => this.handy.anfragen.some((a) => a.status === 202),
      'Upload angenommen',
      110_000,
    );
    const anfrage = this.handy.anfragen.find((a) => a.status === 202);
    assert.ok(
      Array.isArray(anfrage.antwort) && anfrage.antwort.length > 0,
      JSON.stringify(anfrage.antwort),
    );
    const ids = new Set(anfrage.antwort.map((e) => e.id));
    assert.deepEqual(
      anfrage.antwort.map((e) => e.status),
      anfrage.antwort.map(() => 'NEU'),
    );
    assert.equal(ids.size, 1, 'mehr als ein Dokument');
    // Alle Versuche senden denselben Eintrag; der zuletzt beschriebene ist der angenommene
    await warten(this, () => this.handy.uploads.length > 0, 'Upload beschrieben', 10_000);
    const upload = this.handy.uploads.at(-1);
    assert.equal(upload.originale.length, Number(anzahl));
    assert.equal(upload.arbeitsfassungen.length, Number(anzahl));
    assert.equal(upload.zusammenfassen, true);
    this.handy.upload = upload;
  },
);

Then(
  /^tragen die Originale den Namen «Aufnahme TT.MM.JJJJ HH:MM» der ersten Aufnahme$/,
  function () {
    const zeit = new Intl.DateTimeFormat('de-CH', {
      timeZone: 'Europe/Zurich',
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
    const erste = this.handy.ersteAufnahme.getTime();
    // Die erste Aufnahme liegt zwischen dem Tippen und einigen Sekunden danach
    const moeglich = [0, 30_000].map(
      (ms) => `Aufnahme ${zeit.format(erste + ms).replace(',', '')}`,
    );
    for (const original of this.handy.upload.originale) {
      assert.match(original.name, /^Aufnahme \d{2}\.\d{2}\.\d{4} \d{2}:\d{2}$/);
      assert.ok(moeglich.includes(original.name), `${original.name} nicht in ${moeglich}`);
    }
  },
);

Then(/^speichert das Archiv den Dateinamen «(.+)»$/, async function (name) {
  for (const original of this.handy.upload.originale) {
    assert.equal(original.name, name);
  }
  const [{ id }] = this.handy.anfragen.find((a) => a.status === 202).antwort;
  const {
    rollen: [rolle],
    mandant,
  } = this.keycloak.benutzer;
  const antwort = await fetch(`${backend().api}/api/v1/dokumente/${id}`, {
    headers: { authorization: `Bearer ${await echtesToken(rolle, mandant)}` },
  });
  assert.equal(antwort.status, 200);
  assert.equal((await antwort.json()).dateiname, name);
});

Then('sind die Originale die unbearbeiteten Aufnahmen', function () {
  for (const original of this.handy.upload.originale) {
    assert.deepEqual([original.breite, original.hoehe], [BREITE, HOEHE]);
  }
});

Then(
  /^sind die Arbeitsfassungen zugeschnittene JPEG mit (\d+) Pixeln an der langen Kante$/,
  function (kante) {
    assert.equal(Number(kante), LANGE_KANTE);
    this.handy.upload.arbeitsfassungen.forEach((fassung, i) => {
      assert.equal(fassung.typ, 'image/jpeg');
      assert.match(fassung.name, new RegExp(`^Aufnahme .+ Seite ${i + 1}\\.jpg$`));
      assert.equal(Math.max(fassung.breite, fassung.hoehe), LANGE_KANTE);
      // Das Blatt steht hochkant im querformatigen Kamerabild: zugeschnitten ist die Fassung hochkant
      assert.ok(fassung.hoehe > fassung.breite, `${fassung.breite} × ${fassung.hoehe}`);
    });
  },
);

Then(
  /^sehe ich die Erfassung unter «Nicht hochgeladen» mit der Meldung «(.+)»$/,
  { timeout: 60_000 },
  async function (meldung) {
    const eintrag = this.seite.locator('[data-test=fehlerhaft]');
    await eintrag
      .locator('[data-test=meldung]')
      .getByText(meldung, { exact: true })
      .waitFor({ timeout: 55_000 });
    await eintrag.getByRole('button', { name: 'Erneut versuchen', exact: true }).waitFor();
  },
);

Then('sehe ich nichts unter «Nicht hochgeladen»', async function () {
  await warten(
    this,
    async () => (await this.seite.locator('[data-test=fehlerhaft]').count()) === 0,
    'keine fehlerhaften',
    30_000,
  );
});

Then(/^zeigt der Zähler «(.+)»$/, { timeout: 120_000 }, async function (text) {
  const zaehler = this.seite.locator('[data-test=wartet]');
  await warten(
    this,
    async () => (await zaehler.textContent())?.trim() === text,
    `Zähler «${text}»`,
    110_000,
  );
});
