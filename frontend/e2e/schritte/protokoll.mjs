// Browser-Nachweis für die Seite «Zugriffsprotokoll» (Spec 01, Aufgabe 5.1). Eine Attrappe steuert Zeitstempel,
// Reihenfolge, Filterergebnisse und verzögerte Antworten; die Serverregeln belegen die Java-Szenarien.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';

const PFAD = '/api/v1/protokoll';

function antwort(route, status, inhalt) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: inhalt === undefined ? '' : JSON.stringify(inhalt),
  });
}

function protokoll(welt) {
  assert.ok(welt.protokoll, 'die Protokoll-Schnittstelle ist keine Attrappe');
  return welt.protokoll;
}

function person(name = `Person ${randomUUID()}`, rolle = 'MITARBEITER') {
  return {
    id: 1,
    zeit: '2026-03-28T08:00:00Z',
    benutzer: `sub-${randomUUID()}`,
    name,
    rolle,
    gueltigVon: '2026-03-01T08:00:00Z',
    gueltigBis: null,
    anlass: 'AKTION',
    erfasstVon: null,
  };
}

function eintrag(id, zeit, benutzer, name, rolle, aktion, dokumentId, zusaetze = {}) {
  return {
    id,
    zeit,
    benutzer,
    name,
    rolle,
    aktion,
    dokumentId,
    ip: null,
    details: '{}',
    ...zusaetze,
  };
}

function protokollAbfragen(welt) {
  return protokoll(welt).anfragen.filter((a) => a.pfad === PFAD && a.methode === 'GET');
}

function filterfeld(welt, feld) {
  return welt.seite.locator(`form [data-test=${feld}]`);
}

function filtert(eintrag, parameter) {
  return (
    (!parameter.get('aktion') || eintrag.aktion === parameter.get('aktion')) &&
    (!parameter.get('benutzer') || eintrag.benutzer === parameter.get('benutzer')) &&
    (!parameter.get('dokument') || eintrag.dokumentId === parameter.get('dokument')) &&
    (!parameter.get('von') || eintrag.zeit >= parameter.get('von')) &&
    (!parameter.get('bis') || eintrag.zeit < parameter.get('bis'))
  );
}

async function anzeigen(welt) {
  await welt.seite.getByRole('button', { name: 'Anzeigen', exact: true }).click();
}

Given('die Protokoll-Schnittstelle ist eine Attrappe', async function () {
  const origin = new URL(basisUrl).origin;
  const p = {
    eintraege: [],
    verzeichnis: [],
    anfragen: [],
    ersteAntwortVerzoegern: false,
    ersteAntwortGestartet: null,
    ersteAntwortStart: null,
    ersteAntwortFreigeben: null,
    ersteAntwortFreigabe: null,
    ersteAntwortAbgeschlossen: null,
    ersteAntwortEnde: null,
    verzoegerteEintraege: [],
  };
  this.protokoll = p;

  await this.seite.route(
    (url) =>
      url.origin === origin && (url.pathname === PFAD || url.pathname.startsWith(`${PFAD}/`)),
    async (route) => {
      const anfrage = route.request();
      const url = new URL(anfrage.url());
      const protokollAnfrage = {
        methode: anfrage.method(),
        pfad: url.pathname,
        parameter: new URLSearchParams(url.searchParams),
      };
      p.anfragen.push(protokollAnfrage);

      if (!(await anfrage.headerValue('authorization'))?.startsWith('Bearer ')) {
        return antwort(route, 401, { fehlercode: 'NICHT_ANGEMELDET' });
      }

      if (url.pathname === PFAD && anfrage.method() === 'GET') {
        const nummer = protokollAbfragen(this).length;
        if (p.ersteAntwortVerzoegern && nummer === 1) {
          p.ersteAntwortGestartet();
          await p.ersteAntwortFreigabe;
        }
        const quelle =
          p.ersteAntwortVerzoegern && nummer === 1 ? p.verzoegerteEintraege : p.eintraege;
        const groesse = Number(url.searchParams.get('groesse') ?? 50);
        const seite = Number(url.searchParams.get('seite') ?? 0);
        const treffer = quelle
          .filter((e) => filtert(e, url.searchParams))
          .sort((a, b) => b.zeit.localeCompare(a.zeit) || b.id - a.id);
        const start = seite * groesse;
        const body = {
          eintraege: treffer.slice(start, start + groesse),
          gesamt: treffer.length,
          seite,
          groesse,
        };
        try {
          await antwort(route, 200, body);
        } finally {
          if (p.ersteAntwortVerzoegern && nummer === 1) {
            p.ersteAntwortEnde();
          }
        }
        return;
      }

      if (url.pathname === `${PFAD}/benutzer` && anfrage.method() === 'GET') {
        return antwort(route, 200, p.verzeichnis);
      }

      const austritt = /^\/api\/v1\/protokoll\/benutzer\/([^/]+)\/austritt$/.exec(url.pathname);
      if (austritt && anfrage.method() === 'POST') {
        const benutzer = decodeURIComponent(austritt[1]);
        const zeile = [...p.verzeichnis].reverse().find((e) => e.benutzer === benutzer);
        if (!zeile) {
          return antwort(route, 404, { fehlercode: 'NICHT_GEFUNDEN' });
        }
        const jetzt = new Date().toISOString();
        p.verzeichnis.push({
          ...zeile,
          id: zeile.id + 1,
          zeit: jetzt,
          gueltigVon: jetzt,
          gueltigBis: jetzt,
          anlass: 'AUSTRITT',
          erfasstVon: 'inhaber-test',
        });
        return route.fulfill({ status: 204 });
      }

      return antwort(route, 404, { fehlercode: 'NICHT_GEFUNDEN' });
    },
  );
});

Given('das Zugriffsprotokoll enthält zwei synthetische Einträge mit Zusatzfeldern', function () {
  const p = protokoll(this);
  const aelter = person(`Älterer Benutzer ${randomUUID()}`);
  const neuer = person(`Neuester Benutzer ${randomUUID()}`, 'INHABER');
  const datei = `datei-${randomUUID()}.pdf`;
  const inhalt = `synthetischer-inhalt-${randomUUID()}`;
  const dokumentAelter = randomUUID();
  const dokumentNeuer = randomUUID();
  p.eintraege = [
    eintrag(
      1,
      '2026-03-28T08:30:00Z',
      aelter.benutzer,
      aelter.name,
      aelter.rolle,
      'ANZEIGEN',
      dokumentAelter,
    ),
    eintrag(
      2,
      '2026-03-30T00:15:00Z',
      neuer.benutzer,
      neuer.name,
      neuer.rolle,
      'VERWEIGERT',
      dokumentNeuer,
      {
        dateiname: datei,
        inhalt,
      },
    ),
  ];
  p.neuester = {
    ...neuer,
    dokumentId: dokumentNeuer,
    zeit: '30.03.2026 02:15',
    aktion: 'Verweigert',
  };
  p.aelter = {
    ...aelter,
    dokumentId: dokumentAelter,
    zeit: '28.03.2026 09:30',
    aktion: 'Angezeigt',
  };
  p.datei = datei;
  p.inhalt = inhalt;
});

Given(
  'das Zugriffsprotokoll enthält einen Eintrag am Ende des «Bis»-Tages und einen am Folgetag',
  function () {
    const p = protokoll(this);
    p.filterBenutzer = `sub-${randomUUID()}`;
    p.filterDokument = randomUUID();
    p.bisTag = eintrag(
      21,
      '2026-03-29T21:59:59Z',
      p.filterBenutzer,
      'Eintrag am Bis-Tag',
      'MITARBEITER',
      'ANZEIGEN',
      p.filterDokument,
    );
    p.folgetag = eintrag(
      22,
      '2026-03-29T22:00:00Z',
      p.filterBenutzer,
      'Eintrag am Folgetag',
      'MITARBEITER',
      'ANZEIGEN',
      p.filterDokument,
    );
    p.falscherBenutzer = eintrag(
      23,
      '2026-03-29T21:59:00Z',
      `sub-${randomUUID()}`,
      'Anderer Benutzer',
      'MITARBEITER',
      'ANZEIGEN',
      p.filterDokument,
    );
    p.eintraege = [p.bisTag, p.folgetag, p.falscherBenutzer];
  },
);

Given('das Zugriffsprotokoll enthält 101 Einträge für eine synthetische Kennung', function () {
  const p = protokoll(this);
  p.filterBenutzer = `sub-${randomUUID()}`;
  p.eintraege = Array.from({ length: 101 }, (_, index) =>
    eintrag(
      index + 1,
      new Date(Date.UTC(2026, 2, 30) - index * 1000).toISOString(),
      p.filterBenutzer,
      `Eintrag ${index + 1}`,
      'MITARBEITER',
      'ANZEIGEN',
      randomUUID(),
    ),
  );
});

Given('die erste Protokollantwort ist absichtlich verzögert', function () {
  const p = protokoll(this);
  p.ersteAntwortVerzoegern = true;
  p.ersteAntwortGestartet = null;
  p.ersteAntwortStart = new Promise((resolve) => (p.ersteAntwortGestartet = resolve));
  p.ersteAntwortFreigeben = null;
  p.ersteAntwortFreigabe = new Promise((resolve) => (p.ersteAntwortFreigeben = resolve));
  p.ersteAntwortEnde = null;
  p.ersteAntwortAbgeschlossen = new Promise((resolve) => (p.ersteAntwortEnde = resolve));
  p.filterBenutzer = `sub-${randomUUID()}`;
  p.neueAntwort = eintrag(
    32,
    '2026-03-30T11:00:00Z',
    p.filterBenutzer,
    `Neue Antwort ${randomUUID()}`,
    'INHABER',
    'ANZEIGEN',
    randomUUID(),
  );
  p.verzoegerteAntwort = eintrag(
    31,
    '2026-03-30T10:00:00Z',
    `sub-${randomUUID()}`,
    `Verspätete Antwort ${randomUUID()}`,
    'INHABER',
    'ANZEIGEN',
    randomUUID(),
  );
  p.eintraege = [p.neueAntwort];
  p.verzoegerteEintraege = [p.verzoegerteAntwort];
});

Given('das Benutzerverzeichnis enthält eine synthetische Person', function () {
  const p = protokoll(this);
  p.person = person(`Person ${randomUUID()}`);
  p.verzeichnis = [p.person];
});

When('ich die vorbereiteten Protokollfilter eintrage', async function () {
  const p = protokoll(this);
  await filterfeld(this, 'von').fill('2026-03-28');
  await filterfeld(this, 'bis').fill('2026-03-29');
  await filterfeld(this, 'benutzer').fill(p.filterBenutzer);
  await filterfeld(this, 'dokument').fill(p.filterDokument);
  await filterfeld(this, 'aktion').selectOption('ANZEIGEN');
});

Then('hat die PWA noch keinen Filter gesendet', function () {
  const anfragen = protokollAbfragen(this);
  assert.equal(anfragen.length, 1, 'die PWA hat vor «Anzeigen» erneut abgefragt');
  assert.ok(
    !['von', 'bis', 'benutzer', 'dokument', 'aktion'].some((k) => anfragen[0].parameter.has(k)),
  );
});

Then(/^hat die PWA den Protokollfilter in Europe\/Zurich gesendet$/, function () {
  const anfrage = protokollAbfragen(this).at(-1);
  assert.deepEqual(Object.fromEntries(anfrage.parameter), {
    aktion: 'ANZEIGEN',
    benutzer: protokoll(this).filterBenutzer,
    bis: '2026-03-29T22:00:00.000Z',
    dokument: protokoll(this).filterDokument,
    groesse: '50',
    seite: '0',
    von: '2026-03-27T23:00:00.000Z',
  });
});

Then('ist der Eintrag am «Bis»-Tag sichtbar, aber der am Folgetag nicht', async function () {
  const eintraege = this.seite.locator('[data-test=eintrag]');
  await eintraege.filter({ hasText: protokoll(this).folgetag.name }).waitFor({ state: 'detached' });
  assert.equal(await eintraege.count(), 1);
  assert.ok((await eintraege.first().innerText()).includes(protokoll(this).bisTag.name));
  assert.ok(
    !(await this.seite.locator('body').innerText()).includes(protokoll(this).folgetag.name),
  );
});

When('ich die Protokollfilter zurücksetze', async function () {
  const antwort = this.seite.waitForResponse((response) => {
    const url = new URL(response.url());
    return (
      url.pathname === PFAD &&
      !['von', 'bis', 'benutzer', 'dokument', 'aktion'].some((k) => url.searchParams.has(k))
    );
  });
  await this.seite.getByRole('button', { name: 'Filter zurücksetzen', exact: true }).click();
  await antwort;
});

When('ich die vorbereitete Protokoll-Kennung und Aktion filtere', async function () {
  await filterfeld(this, 'benutzer').fill(protokoll(this).filterBenutzer);
  await filterfeld(this, 'aktion').selectOption('ANZEIGEN');
  await anzeigen(this);
  await this.seite.getByText('1–50 von 101', { exact: true }).waitFor();
});

When('ich den nicht übernommenen Protokollfilter ändere', async function () {
  await filterfeld(this, 'benutzer').fill(`sub-${randomUUID()}`);
  await filterfeld(this, 'aktion').selectOption('VERWEIGERT');
});

Then(/^sehe ich «(.+)»$/, async function (text) {
  await this.seite.getByText(text, { exact: true }).waitFor();
});

Then('hat die PWA beim Blättern nur den übernommenen Protokollfilter gesendet', function () {
  const anfragen = protokollAbfragen(this);
  const seiten = anfragen.map((a) => a.parameter.get('seite'));
  assert.deepEqual(seiten, ['0', '0', '1', '2', '1']);
  for (const anfrage of anfragen.slice(1)) {
    assert.equal(anfrage.parameter.get('benutzer'), protokoll(this).filterBenutzer);
    assert.equal(anfrage.parameter.get('aktion'), 'ANZEIGEN');
  }
});

When('ich die neue Protokollabfrage starte', async function () {
  const p = protokoll(this);
  await p.ersteAntwortStart;
  await filterfeld(this, 'benutzer').fill(p.filterBenutzer);
  await anzeigen(this);
  await this.seite.getByText(p.neueAntwort.name, { exact: false }).waitFor();
});

When('die verzögerte erste Protokollantwort eintrifft', async function () {
  const p = protokoll(this);
  p.ersteAntwortFreigeben();
  await p.ersteAntwortAbgeschlossen;
  await this.seite.waitForTimeout(100);
});

Then(
  'sehe ich die Protokolleinträge neueste zuerst mit Zeit, Benutzer, Aktion und Dokument-ID',
  async function () {
    const p = protokoll(this);
    const eintraege = this.seite.locator('[data-test=eintrag]');
    await eintraege.first().waitFor();
    assert.equal(await eintraege.count(), 2);
    const neuester = await eintraege.nth(0).innerText();
    const aelterer = await eintraege.nth(1).innerText();
    for (const wert of [
      p.neuester.zeit,
      p.neuester.name,
      'Inhaber',
      p.neuester.aktion,
      p.neuester.dokumentId,
    ]) {
      assert.ok(neuester.includes(wert), `Neuester Eintrag enthält «${wert}» nicht: ${neuester}`);
    }
    for (const wert of [
      p.aelter.zeit,
      p.aelter.name,
      'Mitarbeiter',
      p.aelter.aktion,
      p.aelter.dokumentId,
    ]) {
      assert.ok(aelterer.includes(wert), `Älterer Eintrag enthält «${wert}» nicht: ${aelterer}`);
    }
  },
);

Then('sehe ich weder Dateinamen noch Dokumentinhalte im Protokoll', async function () {
  const text = await this.seite.locator('[data-test=eintrag]').allInnerTexts();
  assert.ok(text.join('\n').includes(protokoll(this).neuester.dokumentId));
  assert.ok(!text.join('\n').includes(protokoll(this).datei));
  assert.ok(!text.join('\n').includes(protokoll(this).inhalt));
});

Then('sind die Protokollfilter leer und die PWA hat sie zurückgesetzt', async function () {
  const p = protokoll(this);
  for (const feld of ['von', 'bis', 'benutzer', 'dokument']) {
    assert.equal(await filterfeld(this, feld).inputValue(), '');
  }
  assert.equal(await filterfeld(this, 'aktion').inputValue(), '');
  await this.seite
    .locator('[data-test=eintrag]')
    .nth(p.eintraege.length - 1)
    .waitFor();
  const anfrage = protokollAbfragen(this).at(-1);
  assert.ok(
    !['von', 'bis', 'benutzer', 'dokument', 'aktion'].some((k) => anfrage.parameter.has(k)),
  );
  assert.equal(await this.seite.locator('[data-test=eintrag]').count(), p.eintraege.length);
});

Then('sehe ich die Antwort der neuen Abfrage', async function () {
  const p = protokoll(this);
  const eintraege = this.seite.locator('[data-test=eintrag]');
  await eintraege.getByText(p.neueAntwort.name, { exact: false }).waitFor();
  assert.equal(await eintraege.count(), 1);
});

Then('bleibt die Antwort der neuen Abfrage sichtbar', async function () {
  const p = protokoll(this);
  const text = await this.seite.locator('[data-test=eintrag]').allInnerTexts();
  assert.ok(text.join('\n').includes(p.neueAntwort.name));
  assert.ok(!text.join('\n').includes(p.verzoegerteAntwort.name));
});

Then('zeigt das Benutzerverzeichnis Name und Rolle zur Zeit des Eintrags', async function () {
  const p = protokoll(this);
  const zeile = this.seite
    .locator('[data-test=verzeichnis]')
    .filter({ hasText: p.person.benutzer });
  await zeile.getByText(p.person.name, { exact: false }).waitFor();
  assert.ok((await zeile.innerText()).includes('Mitarbeiter'));
});

When(/^ich beim Austritt auf «(.+)» klicke$/, async function (knopf) {
  const p = protokoll(this);
  await this.seite
    .locator('[data-test=verzeichnis]')
    .filter({ hasText: p.person.benutzer })
    .getByRole('button', { name: knopf, exact: true })
    .click();
});

Then('sehe ich die zweite Bestätigung und es wurde kein Austritt gesendet', async function () {
  const p = protokoll(this);
  const zeile = this.seite
    .locator('[data-test=verzeichnis]')
    .filter({ hasText: p.person.benutzer });
  await zeile.getByRole('button', { name: 'Austritt bestätigen', exact: true }).waitFor();
  assert.equal(p.anfragen.filter((a) => a.methode === 'POST').length, 0);
});

When('ich den Austritt abbreche', async function () {
  const p = protokoll(this);
  const zeile = this.seite
    .locator('[data-test=verzeichnis]')
    .filter({ hasText: p.person.benutzer });
  await zeile.getByRole('button', { name: 'Abbrechen', exact: true }).click();
});

Then('wurde kein Austritt gesendet', function () {
  assert.equal(protokoll(this).anfragen.filter((a) => a.methode === 'POST').length, 0);
});

When('ich den Austritt erneut bestätige', async function () {
  const p = protokoll(this);
  const zeile = this.seite
    .locator('[data-test=verzeichnis]')
    .filter({ hasText: p.person.benutzer });
  await zeile.getByRole('button', { name: 'Austritt erfassen', exact: true }).click();
  await zeile.getByRole('button', { name: 'Austritt bestätigen', exact: true }).click();
  await this.seite.getByRole('status').filter({ hasText: 'erfasst' }).waitFor();
});

Then('hat die PWA genau einen Austritt für diese Kennung gesendet', function () {
  const p = protokoll(this);
  const anfragen = p.anfragen.filter((a) => a.methode === 'POST');
  assert.equal(anfragen.length, 1);
  assert.equal(anfragen[0].pfad, `${PFAD}/benutzer/${p.person.benutzer}/austritt`);
});

Then('hat die PWA die Protokoll-Schnittstelle nicht aufgerufen', async function () {
  await this.seite.waitForLoadState('networkidle');
  assert.deepEqual(protokoll(this).anfragen, []);
});
