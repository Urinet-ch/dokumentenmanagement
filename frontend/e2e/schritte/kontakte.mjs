// Kontakte ansehen und pflegen in der PWA (Aufgabe 4.12) gegen das echte Archiv im Testmodus (hilfen/echtes-backend.mjs,
// Schritt «die PWA spricht mit dem Archiv im Testmodus» in echtes-archiv.mjs). Kontakte und Dokumente entstehen über die
// API des Archivs, nur synthetisch; jeder Kontaktname bekommt je Szenario eine Kennung, damit ein weiterer Lauf gegen
// dasselbe Archiv keine gleichnamigen Kontakte früherer Läufe trifft. Szenarien referenzieren den Namen ohne Kennung.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import {
  anfrageWeiterleiten,
  backend,
  direktHochladen,
  direktLesen,
  echteDokumente,
  echtesToken,
  inhalt,
  statusSetzen,
} from '../hilfen/echtes-backend.mjs';

const kennung = () => randomBytes(3).toString('hex');

/** Kontakte des Szenarios: Name aus dem Szenario → { id, name (mit Kennung), mandant }. */
function kontakte(welt) {
  welt.kontakte ??= new Map();
  return welt.kontakte;
}

function kontakt(welt, basis) {
  const k = kontakte(welt).get(basis);
  assert.ok(k, `kein Kontakt «${basis}» angelegt`);
  return k;
}

async function api(rolle, mandant, methode, pfad, { body, ifMatch } = {}) {
  const headers = { authorization: `Bearer ${await echtesToken(rolle, mandant)}` };
  if (body !== undefined) {
    headers['content-type'] = 'application/json';
  }
  if (ifMatch !== undefined) {
    headers['if-match'] = ifMatch;
  }
  const antwort = await fetch(`${backend().api}/api/v1${pfad}`, {
    method: methode,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await antwort.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: antwort.status, json, etag: antwort.headers.get('etag') };
}

async function kontaktAnlegen(welt, rolle, mandant, felder) {
  const { name: basis, ...rest } = felder;
  const name = `${basis} ${kennung()}`;
  const angaben = { typ: 'ORGANISATION', name, ...rest };
  for (const [schluessel, wert] of Object.entries(angaben)) {
    if (wert === '' || wert === undefined) {
      delete angaben[schluessel];
    }
  }
  const antwort = await api(rolle, mandant, 'POST', '/kontakte', { body: angaben });
  assert.equal(
    antwort.status,
    201,
    `Kontakt anlegen: ${antwort.status} ${JSON.stringify(antwort.json)}`,
  );
  kontakte(welt).set(basis, { id: antwort.json.id, name, mandant, rolle });
}

Given(
  /^«([^»]+)» im Mandanten «([^»]+)» hat den Kontakt «([^»]+)» in «([^»]+)» angelegt$/,
  async function (rolle, mandant, basis, ort) {
    await kontaktAnlegen(this, rolle, mandant, { name: basis, ort, plz: '6000' });
  },
);

Given(
  /^«([^»]+)» im Mandanten «([^»]+)» hat diese Kontakte angelegt:$/,
  async function (rolle, mandant, tabelle) {
    for (const zeile of tabelle.hashes()) {
      await kontaktAnlegen(this, rolle, mandant, zeile);
    }
  },
);

Given(
  /^«([^»]+)» im Mandanten «([^»]+)» hat das Dokument «([^»]+)» dem Kontakt «([^»]+)» mit Vertraulichkeit «(NORMAL|INTERN|PERSONAL)» zugeordnet$/,
  async function (rolle, mandant, datei, basis, vertraulichkeit) {
    const [ergebnis] = await direktHochladen(rolle, mandant, [
      { name: datei, mimeType: 'application/pdf', buffer: inhalt(datei) },
    ]);
    assert.equal(ergebnis.status, 'NEU', JSON.stringify(ergebnis));
    await statusSetzen(mandant, ergebnis.id, 'EINGANGSKORB');
    const dokument = await direktLesen(rolle, mandant, ergebnis.id);
    const titel = `${datei.replace(/\.pdf$/, '')} ${kennung()}`;
    const antwort = await api(rolle, mandant, 'PATCH', `/dokumente/${ergebnis.id}`, {
      ifMatch: `"${dokument.version}"`,
      body: { titel, partnerId: kontakt(this, basis).id, vertraulichkeit },
    });
    assert.equal(
      antwort.status,
      200,
      `Dokument zuordnen: ${antwort.status} ${JSON.stringify(antwort.json)}`,
    );
    echteDokumente(this).set(datei, ergebnis.id);
    this.dokumentTitel ??= new Map();
    this.dokumentTitel.set(datei, titel);
  },
);

Given(
  /^«([^»]+)» im Mandanten «([^»]+)» hat das Dokument «([^»]+)» verworfen$/,
  async function (rolle, mandant, datei) {
    const id = echteDokumente(this).get(datei);
    assert.ok(id, `kein Dokument «${datei}»`);
    const dokument = await direktLesen(rolle, mandant, id);
    const antwort = await api(rolle, mandant, 'POST', `/dokumente/${id}/verwerfen`, {
      ifMatch: `"${dokument.version}"`,
      body: { grund: 'Testdokument verworfen' },
    });
    assert.equal(
      antwort.status,
      204,
      `Dokument verwerfen: ${antwort.status} ${JSON.stringify(antwort.json)}`,
    );
  },
);

/**
 * Das Archiv sieht den Benutzer als andere Rolle: Die Anfragen an /kontakte tragen das Token von «<rolle>-<mandant>». So
 * zeigt die PWA Menüpunkt und Seite (Recht A6 nach dem Token der Anmeldung), das Archiv verweigert aber (Req 17.5).
 */
Given(
  /^das Archiv hält mich bei den Kontakten für «([^»]+)» im Mandanten «([^»]+)»$/,
  async function (rolle, mandant) {
    await this.seite.route(
      (url) => url.pathname.startsWith('/api/v1/kontakte'),
      (route) => anfrageWeiterleiten(route, { rollen: [rolle], mandant }),
    );
  },
);

When(/^ich die Kontakte über das Menü öffne$/, async function () {
  const oeffnen = this.seite.getByRole('button', { name: 'Menü öffnen', exact: true });
  if (await oeffnen.isVisible()) {
    await oeffnen.click();
  }
  await this.seite
    .getByRole('navigation', { name: 'Hauptnavigation' })
    .getByRole('link', { name: 'Kontakte' })
    .click();
  await this.seite.getByRole('heading', { name: 'Kontakte', level: 1 }).waitFor();
  await this.seite.locator('[data-test=anzahl], [data-test=fehler]').first().waitFor();
});

async function suchen(welt, begriff) {
  const antwort = welt.seite.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/kontakte/liste' &&
      new URL(r.url()).searchParams.get('q') === begriff,
  );
  await welt.seite.locator('[data-test=suche]').fill(begriff);
  await antwort;
  await welt.seite.locator('[data-test=anzahl]').waitFor();
}

When(/^ich nach dem Kontakt «([^»]+)» suche$/, async function (basis) {
  await suchen(this, kontakt(this, basis).name);
});

When(/^ich nach «([^»]+)» suche, das es nicht gibt$/, async function (begriff) {
  await suchen(this, begriff);
});

const kontaktLink = (welt, basis) =>
  welt.seite.locator(`[data-test=kontakt] a[href="/kontakte/${kontakt(welt, basis).id}"]`);

When(/^ich den Kontakt «([^»]+)» öffne$/, async function (basis) {
  await kontaktLink(this, basis).click();
  await this.seite
    .locator('[data-test=kontakt-name], [data-test=nicht-gefunden]')
    .first()
    .waitFor();
});

When(
  /^ich die Adresse des Kontakts «([^»]+)» aus dem Mandanten «([^»]+)» direkt aufrufe$/,
  async function (basis, mandant) {
    const k = kontakt(this, basis);
    assert.equal(k.mandant, mandant);
    await this.seite.goto(`${new URL(this.seite.url()).origin}/kontakte/${k.id}`);
    const anmelden = this.seite.getByRole('button', { name: 'Anmelden', exact: true });
    await anmelden.click();
    await this.seite
      .locator('[data-test=kontakt-name], [data-test=nicht-gefunden]')
      .first()
      .waitFor();
  },
);

Then(/^zeigt die Liste den Kontakt «([^»]+)»$/, async function (basis) {
  await kontaktLink(this, basis).waitFor();
});

Then(/^zeigt die Liste den Kontakt «([^»]+)» nicht$/, async function (basis) {
  await this.seite.locator('[data-test=anzahl]').waitFor();
  assert.equal(await kontaktLink(this, basis).count(), 0);
});

Then(/^zeigt die Liste keine Kontakte$/, async function () {
  await this.seite.locator('[data-test=keine]').waitFor();
  assert.equal(await this.seite.locator('[data-test=kontakt]').count(), 0);
});

Then(/^sehe ich die Kontaktdetails von «([^»]+)»$/, async function (basis) {
  const k = kontakt(this, basis);
  await this.seite.waitForURL((url) => url.pathname === `/kontakte/${k.id}`);
  await this.seite.getByRole('heading', { name: k.name, level: 1 }).waitFor();
});

Then(/^zeigen die Stammdaten bei «([^»]+)» den Wert «([^»]+)»$/, async function (feld, wert) {
  const zeile = this.seite
    .locator('[data-test=stammdaten] > div')
    .filter({ has: this.seite.getByText(feld, { exact: true }) });
  assert.equal((await zeile.locator('dd').innerText()).trim(), wert);
});

Then(
  /^zeigen die Stammdaten bei «([^»]+)» den Namen des Kontakts «([^»]+)»$/,
  async function (feld, basis) {
    const zeile = this.seite
      .locator('[data-test=stammdaten] > div')
      .filter({ has: this.seite.getByText(feld, { exact: true }) });
    assert.equal((await zeile.locator('dd').innerText()).trim(), kontakt(this, basis).name);
  },
);

Then(/^zeigt die Seite «nicht gefunden» ohne Kontaktdaten$/, async function () {
  const seite = this.seite.locator('[data-test=nicht-gefunden]');
  await seite.waitFor();
  assert.ok((await seite.innerText()).includes('Kontakt nicht gefunden'));
  assert.equal(await this.seite.locator('[data-test=stammdaten]').count(), 0);
  assert.equal(await this.seite.locator('[data-test=kontakt-name]').count(), 0);
});

Then(/^zeigt die Seite die Meldung «([^»]+)» und keine Kontaktdaten$/, async function (text) {
  const fehler = this.seite.locator('[data-test=fehler]');
  await fehler.waitFor();
  assert.ok((await fehler.innerText()).includes(text), await fehler.innerText());
  assert.equal(await this.seite.locator('[data-test=kontakt]').count(), 0);
});

const AKTIONEN = {
  Bearbeiten: 'bearbeiten',
  'Zusammenführen …': 'zusammenfuehren',
  'Als inaktiv markieren': 'inaktiv',
  'Wieder aktivieren': 'wieder-aktivieren',
};

Then(/^sehe ich keinen der Knöpfe «(.+)»$/, async function (liste) {
  await this.seite.locator('[data-test=stammdaten]').waitFor();
  for (const knopf of liste.split('», «')) {
    assert.equal(
      await this.seite.locator(`[data-test=${AKTIONEN[knopf]}]`).count(),
      0,
      `Knopf «${knopf}» sichtbar`,
    );
    assert.equal(await this.seite.getByRole('button', { name: knopf, exact: true }).count(), 0);
  }
});

Then(/^sehe ich die Knöpfe «(.+)»$/, async function (liste) {
  for (const knopf of liste.split('», «')) {
    await this.seite.locator(`[data-test=${AKTIONEN[knopf]}]`).waitFor();
  }
});

When(/^ich die Aktion «([^»]+)» wähle$/, async function (knopf) {
  await this.seite.locator(`[data-test=${AKTIONEN[knopf]}]`).click();
});

const FELD_TEST = { 'E-Mail': 'email', IBAN: 'iban', UID: 'uid', PLZ: 'plz' };

When(/^ich im Formular das Feld «([^»]+)» auf «([^»]+)» ändere$/, async function (feld, wert) {
  await this.seite.locator(`[data-test=feld-${FELD_TEST[feld] ?? feld.toLowerCase()}]`).fill(wert);
});

When('ich das Formular speichere', async function () {
  await this.seite.locator('[data-test=speichern]').click();
});

// wartet auf die Meldung mit dem Text (eine ältere Meldung der Seite verschwindet erst mit der Antwort des Archivs)
Then(/^meldet die Seite «([^»]+)»$/, async function (text) {
  await this.seite.locator('[data-test=erfolg]', { hasText: text }).waitFor();
});

Then(
  /^steht beim Kontakt «([^»]+)» im Archiv bei «([^»]+)» der Wert «([^»]+)»$/,
  async function (basis, feld, wert) {
    const k = kontakt(this, basis);
    const antwort = await api(k.rolle, k.mandant, 'GET', `/kontakte/${k.id}`);
    assert.equal(antwort.status, 200);
    assert.equal(antwort.json[feld], wert);
  },
);

// ---------------------------------------------------------------- Zusammenführen und Inaktivmarkieren

When(/^ich den Kontakt «([^»]+)» als Ziel suche und wähle$/, async function (basis) {
  const k = kontakt(this, basis);
  const antwort = this.seite.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/kontakte' &&
      new URL(r.url()).searchParams.get('q') === k.name,
  );
  await this.seite.locator('[data-test=ziel-suche]').fill(k.name);
  await antwort;
  await this.seite.locator('[data-test=ziel-vorschlag]', { hasText: k.name }).click();
});

When(/^ich als Grund «([^»]+)» angebe$/, async function (grund) {
  await this.seite
    .locator('[data-test=zusammenfuehren-grund], [data-test=inaktiv-grund]')
    .fill(grund);
});

When('ich im Dialog auf «Weiter» klicke', async function () {
  await this.seite.locator('[data-test=weiter]').click();
  await this.seite.locator('[data-test=rueckfrage-text]').waitFor();
});

Then(
  /^fragt die Seite zurück, ob «([^»]+)» in «([^»]+)» zusammengeführt werden soll$/,
  async function (quelle, ziel) {
    const text = await this.seite.locator('[data-test=rueckfrage-text]').innerText();
    assert.ok(
      text.includes(`«${kontakt(this, quelle).name}»`) &&
        text.includes(`«${kontakt(this, ziel).name}»`),
      text,
    );
    assert.ok(
      (await this.seite.locator('[data-test=rueckfrage-zahlen]').innerText()).includes('Dokument'),
    );
    assert.ok(
      (await this.seite.locator('[data-test=nicht-umkehrbar]').innerText()).includes(
        'nicht umkehren',
      ),
    );
  },
);

Then(/^hat die PWA noch nichts zu «(zusammenfuehren|inaktiv|aktiv)» gesendet$/, function (aktion) {
  const gesendet = this.weiterleitung.anfragen.filter(
    (a) => a.methode === 'POST' && a.pfad.endsWith(`/${aktion}`),
  );
  assert.deepEqual(gesendet, []);
});

When('ich die Rückfrage mit «Zusammenführen» bestätige', async function () {
  await this.seite.locator('[data-test=zusammenfuehren-ausfuehren]').click();
});

When('ich die Rückfrage mit «Als inaktiv markieren» bestätige', async function () {
  await this.seite.locator('[data-test=inaktiv-ausfuehren]').click();
});

When('ich «Wieder aktivieren» wähle', async function () {
  await this.seite.locator('[data-test=wieder-aktivieren]').click();
});

When('ich die Rückfrage abbreche', async function () {
  await this.seite.locator('[data-test=dialog] [data-test$=abbrechen]').click();
  await this.seite.locator('[data-test=dialog]').waitFor({ state: 'detached' });
});

Then(
  /^hat die PWA «(zusammenfuehren|inaktiv|aktiv)» mit «If-Match» und dem Grund «([^»]*)» gesendet$/,
  function (aktion, grund) {
    const a = this.weiterleitung.anfragen.findLast(
      (x) => x.methode === 'POST' && x.pfad.endsWith(`/${aktion}`),
    );
    assert.ok(a, `kein POST ${aktion}`);
    assert.match(a.ifMatch, /^"\d+"$/);
    assert.equal(a.status, aktion === 'zusammenfuehren' ? 200 : 200, `Status ${a.status}`);
    if (grund) {
      assert.equal(a.body.grund, grund);
    }
  },
);

Then(
  /^ist der Kontakt «([^»]+)» im Archiv inaktiv und in «([^»]+)» zusammengeführt$/,
  async function (quelle, ziel) {
    const k = kontakt(this, quelle);
    const antwort = await api(k.rolle, k.mandant, 'GET', `/kontakte/${k.id}`);
    assert.equal(antwort.json.aktiv, false);
    assert.equal(antwort.json.zusammengefuehrtIn.id, kontakt(this, ziel).id);
  },
);

Then(/^ist der Kontakt «([^»]+)» im Archiv (aktiv|inaktiv)$/, async function (basis, zustand) {
  const k = kontakt(this, basis);
  const antwort = await api(k.rolle, k.mandant, 'GET', `/kontakte/${k.id}`);
  assert.equal(antwort.json.aktiv, zustand === 'aktiv');
});

Then(/^zeigt die Seite den Link auf den Zielkontakt «([^»]+)»$/, async function (basis) {
  const href = await this.seite.locator('[data-test=ziel-link]').getAttribute('href');
  assert.equal(href, `/kontakte/${kontakt(this, basis).id}`);
  assert.equal(await this.seite.locator('[data-test=in-link]').getAttribute('href'), href);
});

Then(
  /^erscheint der Kontakt «([^»]+)» in der Partnerauswahl des Archivs nicht mehr$/,
  async function (basis) {
    const k = kontakt(this, basis);
    const antwort = await api(
      k.rolle,
      k.mandant,
      'GET',
      `/kontakte?q=${encodeURIComponent(k.name)}`,
    );
    assert.equal(antwort.status, 200);
    assert.ok(!antwort.json.some((e) => e.id === k.id));
  },
);

// ---------------------------------------------------------------- Rechte und Protokoll (Req 17.5, 17.6)

Then(
  /^verweigert das Archiv «([^»]+)» im Mandanten «([^»]+)» die Kontaktliste mit 403 «([A-Z_]+)»$/,
  async function (rolle, mandant, code) {
    const antwort = await api(rolle, mandant, 'GET', '/kontakte/liste');
    assert.equal(antwort.status, 403);
    assert.equal(antwort.json.fehlercode, code);
  },
);

Then(
  /^enthält das Zugriffsprotokoll von «([^»]+)» eine neue Verweigerung «VERWEIGERT» für die Rolle «([A-Z_]+)» mit dem Grund «([A-Z0-9]+)»$/,
  async function (mandant, rolle, grund) {
    // das Protokoll liest der Inhaber des Mandanten (A13); die Zeit der PWA-Anfrage liegt nach Szenariobeginn
    const antwort = await api(
      'inhaber',
      mandant,
      'GET',
      '/protokoll?aktion=VERWEIGERT&groesse=100',
    );
    assert.equal(antwort.status, 200, JSON.stringify(antwort.json));
    const treffer = antwort.json.eintraege.filter((e) => {
      const details = e.details ? JSON.parse(e.details) : {};
      return (
        e.rolle === rolle && details.grund === grund && Date.parse(e.zeit) >= this.szenarioStart
      );
    });
    assert.ok(
      treffer.length >= 1,
      `kein Eintrag VERWEIGERT/${rolle}/${grund}: ${JSON.stringify(antwort.json.eintraege.slice(0, 3))}`,
    );
  },
);

Given('der Szenariobeginn ist gemerkt', function () {
  this.szenarioStart = Date.now() - 1000;
});

Then(
  /^sieht «([^»]+)» im Mandanten «([^»]+)» bei der Suche nach «([^»]+)» dieselben Kontakte wie die Liste$/,
  async function (rolle, mandant, basis) {
    const k = kontakt(this, basis);
    const antwort = await api(
      rolle,
      mandant,
      'GET',
      `/kontakte/liste?q=${encodeURIComponent(k.name)}&status=AKTIV`,
    );
    assert.equal(antwort.status, 200);
    const imArchiv = antwort.json.eintraege.map((e) => e.id).sort();
    const gezeigt = (
      await this.seite
        .locator('[data-test=kontakt] a[href^="/kontakte/"]')
        .evaluateAll((l) => l.map((a) => a.getAttribute('href')))
    )
      .map((h) => h.replace('/kontakte/', ''))
      .sort();
    assert.ok(imArchiv.length >= 1);
    assert.deepEqual(gezeigt, imArchiv);
  },
);

// ---------------------------------------------------------------- Dokumente des Kontakts (Req 17.3, 17.4)

Then(/^zeigt die Dokumentliste genau «(.*)»$/, async function (liste) {
  await this.seite
    .locator('[data-test=dokumente-anzahl], [data-test=dokumente-keine]')
    .first()
    .waitFor();
  const soll = liste
    .split('», «')
    .filter(Boolean)
    .map((datei) => `/dokument/${echteDokumente(this).get(datei)}`);
  const ist = await this.seite
    .locator('[data-test=dokument] a')
    .evaluateAll((l) => l.map((a) => a.getAttribute('href')));
  assert.deepEqual([...ist].sort(), [...soll].sort());
});

Then(
  /^nennt die Dokumentliste die Gesamtzahl (\d+) wie die Antwort des Archivs$/,
  async function (zahl) {
    const anzeige = await this.seite.locator('[data-test=dokumente-anzahl]').innerText();
    assert.ok(anzeige.includes(`${zahl} Dokument`), anzeige);
    const antwort = this.weiterleitung.anfragen.findLast(
      (a) => a.pfad === '/api/v1/dokumente' && a.status === 200,
    );
    assert.equal(antwort.antwort.gesamt, Number(zahl));
  },
);

Then(
  /^hat die PWA die Dokumente des Kontakts «([^»]+)» nach Datum ohne Suchbegriff angefragt$/,
  function (basis) {
    const a = this.weiterleitung.anfragen.findLast((x) => x.pfad === '/api/v1/dokumente');
    assert.ok(a, 'keine Dokumentabfrage');
    assert.equal(a.query.get('partner'), kontakt(this, basis).id);
    assert.equal(a.query.get('sortierung'), 'DATUM');
    assert.equal(a.query.has('q'), false);
    assert.equal(a.query.get('seite'), '0');
  },
);

Then(/^zeigt die Dokumentliste den Titel von «([^»]+)»$/, async function (datei) {
  const titel = this.dokumentTitel.get(datei);
  const link = this.seite.locator(
    `[data-test=dokument] a[href="/dokument/${echteDokumente(this).get(datei)}"]`,
  );
  assert.ok((await link.innerText()).includes(titel), await link.innerText());
});

// ---------------------------------------------------------------- Handy (Req 17.17)

Then('lässt sich die Kontaktseite nicht waagrecht scrollen', async function () {
  const breiten = await this.seite.evaluate(() => ({
    inhalt: document.documentElement.scrollWidth,
    fenster: document.documentElement.clientWidth,
  }));
  assert.ok(breiten.inhalt <= breiten.fenster, JSON.stringify(breiten));
});

Then('liegt der Dialog innerhalb des Bildschirms', async function () {
  const dialog = this.seite.locator('[data-test=dialog]');
  await dialog.waitFor();
  const masse = await dialog.evaluate((e) => {
    const r = e.getBoundingClientRect();
    return {
      links: r.left,
      rechts: r.right,
      breite: window.innerWidth,
      inhalt: e.scrollWidth,
      sichtbar: e.clientWidth,
    };
  });
  assert.ok(masse.links >= 0 && masse.rechts <= masse.breite, JSON.stringify(masse));
  assert.ok(masse.inhalt <= masse.sichtbar, JSON.stringify(masse));
});

Then(/^zeigt die Seite die Nutzer-Texte von «([^»]+)» nur als Text$/, async function (basis) {
  const k = kontakt(this, basis);
  const kopf = await this.seite.locator('[data-test=kontakt-name]').innerText();
  assert.ok(kopf.includes(k.name), kopf);
  assert.equal(
    await this.seite.locator('[data-test=kontakt-name] b, [data-test=stammdaten] b').count(),
    0,
  );
});

Then(
  /^zeigt die Liste den Kontakt «([^»]+)» mit dem Namen als reinem Text$/,
  async function (basis) {
    const k = kontakt(this, basis);
    const link = kontaktLink(this, basis);
    await link.waitFor();
    assert.ok((await link.innerText()).includes(k.name), await link.innerText());
    assert.equal(await this.seite.locator('[data-test=kontakt] b').count(), 0);
  },
);

Then('hat die PWA die Kontakte-Schnittstelle nicht aufgerufen', async function () {
  await this.seite.waitForLoadState('networkidle');
  assert.deepEqual(
    this.apiAnfragen.filter((url) => new URL(url).pathname.startsWith('/api/v1/kontakte')),
    [],
    'die PWA hat die Kontakte abgerufen',
  );
});

Then(/^sehe ich im geöffneten Menü genau «(.*)»$/, async function (liste) {
  // auf schmalen Bildschirmen liegt das Menü hinter dem Knopf «Menü öffnen»
  const oeffnen = this.seite.getByRole('button', { name: 'Menü öffnen', exact: true });
  if (await oeffnen.isVisible()) {
    await oeffnen.click();
  }
  const nav = this.seite.getByRole('navigation', { name: 'Hauptnavigation' });
  await nav.waitFor();
  const punkte = await nav
    .locator('a .punkt')
    .evaluateAll((elemente) => elemente.map((e) => e.firstChild?.textContent?.trim() ?? ''));
  assert.deepEqual(punkte, liste.split(', '));
});
