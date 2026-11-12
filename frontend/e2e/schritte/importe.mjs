// Import-Oberfläche (Aufgabe 4.4) gegen eine Attrappe der Import-Schnittstelle: Im Testmodus läuft kein Worker, die
// Vorschau käme nie an. Die Attrappe antwortet wie ImportResource (Statusfolge, Zähler, Filter, Kandidaten) und hält
// fest, was die PWA sendet. Alle Daten sind synthetisch.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const PFAD = '/api/v1/importe';
const KANDIDATEN = { 'Muster Garage AG': randomUUID(), 'Muster Garage GmbH': randomUUID() };
const GEAENDERT = randomUUID();
const UNVERAENDERT = randomUUID();
// Profil «21.CRM» der Attrappe: Kopfzeile → Feld
const PROFIL = { Organisation: 'organisation', Vorname: 'vorname', Name: 'name', PLZ: 'plz', Ort: 'ort', 'E-Mail': 'email' };

const kontakte = {
  [KANDIDATEN['Muster Garage AG']]: { typ: 'ORGANISATION', name: 'Muster Garage AG', plz: '6460', ort: 'Altdorf' },
  [KANDIDATEN['Muster Garage GmbH']]: { typ: 'ORGANISATION', name: 'Muster Garage GmbH', plz: '6460', ort: 'Altdorf' },
  [GEAENDERT]: { typ: 'PERSON', name: 'Beispiel', vorname: 'Berta', plz: '6004', ort: 'Luzern', crmId: '1002' },
  [UNVERAENDERT]: { typ: 'PERSON', name: 'Probe', vorname: 'Paul', plz: '6370', ort: 'Stans', crmId: '1004' },
};

const ZEILEN = [
  { nr: 1, ergebnis: 'NEU', werte: ['Beispiel Verein', 'Anna', 'Muster', '6000', 'Luzern', 'anna@beispiel.invalid'] },
  { nr: 2, ergebnis: 'GEAENDERT', kontaktId: GEAENDERT, werte: ['', 'Berta', 'Beispiel', '6004', 'Luzern', ''] },
  { nr: 3, ergebnis: 'DUBLETTE', kandidaten: Object.values(KANDIDATEN), werte: ['Muster Garage', '', '', '6460', 'Altdorf', ''] },
  { nr: 4, ergebnis: 'UNVERAENDERT', kontaktId: UNVERAENDERT, werte: ['', 'Paul', 'Probe', '6370', 'Stans', ''] },
];

function attrappe(welt) {
  assert.ok(welt.importe, 'die Import-Schnittstelle ist keine Attrappe');
  return welt.importe;
}

function zeileMitFeldern(zeile, felder) {
  const werte = Object.fromEntries(Object.entries(felder).map(([feld, position]) => [feld, zeile.werte[position] ?? '']));
  return { meldung: null, kontaktId: null, kandidaten: [], ...zeile, felder: werte };
}

function antwort(route, status, inhalt) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(inhalt) });
}

/** Ein GET des Laufs nach der Übernahme schliesst sie ab, wie der Auftrag UEBERNEHMEN im Worker. */
function laufFortschreiben(imp) {
  const lauf = imp.lauf;
  if (lauf.status === 'VORSCHAU_AUSSTEHEND') {
    if (imp.abfragen++ > 0) {
      Object.assign(lauf, { status: 'VORSCHAU', neu: 1, geaendert: 1, unveraendert: 1, dubletten: 1 });
    }
  } else if (lauf.status === 'VORSCHAU' && lauf.uebernommenVon) {
    Object.assign(lauf, {
      status: 'UEBERNOMMEN',
      uebernommen: new Date().toISOString(),
      angelegt: 1,
      aktualisiert: 2,
      ausgelassen: 0,
      gekennzeichnet: lauf.vollexport ? 3 : 0,
    });
  }
}

async function bedienen(welt, route) {
  const imp = attrappe(welt);
  const anfrage = route.request();
  const url = new URL(anfrage.url());
  const methode = anfrage.method();
  imp.anfragen.push({ methode, pfad: url.pathname, query: Object.fromEntries(url.searchParams) });
  assert.equal(await anfrage.headerValue('authorization'), `Bearer ${welt.keycloak.accessToken}`);

  if (methode === 'GET' && url.pathname === PFAD) {
    return antwort(route, 200, { eintraege: imp.lauf ? [imp.lauf] : [], gesamt: imp.lauf ? 1 : 0, seite: 0, groesse: 20 });
  }
  if (methode === 'POST' && url.pathname === `${PFAD}/crm`) {
    const formular = (await welt.seite.evaluate(() => window.__archivImporte?.shift())) ?? null;
    imp.uploads.push({ formular, art: url.searchParams.get('art'), vollexport: url.searchParams.get('vollexport') });
    imp.spalten = imp.kopfzeile.map((name, position) => ({ position, name }));
    imp.lauf = {
      id: randomUUID(),
      art: url.searchParams.get('art'),
      vollexport: url.searchParams.get('vollexport') === 'true',
      dateiname: formular?.name ?? null,
      sha256: '0'.repeat(64),
      zeichensatz: 'UTF-8',
      trennzeichen: ';',
      zeilen: ZEILEN.length,
      fehler: 0,
      uebersprungen: 0,
      status: imp.zuordnungBekannt ? 'VORSCHAU_AUSSTEHEND' : 'ZUORDNUNG_NOETIG',
      erstellt: new Date().toISOString(),
    };
    if (imp.zuordnungBekannt) {
      imp.felder = imp.vorschlag;
      return antwort(route, 202, { lauf: imp.lauf });
    }
    return antwort(route, 200, {
      lauf: imp.lauf,
      spalten: imp.spalten,
      vorschlagZuordnung: { profil: '21.CRM', felder: imp.vorschlag },
    });
  }
  if (methode === 'PUT' && url.pathname === `${PFAD}/crm/zuordnung`) {
    const inhalt = anfrage.postDataJSON();
    imp.zuordnungen.push({ ...inhalt, art: url.searchParams.get('art') });
    imp.felder = inhalt.profil ? { ...imp.vorschlag, ...inhalt.felder } : inhalt.felder;
    imp.lauf.status = 'VORSCHAU_AUSSTEHEND';
    return antwort(route, 200, imp.lauf);
  }
  const id = imp.lauf?.id;
  if (methode === 'GET' && url.pathname === `${PFAD}/${id}`) {
    laufFortschreiben(imp);
    const lauf = imp.lauf;
    const ergebnis = url.searchParams.get('ergebnis');
    const mitVorschau = lauf.status === 'VORSCHAU' || lauf.status === 'UEBERNOMMEN';
    const zeilen = (mitVorschau ? ZEILEN : ZEILEN.map((z) => ({ nr: z.nr, werte: z.werte, ergebnis: null })))
      .filter((zeile) => !ergebnis || zeile.ergebnis === ergebnis)
      .map((zeile) => zeileMitFeldern(zeile, mitVorschau ? imp.felder : {}));
    const gezeigt = new Set(zeilen.flatMap((z) => [z.kontaktId, ...z.kandidaten]).filter(Boolean));
    return antwort(route, 200, {
      lauf,
      eintraege: zeilen,
      gesamt: zeilen.length,
      seite: 0,
      groesse: 50,
      kontakte: Object.fromEntries(Object.entries(kontakte).filter(([kontakt]) => gezeigt.has(kontakt))),
      ...(lauf.status === 'ZUORDNUNG_NOETIG'
        ? { spalten: imp.spalten, vorschlagZuordnung: { profil: '21.CRM', felder: imp.vorschlag } }
        : {}),
    });
  }
  if (methode === 'POST' && url.pathname === `${PFAD}/${id}/uebernehmen`) {
    imp.uebernahmen.push(anfrage.postDataJSON());
    imp.lauf.uebernommenVon = welt.keycloak.benutzer.id;
    return antwort(route, 202, imp.lauf);
  }
  return antwort(route, 404, {
    status: 404,
    fehlercode: 'NICHT_GEFUNDEN',
    title: 'Nicht gefunden',
    detail: 'Den Eintrag gibt es nicht, oder er ist für Sie nicht sichtbar. Bitte prüfen Sie den Link.',
  });
}

// Chromium gibt den Inhalt einer Datei nicht an Playwright heraus (postDataBuffer bleibt leer); darum hält die Seite
// selbst fest, welche Felder die PWA im FormData an XMLHttpRequest.send bzw. fetch übergibt (wie upload.mjs).
function formularMitschreiben() {
  if (window.__archivImporte) {
    return;
  }
  window.__archivImporte = [];
  const mitschreiben = (inhalt) => {
    if (inhalt instanceof FormData && inhalt.has('datei')) {
      const datei = inhalt.get('datei');
      window.__archivImporte.push({ felder: [...inhalt.keys()], name: datei.name, groesse: datei.size });
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

Given(/^ein Bildschirm mit (\d+) Pixel Breite$/, async function (breite) {
  await this.seite.setViewportSize({ width: Number(breite), height: 800 });
});

Given(/^die Import-Schnittstelle ist eine Attrappe (ohne|mit) bekannter? Zuordnung$/, async function (art) {
  this.importe = {
    zuordnungBekannt: art === 'mit',
    kopfzeile: [],
    vorschlag: {},
    felder: {},
    abfragen: 0,
    lauf: null,
    anfragen: [],
    uploads: [],
    zuordnungen: [],
    uebernahmen: [],
  };
  await this.seite.addInitScript(formularMitschreiben);
  await this.seite.route(
    (url) => url.pathname === PFAD || url.pathname.startsWith(`${PFAD}/`),
    (route) =>
      bedienen(this, route).catch((fehler) => {
        this.importe.fehler = fehler;
        return route.abort('failed');
      }),
  );
});

async function hochladen(welt, name, kopfzeile, vollexport) {
  const imp = attrappe(welt);
  imp.kopfzeile = kopfzeile.split(';');
  imp.vorschlag = Object.fromEntries(
    imp.kopfzeile.flatMap((spalte, position) => (PROFIL[spalte] ? [[PROFIL[spalte], position]] : [])),
  );
  const inhalt = [kopfzeile, ...ZEILEN.map((zeile) => zeile.werte.join(';'))].join('\r\n') + '\r\n';
  const seite = welt.seite;
  await seite.getByRole('heading', { name: 'CSV-Import aus 21.CRM' }).waitFor();
  await seite.locator('[data-test=art]').selectOption('kontakt');
  const checkbox = seite.getByRole('checkbox', { name: 'Vollexport (alle Kontakte aus 21.CRM)' });
  await (vollexport ? checkbox.check() : checkbox.uncheck());
  await seite.locator('input[type=file]').setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(inhalt, 'utf8') });
  await seite.getByRole('button', { name: 'Hochladen', exact: true }).click();
  await seite.waitForURL((url) => url.pathname.startsWith('/importe/'));
}

When(
  /^ich die Kontakt-Datei «(.+)» mit der Kopfzeile «(.+)» (als Vollexport )?hochlade$/,
  async function (name, kopfzeile, vollexport) {
    await hochladen(this, name, kopfzeile, !!vollexport);
  },
);

When(/^ich eine Kontakt-Datei «(.+)» über 5 MiB wähle$/, async function (name) {
  await this.seite.getByRole('heading', { name: 'CSV-Import aus 21.CRM' }).waitFor();
  const buffer = Buffer.alloc(5 * 1024 * 1024 + 1, 'a');
  await this.seite.locator('input[type=file]').setInputFiles({ name, mimeType: 'text/csv', buffer });
});

function spaltenAuswahl(welt, spalte) {
  return welt.seite.locator('[data-test=zuordnung] li').filter({ hasText: `«${spalte}»` }).locator('select');
}

When(/^ich die Spalte «(.+)» dem Feld «(.+)» zuordne$/, async function (spalte, feld) {
  await spaltenAuswahl(this, spalte).selectOption({ label: feld });
});

When(/^ich für Zeile (\d+) «(.+)» wähle$/, async function (nr, text) {
  const auswahl = this.seite.getByRole('combobox', { name: `Entscheid für Zeile ${nr}`, exact: true });
  await auswahl.waitFor({ timeout: 15_000 });
  const optionen = await auswahl.locator('option').evaluateAll((elemente) =>
    elemente.map((e) => ({ wert: e.value, text: e.textContent.trim() })),
  );
  const option = optionen.find((o) => o.text.startsWith(text));
  assert.ok(option, `keine Option «${text}» in ${JSON.stringify(optionen.map((o) => o.text))}`);
  await auswahl.selectOption(option.wert);
});

When(/^ich den Filter «(.+)» wähle$/, async function (filter) {
  const knopf = this.seite.getByRole('group', { name: 'Zeilen filtern' }).getByRole('button', { name: filter });
  await knopf.click();
  await this.seite.waitForFunction(
    (anzahl) => document.querySelectorAll('[data-test=zeile]').length === anzahl,
    ZEILEN.filter((z) => z.ergebnis === 'DUBLETTE').length,
  );
  assert.equal(await knopf.getAttribute('aria-pressed'), 'true');
});

Then(
  /^hat das Archiv die Datei «(.+)» als Multipart-Feld «(.+)» mit «art=(.+)» und «vollexport=(.+)» erhalten$/,
  function (name, feld, art, vollexport) {
    const { uploads, fehler } = attrappe(this);
    assert.ifError(fehler);
    assert.equal(uploads.length, 1);
    const [upload] = uploads;
    assert.deepEqual(upload.formular?.felder, [feld]);
    assert.equal(upload.formular.name, name);
    assert.equal(upload.art, art);
    assert.equal(upload.vollexport, vollexport);
  },
);

Then(/^sehe ich den Zuordnungsdialog mit «(.+)» ausgewählt$/, async function (profil) {
  await this.seite.getByRole('heading', { name: 'Spalten zuordnen' }).waitFor();
  assert.ok(await this.seite.getByRole('checkbox', { name: profil }).isChecked());
});

Then(/^ist die Spalte «(.+)» dem Feld «(.+)» zugeordnet$/, async function (spalte, feld) {
  const auswahl = spaltenAuswahl(this, spalte);
  const gewaehlt = await auswahl.evaluate((e) => e.selectedOptions[0]?.textContent.trim());
  assert.equal(gewaehlt, feld);
});

Then(/^ist «(.+)» nicht mehr ausgewählt$/, async function (name) {
  assert.equal(await this.seite.getByRole('checkbox', { name }).isChecked(), false);
});

Then(/^hat das Archiv die Zuordnung ohne Profil mit den Feldern «(.+)» erhalten$/, async function (liste) {
  const imp = attrappe(this);
  for (let versuch = 0; versuch < 50 && imp.zuordnungen.length === 0; versuch++) {
    await this.seite.waitForTimeout(100);
  }
  assert.ifError(imp.fehler);
  assert.equal(imp.zuordnungen.length, 1);
  const [zuordnung] = imp.zuordnungen;
  assert.equal(zuordnung.laufId, imp.lauf.id);
  assert.equal(zuordnung.art, 'kontakt');
  assert.ok(!zuordnung.profil, `Profil gesendet: ${zuordnung.profil}`);
  const erwartet = liste.split(', ');
  assert.deepEqual(Object.keys(zuordnung.felder).sort(), [...erwartet].sort());
  for (const feld of erwartet) {
    assert.equal(zuordnung.felder[feld], imp.vorschlag[feld], feld);
  }
});

Then(/^sehe ich die Vorschau mit «(.+)»$/, async function (erwartet) {
  const gruppe = this.seite.getByRole('group', { name: 'Zeilen filtern' });
  await gruppe.waitFor({ timeout: 15_000 });
  const chips = await gruppe
    .getByRole('button')
    .evaluateAll((knoepfe) => knoepfe.map((k) => k.textContent.replace(/\s+/g, ' ').trim()));
  assert.deepEqual(chips, erwartet.split(', '));
});

Then(/^hat das Archiv die Zeilen mit dem Ergebnis «(.+)» abgefragt$/, function (ergebnis) {
  const imp = attrappe(this);
  const abfragen = imp.anfragen.filter((a) => a.methode === 'GET' && a.pfad === `${PFAD}/${imp.lauf.id}`);
  assert.equal(abfragen.at(-1).query.ergebnis, ergebnis);
});

Then(/^sehe ich nur die Zeile (\d+) mit den möglichen Kontakten (.+)$/, async function (nr, liste) {
  const zeilen = this.seite.locator('[data-test=zeile]');
  assert.equal(await zeilen.count(), 1);
  assert.ok((await zeilen.first().innerText()).includes(`Zeile ${nr}`));
  const kandidaten = await zeilen.first().locator('.kandidaten li').allInnerTexts();
  const namen = liste.split(', ');
  assert.equal(kandidaten.length, namen.length);
  namen.forEach((name, i) => assert.ok(kandidaten[i].startsWith(name), `${kandidaten[i]} statt ${name}`));
});

Then('hat das Archiv keine Übernahme erhalten', function () {
  assert.deepEqual(attrappe(this).uebernahmen, []);
});

Then('hat das Archiv keinen Upload erhalten', function () {
  const imp = attrappe(this);
  assert.equal(imp.anfragen.filter((a) => a.methode === 'POST').length, 0);
});

Then(/^hat das Archiv die Übernahme mit dem Entscheid «(\d+)» → «(.+)» erhalten$/, async function (nr, kontakt) {
  const imp = attrappe(this);
  for (let versuch = 0; versuch < 50 && imp.uebernahmen.length === 0; versuch++) {
    await this.seite.waitForTimeout(100);
  }
  assert.ifError(imp.fehler);
  assert.deepEqual(imp.uebernahmen, [{ dublettenEntscheide: { [nr]: `ZUSAMMENFUEHREN:${KANDIDATEN[kontakt]}` } }]);
});

Then(/^sehe ich die Bilanz «(.+)»$/, async function (erwartet) {
  const bilanz = this.seite.locator('[data-test=bilanz]');
  await bilanz.waitFor({ timeout: 15_000 });
  const posten = (await bilanz.locator('li').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim());
  assert.deepEqual(posten, erwartet.split(/, (?=\d)/));
});

Then(/^zeigt die Liste der Importe «(.+)» mit «(.+)»$/, async function (name, status) {
  const lauf = this.seite.locator('[data-test=lauf]').filter({ hasText: name });
  await lauf.waitFor();
  assert.equal((await lauf.locator('.marke').innerText()).trim(), status);
});

Then(/^sehe ich einen Fehler mit «(.+)»$/, async function (text) {
  const fehler = this.seite.getByRole('alert');
  await fehler.waitFor();
  assert.ok((await fehler.innerText()).includes(text), await fehler.innerText());
});

Then(/^ist der Knopf «(.+)» gesperrt$/, async function (name) {
  assert.ok(await this.seite.getByRole('button', { name, exact: true }).isDisabled());
});

Then('ragt nichts über den Bildschirm hinaus', async function () {
  const { breite, inhalt } = await this.seite.evaluate(() => ({
    breite: window.innerWidth,
    inhalt: document.documentElement.scrollWidth,
  }));
  assert.ok(inhalt <= breite, `Inhalt ${inhalt} px breiter als der Bildschirm ${breite} px`);
});

Then('hat die PWA die Import-Schnittstelle nicht aufgerufen', async function () {
  await this.seite.waitForLoadState('networkidle');
  assert.equal(await this.seite.getByRole('heading', { name: 'CSV-Import aus 21.CRM' }).count(), 0);
  assert.deepEqual(attrappe(this).anfragen, []);
});
