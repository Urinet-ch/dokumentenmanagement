import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import axe from 'axe-core';

const PERSON = {
  id: 'probe', benutzername: 'a.beispiel', vorname: 'Alex', nachname: 'Beispiel',
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

Given('die Benutzerverwaltung liefert synthetische Daten', async function () {
  this.benutzerAnfragen = [];
  this.benutzerEinladungen = [];
  this.benutzerAktionen = [];
  await this.seite.route((url) => url.pathname === '/api/v1/rollen'
    || url.pathname === '/api/v1/benutzer' || url.pathname.startsWith('/api/v1/benutzer/'), async (route) => {
    const url = new URL(route.request().url());
    this.benutzerAnfragen.push(url.pathname);
    let antwort;
    if (url.pathname === '/api/v1/benutzer/probe/sperren' && route.request().method() === 'POST') {
      this.benutzerAktionen.push({ aktion: 'sperren', body: route.request().postDataJSON() });
      return route.fulfill({ status: 204 });
    } else if (url.pathname === '/api/v1/benutzer' && route.request().method() === 'POST') {
      this.benutzerEinladungen.push(route.request().postDataJSON());
      antwort = { id: 'synthetische-einladung' };
    } else if (url.pathname === '/api/v1/benutzer') antwort = [PERSON];
    else if (url.pathname === '/api/v1/benutzer/probe') antwort = PERSON;
    else if (url.pathname === '/api/v1/benutzer/probe/rechte') antwort = ROLLE;
    else if (url.pathname === '/api/v1/rollen') antwort = [ROLLE];
    else if (url.pathname === '/api/v1/benutzer/antraege') {
      antwort = [{ id: 'antrag', art: 'INHABER_ROLLE', ziel: 'probe', beantragtVon: 'andere',
        beantragtAm: '2026-11-22T20:00:00Z', gueltigBis: '2026-11-25T20:00:00Z' }];
    } else {
      return route.fulfill({ status: 404 });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(antwort) });
  });
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
    assert.deepEqual(this.benutzerAktionen, [{ aktion: 'sperren', body: { grund: 'SICHERHEIT' } }]);
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
