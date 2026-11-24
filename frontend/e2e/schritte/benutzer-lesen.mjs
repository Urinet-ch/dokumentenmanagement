import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import axe from 'axe-core';

const PERSON = {
  id: 'probe', etag: 'a'.repeat(64), benutzername: 'a.beispiel', vorname: 'Alex', nachname: 'Beispiel',
  rollen: ['MITARBEITER'], status: 'AKTIV',
  rollenkonflikt: false, totp: true, google: false, voruebergehendGesperrt: false,
  letzteAktion: { zeit: '2026-11-22T20:00:00Z', aktion: 'ANMELDUNG' },
};
const ROLLE = {
  code: 'mitarbeiter', beschreibung: 'Mitarbeiterin oder Mitarbeiter',
  rechte: [
    { aktion: 'A1', bezeichnung: 'Dokumente erfassen', ergebnis: 'ERLAUBT', fussnoten: [] },
    { aktion: 'A2', bezeichnung: 'Dokumente lesen', ergebnis: 'NUR_EIGENE',
      fussnoten: [{ zeichen: '1', text: 'Nur eigene Dokumente' }] },
    { aktion: 'A7', bezeichnung: 'Bestätigen', ergebnis: 'VERBOTEN', fussnoten: [] },
    { aktion: 'A21', bezeichnung: 'Benutzer ansehen', ergebnis: 'VERBOTEN', fussnoten: [] },
  ],
};

const ROLLE_INHABER = {
  code: 'inhaber', beschreibung: 'Inhaberin oder Inhaber',
  rechte: [
    { aktion: 'A1', bezeichnung: 'Dokumente erfassen', ergebnis: 'ERLAUBT', fussnoten: [] },
    { aktion: 'A2', bezeichnung: 'Dokumente lesen', ergebnis: 'ERLAUBT', fussnoten: [] },
    { aktion: 'A21', bezeichnung: 'Benutzer ansehen', ergebnis: 'ERLAUBT', fussnoten: [] },
  ],
};
// Aktion -> [Methode, Pfad]; der Pfad der Aktionen am synthetischen Benutzer
const AKTIONSPFADE = {
  sperren: ['POST', '/api/v1/benutzer/probe/sperren'],
  rolle: ['PUT', '/api/v1/benutzer/probe/rolle'],
  loeschen: ['POST', '/api/v1/benutzer/probe/loeschen'],
  profil: ['PATCH', '/api/v1/benutzer/probe'],
  zugang: ['POST', '/api/v1/benutzer/probe/zugang-zuruecksetzen'],
};

Given('die Benutzerverwaltung liefert synthetische Daten', async function () {
  this.benutzerAnfragen = [];
  this.benutzerListenAbfragen = [];
  this.benutzerEinladungen = [];
  this.benutzerAktionen = [];
  this.benutzerPerson = PERSON;
  this.benutzerRollen = [ROLLE];
  this.benutzerVerlangt = {};
  await this.seite.route((url) => url.pathname === '/api/v1/rollen'
    || url.pathname === '/api/v1/benutzer' || url.pathname.startsWith('/api/v1/benutzer/'), async (route) => {
    const url = new URL(route.request().url());
    this.benutzerAnfragen.push(url.pathname);
    let antwort;
    const aktion = Object.entries(AKTIONSPFADE).find(([, [methode, pfad]]) =>
      route.request().method() === methode && url.pathname === pfad)?.[0];
    if (aktion) {
      const eintrag = { aktion, body: route.request().postDataJSON(), ifMatch: route.request().headers()['if-match'] };
      const verlangt = this.benutzerVerlangt[aktion];
      if (verlangt?.fehler) {
        this.benutzerAktionen.push({ ...eintrag, status: 503 });
        return route.fulfill({ status: 503, json: { fehlercode: verlangt.fehler } });
      }
      if (verlangt === 'step-up' && !this.benutzerAktionen.some((alt) => alt.aktion === aktion)) {
        this.benutzerAktionen.push({ ...eintrag, status: 401 });
        return route.fulfill({ status: 401, json: { fehlercode: 'STEP_UP_NOETIG' } });
      }
      if (verlangt === 'bestaetigung') {
        this.benutzerAktionen.push({ ...eintrag, status: 409 });
        return route.fulfill({ status: 409, json: { fehlercode: 'BESTAETIGUNG_NOETIG',
          auswirkung: [{ schluessel: 'sperren', text: 'Alle Sitzungen der Person werden beendet.' }] } });
      }
      this.benutzerAktionen.push({ ...eintrag, status: 204 });
      return route.fulfill({ status: 204 });
    } else if (url.pathname === '/api/v1/benutzer/probe/loeschvorschau') {
      antwort = { dokumente: 3, protokolleintraege: 7 };
    } else if (url.pathname === '/api/v1/benutzer' && route.request().method() === 'POST') {
      this.benutzerEinladungen.push(route.request().postDataJSON());
      antwort = { id: 'synthetische-einladung' };
    } else if (url.pathname === '/api/v1/benutzer') {
      this.benutzerListenAbfragen.push(url.search);
      antwort = [this.benutzerPerson];
    }
    else if (url.pathname === '/api/v1/benutzer/probe') antwort = this.benutzerPerson;
    else if (url.pathname === '/api/v1/benutzer/probe/rechte') antwort = ROLLE;
    else if (url.pathname === '/api/v1/rollen') antwort = this.benutzerRollen;
    else if (url.pathname === '/api/v1/benutzer/antraege') {
      antwort = [{ id: 'antrag', art: 'INHABER_ROLLE', ziel: 'probe', beantragtVon: 'andere',
        beantragtAm: '2026-11-22T20:00:00Z', gueltigBis: '2026-11-25T20:00:00Z' }];
    } else {
      return route.fulfill({ status: 404 });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(antwort) });
  });
});

Given('der synthetische Benutzer hat {int} Passkeys', function (anzahl) {
  this.benutzerPerson = { ...this.benutzerPerson, passkey: anzahl > 0, passkeyAnzahl: anzahl };
});

Given('die Benutzerverwaltung antwortet beim Zurücksetzen des Zugangs mit dem Fehler «IDENTITAETSDIENST_NICHT_ERREICHBAR»', function () {
  this.benutzerVerlangt.zugang = { fehler: 'IDENTITAETSDIENST_NICHT_ERREICHBAR' };
});

Given('die synthetischen Rollen enthalten zusätzlich «inhaber»', function () {
  this.benutzerRollen = [ROLLE, ROLLE_INHABER];
});

Given('der synthetische Benutzer ist deaktiviert', function () {
  this.benutzerPerson = { ...PERSON, status: 'DEAKTIVIERT' };
});

Given(/^die Benutzerverwaltung verlangt vor «(sperren|rolle|loeschen|profil|zugang)» ein Step-up$/, async function (aktion) {
  this.benutzerVerlangt[aktion] = 'step-up';
  await speicherAufzeichnen(this);
});

Given('die Benutzerverwaltung verlangt vor «sperren» eine Bestätigung', function () {
  this.benutzerVerlangt.sperren = 'bestaetigung';
});

// Zeichnet jede Schreibung in den Browser-Speicher auf, auch über die Weiterleitung zu Keycloak hinweg
async function speicherAufzeichnen(welt) {
  welt.speicherSchreibungen = [];
  await welt.seite.exposeFunction('archivSpeicherSchreibt', (schluessel, wert) => {
    welt.speicherSchreibungen.push({ schluessel, wert });
  });
  await welt.seite.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (schluessel, wert) {
      window.archivSpeicherSchreibt?.(String(schluessel), String(wert));
      return original.call(this, schluessel, wert);
    };
  });
}

When('ich die Rolle auf «inhaber» ändere', async function () {
  await this.seite.getByRole('button', { name: 'Rolle ändern', exact: true }).click();
  await this.seite.locator('#neue-rolle').selectOption('inhaber');
  await this.seite.getByRole('button', { name: 'Rollenänderung bestätigen' }).click();
});

When('ich den Löschdialog öffne', async function () {
  await this.seite.getByRole('button', { name: 'Benutzer löschen', exact: true }).click();
  await this.seite.getByRole('dialog').waitFor();
});

When('ich im Löschdialog den Benutzer «a.beispiel» mit dem Grund «Sicherheit» bestätige', async function () {
  const dialog = this.seite.getByRole('dialog');
  await dialog.locator('#loeschen-grund').selectOption({ label: 'Sicherheit' });
  await dialog.locator('#loeschen-bestaetigung').fill('a.beispiel');
  await dialog.getByRole('button', { name: 'Löschen', exact: true }).click();
});

When('ich den Vornamen auf «Neuername» ändere und speichere', async function () {
  await this.seite.locator('#profil-vorname').fill('Neuername');
  await this.seite.getByRole('button', { name: 'Änderungen speichern' }).click();
});

When('ich das Profil, den Rollendialog und den Löschdialog prüfe', async function () {
  await this.seite.getByRole('heading', { name: 'Benutzerangaben ändern' }).waitFor();
  await pruefen(this, 'profil');
  await this.seite.getByRole('button', { name: 'Rolle ändern', exact: true }).click();
  await this.seite.getByRole('dialog').getByText('Rollenänderung bestätigen').waitFor();
  await this.seite.locator('#neue-rolle').selectOption('inhaber');
  await this.seite.getByRole('dialog').getByRole('heading', { name: 'Neue Rechte' }).waitFor();
  await pruefen(this, 'rollendialog');
  await this.seite.getByRole('button', { name: 'Abbrechen' }).click();
  await this.seite.getByRole('dialog').waitFor({ state: 'detached' });
  await this.seite.getByRole('button', { name: 'Benutzer löschen', exact: true }).click();
  await this.seite.getByRole('dialog').getByText('Dokumente mit Bezug zum Benutzer').waitFor();
  await pruefen(this, 'loeschdialog');
  await this.seite.getByRole('button', { name: 'Abbrechen' }).click();
});

Then('sind Profil, Rollendialog und Löschdialog ohne Überlauf und axe-Befund bedienbar', async function () {
  assert.deepEqual(this.benutzerAktionen, []);
});

Then(/^wird die Aktion «(sperren|rolle)» nach Step-up genau einmal ausgeführt$/, async function (aktion) {
  await this.seite.getByText('Die Benutzerangaben wurden aktualisiert.', { exact: false }).waitFor();
  const eigene = this.benutzerAktionen.filter((eintrag) => eintrag.aktion === aktion);
  assert.deepEqual(eigene.map((eintrag) => eintrag.status), [401, 204]);
  assert.deepEqual(eigene[1].body, eigene[0].body);
  assert.equal(eigene[1].ifMatch, eigene[0].ifMatch);
  if (aktion === 'rolle') assert.equal(eigene[1].ifMatch, `"${'a'.repeat(64)}"`);
  assert.equal(this.keycloak.pkce.length, 2);
  assert.ok(this.keycloak.pkce.every(Boolean));
  await pruefen(this, `stepup-${aktion}`);
});

Then('öffnet sich der Löschdialog nach dem Step-up erneut ohne gespeicherte Eingaben', async function () {
  const dialog = this.seite.getByRole('dialog');
  await dialog.waitFor();
  await this.seite.getByText('Aus Datenschutzgründen wurden Ihre Eingaben nicht gespeichert', { exact: false }).waitFor();
  assert.equal(await dialog.locator('#loeschen-bestaetigung').inputValue(), '');
  assert.equal(await dialog.locator('#loeschen-grund').inputValue(), '');
  assert.deepEqual(this.benutzerAktionen.map((eintrag) => eintrag.status), [401]);
  await pruefen(this, 'stepup-loeschen');
});

Then('wurde der Benutzer nach dem Step-up genau einmal gelöscht', async function () {
  await this.seite.waitForURL((url) => url.pathname === '/verwaltung/benutzer');
  assert.deepEqual(this.benutzerAktionen.map((eintrag) => [eintrag.aktion, eintrag.status]),
    [['loeschen', 401], ['loeschen', 204]]);
  assert.deepEqual(this.benutzerAktionen[1].body, { grund: 'SICHERHEIT' });
});

Then('zeigt das Profil nach dem Step-up den Hinweis zur erneuten Eingabe', async function () {
  await this.seite.getByText('Aus Datenschutzgründen wurden Ihre Eingaben nicht gespeichert', { exact: false }).waitFor();
  assert.equal(await this.seite.locator('#profil-vorname').inputValue(), 'Alex');
  assert.deepEqual(this.benutzerAktionen.map((eintrag) => eintrag.status), [401]);
  await pruefen(this, 'stepup-profil');
});

Then('wurde die Profiländerung nach dem Step-up genau einmal ausgeführt', async function () {
  await this.seite.getByText('Die Benutzerangaben wurden aktualisiert.', { exact: false }).waitFor();
  assert.deepEqual(this.benutzerAktionen.map((eintrag) => [eintrag.aktion, eintrag.status]),
    [['profil', 401], ['profil', 204]]);
  assert.deepEqual(this.benutzerAktionen[1].body, { vorname: 'Neuername' });
});

Then('liegt nichts Persönliches im Browser-Speicher', async function () {
  const persoenlich = ['Beispiel', 'beispiel', 'Alex', 'example.invalid', 'Neuername', 'MITARBEITER', 'Sicherheit'];
  assert.ok(this.speicherSchreibungen.length > 0);
  for (const { schluessel, wert } of this.speicherSchreibungen) {
    for (const text of persoenlich) {
      assert.ok(!wert.includes(text), `${schluessel} enthält «${text}»`);
    }
  }
  const fortsetzung = this.speicherSchreibungen.filter(({ schluessel }) => schluessel === 'archiv.benutzer.fortsetzung');
  assert.equal(fortsetzung.length, 1);
  const erlaubt = ['id', 'benutzer', 'mandant', 'aktion', 'ziel', 'etag', 'grund', 'rolle', 'passkeys', 'erstellt'];
  assert.ok(Object.keys(JSON.parse(fortsetzung[0].wert)).every((schluessel) => erlaubt.includes(schluessel)));
  const uebrig = await this.seite.evaluate(() => [localStorage, sessionStorage]
    .map((speicher) => speicher.getItem('archiv.benutzer.fortsetzung')));
  assert.deepEqual(uebrig, [null, null]);
});

Then('zeigt die Benutzerverwaltung die Bestätigungsnotwendigkeit und sendet nichts erneut', async function () {
  await this.seite.getByText('Diese Aktion verlangt eine ausdrückliche Bestätigung', { exact: false }).waitFor();
  await this.seite.getByText('Alle Sitzungen der Person werden beendet.', { exact: false }).first().waitFor();
  assert.deepEqual(this.benutzerAktionen.map((eintrag) => eintrag.status), [409]);
  await pruefen(this, 'bestaetigung-noetig');
});

When('ich sende eine synthetische Benutzereinladung', async function () {
    for (const [feld, wert] of Object.entries({
      benutzername: 'probe.einladung',
      email: 'probe@example.invalid',
      vorname: 'Synthetisch',
      nachname: 'Probe',
    })) {
      await this.seite.getByLabel(feld === 'email' ? 'E-Mail-Adresse' : feld[0].toUpperCase() + feld.slice(1))
        .fill(wert);
    }
    await this.seite.locator('#einladung-rolle').selectOption('mitarbeiter');
    await this.seite.getByRole('button', { name: 'Einladung senden' }).click();
});

When('ich öffne den Dialog zum Sperren', async function () {
    await this.seite.getByRole('button', { name: 'Benutzer sperren', exact: true }).click();
});

When('ich die Sperrung bestätige', async function () {
    await this.seite.locator('#status-grund').selectOption({ index: 1 });
    await this.seite.getByRole('dialog').getByRole('button', { name: 'Bestätigen', exact: true }).click();
});

Then('wird die Einladung bestätigt, ohne Passwort oder Link anzuzeigen', async function () {
    await this.seite.getByText('Die Person erhält eine Einladung per E-Mail und richtet ihr Passwort und die Zwei-Faktor-Anmeldung selbst ein.', { exact: true }).waitFor();
    assert.equal(this.benutzerEinladungen.length, 1);
    assert.equal(this.benutzerEinladungen[0].email, 'probe@example.invalid');
    const formular = this.seite.locator('app-benutzer-einladen');
    assert.equal(await formular.locator('input[type="password"], a[href]').count(), 0);
    await pruefen(this, 'einladung');
});

Then('zeigt der Sperrdialog die Auswirkung und wartet auf die Bestätigung', async function () {
    const dialog = this.seite.getByRole('dialog');
    await dialog.getByText('Die Anmeldung wird gesperrt und die Sitzungen werden beendet.', { exact: false }).waitFor();
    await dialog.getByLabel('Grund').waitFor();
    assert.equal(this.benutzerAktionen.length, 0);
    await pruefen(this, 'sperren-dialog');
});

Then('wird die Sperrung erst nach der Bestätigung einmal gesendet', async function () {
    await this.seite.getByRole('dialog').waitFor({ state: 'detached' });
    assert.deepEqual(this.benutzerAktionen.map(({ aktion, body }) => ({ aktion, body })),
      [{ aktion: 'sperren', body: { grund: 'SICHERHEIT' } }]);
});

async function pruefen(welt, name) {
  await welt.seite.waitForTimeout(250);
  await welt.seite.addScriptTag({ content: axe.source });
  const ergebnis = await welt.seite.evaluate(async () => ({
    ueberlauf: [document.documentElement, document.querySelector('mat-sidenav-content')]
      .filter(Boolean).some((element) => element.scrollWidth > element.clientWidth),
    befunde: (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    })).violations.map(({ id, nodes }) => ({
      id,
      targets: nodes.map((node) => ({ target: node.target, failureSummary: node.failureSummary })),
    })),
  }));
  assert.equal(ergebnis.ueberlauf, false, `${name}: horizontaler Überlauf`);
  assert.deepEqual(ergebnis.befunde, [], `${name}: ${JSON.stringify(ergebnis.befunde)}`);
  if (process.env.BENUTZER_BILDER) {
    await welt.seite.locator('h1').first().scrollIntoViewIfNeeded();
    const groesse = welt.seite.viewportSize().width;
    const schema = await welt.seite.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches ? 'dunkel' : 'hell');
    await welt.seite.screenshot({ path: join(process.env.BENUTZER_BILDER, `${name}-${groesse}-${schema}.png`), fullPage: true });
  }
}

Then('zeigt die Benutzerverwaltung Liste, Anträge und Rollen ohne Überlauf und axe-Befund', async function () {
  await this.seite.getByRole('link', { name: 'Alex Beispiel' }).waitFor();
  await this.seite.getByText('Rolle Inhaber vergeben').waitFor();
  await this.seite.getByText('25.11.2026 21:00').waitFor();
  await this.seite.locator('app-benutzer-liste summary').click();
  await this.seite.getByText('Nur eigene Dokumente').waitFor();
  for (const gruppe of ['Dokumente und Wissen lesen', 'Erfassen und verarbeiten', 'Prüfen und bestätigen', 'Verwalten']) {
    await this.seite.getByRole('heading', { name: gruppe }).waitFor();
  }
  await this.seite.getByLabel('Benutzer suchen').focus();
  await this.seite.keyboard.press('Tab');
  assert.equal(await this.seite.evaluate(() => document.activeElement?.textContent?.trim()), 'Suchen');
  await pruefen(this, 'liste');
});

When('ich den synthetischen Benutzer öffne', async function () {
  await this.seite.getByRole('link', { name: 'Alex Beispiel' }).click();
});

Then('zeigt das Benutzerdetail Status, Rechte und Token-Hinweis ohne Überlauf und axe-Befund', async function () {
  await this.seite.getByRole('heading', { name: 'Alex Beispiel' }).waitFor();
  await this.seite.getByText('Nur eigene Dokumente').waitFor();
  await this.seite.getByText('22.11.2026 21:00', { exact: false }).waitFor();
  await this.seite.getByText('API-Tokens und Webseiten-Schlüssel bleiben beim Sperren oder Deaktivieren gültig.').waitFor();
  await this.seite.getByRole('link', { name: 'API-Tokens verwalten' }).waitFor();
  await pruefen(this, 'detail');
});

Then('fehlt der Menüpunkt Benutzer und es wurde kein Benutzer abgefragt', async function () {
  const oeffnen = this.seite.getByRole('button', { name: 'Menü öffnen', exact: true });
  if (await oeffnen.isVisible()) await oeffnen.click();
  const navigation = this.seite.getByRole('navigation', { name: 'Hauptnavigation' });
  assert.equal(await navigation.getByRole('link', { name: 'Benutzer', exact: true }).count(), 0);
  assert.deepEqual(this.benutzerAnfragen, []);
});

const GERAETEDATEN = /webauthn|credential|schlüsselkennung|userlabel|authenticator-id/i;

Then('zeigt die Benutzerliste «Passkey eingerichtet: Ja, 2 Passkeys» ohne Überlauf und axe-Befund', async function () {
  const eintrag = this.seite.locator('[data-test=benutzer-liste] li').first();
  await eintrag.getByText('Ja, 2 Passkeys', { exact: true }).waitFor();
  assert.match((await eintrag.textContent()).replace(/\s+/g, ' '), /Passkey eingerichtet:\s*Ja, 2 Passkeys/);
  assert.doesNotMatch(await this.seite.locator('app-benutzer-liste').textContent(), GERAETEDATEN);
  await pruefen(this, 'liste-passkey');
});

When('ich die Benutzerliste nach «Mit Passkey» filtere', async function () {
  // Auf die gefilterte Abfrage warten, statt auf «networkidle»: Der Filter löst die Abfrage erst nach dem Änderungsereignis aus.
  await Promise.all([
    this.seite.waitForRequest((anfrage) => new URL(anfrage.url()).searchParams.get('passkey') === 'ja'),
    this.seite.locator('#benutzer-passkey-filter').selectOption({ label: 'Mit Passkey' }),
  ]);
  await this.seite.waitForLoadState('networkidle');
});

Then('wurde die Benutzerliste mit dem Filter «ja» abgefragt', async function () {
  assert.ok(this.benutzerListenAbfragen.some((suche) => new URLSearchParams(suche).get('passkey') === 'ja'),
    this.benutzerListenAbfragen.join(' | '));
  await this.seite.locator('[data-test=benutzer-liste] li').first().waitFor();
});

Then('zeigt das Benutzerdetail «Passkey eingerichtet» «Ja, 2 Passkeys» ohne Gerätedaten, Überlauf und axe-Befund', async function () {
  await this.seite.getByRole('heading', { name: 'Alex Beispiel' }).waitFor();
  const wert = this.seite.locator('[data-test=passkey]');
  assert.equal((await wert.textContent()).trim(), 'Ja, 2 Passkeys');
  await this.seite.getByText('Passkey eingerichtet', { exact: true }).waitFor();
  assert.doesNotMatch(await this.seite.locator('app-benutzer-detail').textContent(), GERAETEDATEN);
  await pruefen(this, 'detail-passkey');
});

When('ich öffne den Dialog zum Zugang zurücksetzen', async function () {
  await this.seite.getByRole('button', { name: 'Zugang zurücksetzen', exact: true }).click();
  await this.seite.getByRole('dialog').waitFor();
});

Then('zeigt der Dialog die Option Passkeys löschen mit der Zahl 2, nicht gewählt, ohne Überlauf und axe-Befund', async function () {
  const dialog = this.seite.getByRole('dialog');
  const option = dialog.getByLabel('Auch die 2 Passkeys löschen');
  await option.waitFor();
  assert.equal(await option.isChecked(), false);
  await dialog.getByText('Gerätedaten sehen Sie nicht', { exact: false }).waitFor();
  assert.doesNotMatch(await dialog.textContent(), GERAETEDATEN);
  assert.deepEqual(this.benutzerAktionen, []);
  await pruefen(this, 'zugang-dialog');
});

When('ich die Option Passkeys wähle und bestätige', async function () {
  const dialog = this.seite.getByRole('dialog');
  await dialog.getByLabel('Auch die 2 Passkeys löschen').check();
  await dialog.getByRole('button', { name: 'Bestätigen', exact: true }).click();
});

When('ich ohne die Option Passkeys bestätige', async function () {
  await this.seite.getByRole('dialog').getByRole('button', { name: 'Bestätigen', exact: true }).click();
});

Then('wird der Zugang genau einmal mit der Option Passkeys zurückgesetzt', async function () {
  await this.seite.getByRole('dialog').waitFor({ state: 'detached' });
  assert.deepEqual(this.benutzerAktionen.map(({ aktion, body, status }) => ({ aktion, body, status })),
    [{ aktion: 'zugang', body: { passkeys: true }, status: 204 }]);
});

Then('wird der Zugang genau einmal ohne die Option Passkeys zurückgesetzt', async function () {
  await this.seite.getByRole('dialog').waitFor({ state: 'detached' });
  assert.deepEqual(this.benutzerAktionen.map(({ aktion, body, status }) => ({ aktion, body, status })),
    [{ aktion: 'zugang', body: {}, status: 204 }]);
});

Then('wird der Zugang nach Step-up genau einmal mit der Option Passkeys zurückgesetzt', async function () {
  await this.seite.getByText('Die Benutzerangaben wurden aktualisiert.', { exact: false }).waitFor();
  const eigene = this.benutzerAktionen.filter((eintrag) => eintrag.aktion === 'zugang');
  assert.deepEqual(eigene.map((eintrag) => eintrag.status), [401, 204]);
  assert.deepEqual(eigene[0].body, { passkeys: true });
  assert.deepEqual(eigene[1].body, { passkeys: true });
  assert.equal(this.keycloak.pkce.length, 2);
  await pruefen(this, 'stepup-zugang');
});

Then('zeigt das Benutzerdetail die Fehlermeldung des Identitätsdienstes ohne Überlauf und axe-Befund', async function () {
  await this.seite.getByRole('alert').getByText('Der Identitätsdienst ist nicht erreichbar.', { exact: false }).waitFor();
  assert.deepEqual(this.benutzerAktionen.map((eintrag) => eintrag.status), [503]);
  assert.equal(await this.seite.getByRole('button', { name: 'Zugang zurücksetzen', exact: true }).isEnabled(), true);
  await pruefen(this, 'zugang-fehler');
});
