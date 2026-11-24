import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import { basisUrl, benutzer, keycloakAttrappe } from '../hilfen/keycloak-attrappe.mjs';
import { archivAttrappe } from '../hilfen/archiv-attrappe.mjs';

Given(/^das Branding heisst «(.+)» und hat ein Logo$/, async function (name) {
  const logo = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
  await this.seite.route('**/api/v1/branding**', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ name, logoUrl: logo, primaerfarbe: '#eeb111', sprache: 'de-CH' }),
  }));
  this.brandingLogo = logo;
});

async function einfuehrungsSchritte(seite, rolle, name) {
  const quelltexte = await Promise.all([
    'gemeinsam/texte.de-CH.ts',
    'gemeinsam/navigation.ts',
    'gemeinsam/anmeldung/rechte.ts',
    'hilfe/einfuehrung-schritte.ts',
  ].map(async (pfad) => ts.transpileModule(
    await readFile(new URL(`../../src/app/${pfad}`, import.meta.url), 'utf8'),
    { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } },
  ).outputText));
  return seite.evaluate(async ({ quelltexte, rolle, name }) => {
    const urls = [];
    const modul = (code) => {
      const url = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
      urls.push(url);
      return url;
    };
    try {
      const texte = modul(quelltexte[0]);
      const navigation = modul(quelltexte[1].replace("'./texte.de-CH'", `'${texte}'`));
      const rechte = await import(modul(quelltexte[2]));
      const schrittModul = await import(modul(quelltexte[3]
        .replace("'../gemeinsam/navigation'", `'${navigation}'`)
        .replace("'../gemeinsam/texte.de-CH'", `'${texte}'`)));
      return schrittModul.einfuehrungSchritte((aktion) => rechte.darf(rolle, aktion), name, rolle);
    } finally {
      urls.forEach((url) => URL.revokeObjectURL(url));
    }
  }, { quelltexte, rolle, name });
}

Then(/^zeigt die Einführung den Namen «(.+)» und das Logo aus dem Branding$/, async function (name) {
  const karte = this.seite.locator('app-start .einfuehrung');
  await karte.getByRole('heading', { name: `Willkommen im ${name}` }).waitFor();
  assert.equal(await karte.locator('img').getAttribute('src'), this.brandingLogo);
});

Given(/^ein bekannter Benutzer «(.+)» im Mandanten «(.+)» ist angemeldet$/, async function (rolle, mandant) {
  const kc = await keycloakAttrappe(this);
  kc.benutzer = benutzer(rolle, mandant);
  const k = createHash('sha256').update(`${mandant}:${kc.benutzer.id}`).digest('hex');
  await this.seite.goto(basisUrl);
  await this.seite.evaluate((kennung) => {
    localStorage.setItem('archiv.einfuehrung', JSON.stringify({
      v: 1,
      eintraege: [{ k: kennung, version: 1, art: 'ABGESCHLOSSEN', am: '2026-11-21' }],
    }));
  }, k);
  await this.seite.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await this.seite.getByRole('button', { name: 'Abmelden', exact: true }).waitFor();
});

Then(/^sehe ich die Einführung mit meiner Rolle «(.+)» über den Kacheln$/, async function (rolle) {
  const karte = this.seite.locator('app-start .einfuehrung');
  await karte.waitFor();
  assert.ok((await karte.innerText()).includes(`«${rolle}»`));
  assert.equal(await karte.locator('h2').count(), 1);
  const kachel = this.seite.locator('app-start .kacheln a').first();
  await kachel.waitFor();
  assert.ok((await karte.boundingBox()).y < (await kachel.boundingBox()).y);
});

Then(/^enthält die Schrittfolge für «(.+)» die Schritte «(.*)» und Bereiche «(.*)»$/, async function (rolle, schritte, bereiche) {
  const folge = await einfuehrungsSchritte(this.seite, rolle, 'Archiv');
  const ergebnis = {
    schritte: folge.map((schritt) => schritt.titel),
    bereiche: folge.flatMap((schritt) => schritt.bereiche?.map((bereich) => bereich.text) ?? []),
  };
  assert.deepEqual(ergebnis.schritte, schritte.split('; '));
  assert.deepEqual(ergebnis.bereiche, bereiche ? bereiche.split('; ') : []);
});

Then(/^bleiben die Einführungstexte für «(.+)» bei den Brandings «(.+)» und «(.+)» gleich$/, async function (rolle, erstesBranding, zweitesBranding) {
  const erste = await einfuehrungsSchritte(this.seite, rolle, erstesBranding);
  const zweite = await einfuehrungsSchritte(this.seite, rolle, zweitesBranding);
  assert.equal(erste[0].titel, `Willkommen im ${erstesBranding}`);
  assert.equal(zweite[0].titel, `Willkommen im ${zweitesBranding}`);
  const ohneBranding = (schritte) => schritte.map((schritt, index) =>
    index === 0 ? { ...schritt, titel: '' } : schritt,
  );
  assert.deepEqual(ohneBranding(erste), ohneBranding(zweite));
});

Then('sehe ich die Startseite ohne Einführung', async function () {
  await this.seite.locator('app-start .seitenkopf').waitFor();
  await this.seite.waitForTimeout(500);
  assert.equal(await this.seite.locator('app-start .einfuehrung').count(), 0);
});

Then('sehe ich die Meldung für fehlenden Zugang', async function () {
  await this.seite.getByRole('alert').filter({ hasText: 'kein Mandant oder keine Rolle' }).waitFor();
});

Then('sendet die Startseite mit Einführung dieselben API-Anfragen wie ohne Einführung', async function () {
  const messen = async (bekannt) => {
    const kontext = await this.browser.newContext({ locale: 'de-CH', serviceWorkers: 'block' });
    const seite = await kontext.newPage();
    try {
      const welt = { kontext, seite };
      await archivAttrappe(welt);
      const kc = await keycloakAttrappe(welt);
      kc.benutzer = benutzer('inhaber', 'urinet');
      await seite.goto(basisUrl);
      if (bekannt) {
        const k = createHash('sha256').update(`urinet:${kc.benutzer.id}`).digest('hex');
        await seite.evaluate((kennung) => localStorage.setItem('archiv.einfuehrung', JSON.stringify({
          v: 1, eintraege: [{ k: kennung, version: 1, art: 'ABGESCHLOSSEN', am: '2026-11-21' }],
        })), k);
      }
      const anfragen = [];
      seite.on('request', (anfrage) => {
        const url = new URL(anfrage.url());
        if (url.pathname.startsWith('/api/')) anfragen.push(`${anfrage.method()} ${url.pathname}`);
      });

      await seite.getByRole('button', { name: 'Anmelden', exact: true }).click();
      await seite.getByRole('button', { name: 'Abmelden', exact: true }).waitFor();
      if (!bekannt) await seite.locator('app-start .einfuehrung').waitFor();
      await seite.waitForLoadState('networkidle');
      return anfragen.sort();
    } finally {
      await kontext.close();
    }
  };
  assert.deepEqual(await messen(false), await messen(true));
});

Then(/^zeigt die Einführung «(Schritt \d+ von \d+)»(?: (ohne Zurück|mit Fokus auf der Schrittüberschrift))?$/, async function (fortschritt, zusatz) {
  const karte = this.seite.locator('app-start .einfuehrung');
  await karte.getByText(fortschritt, { exact: true }).waitFor();
  if (zusatz === 'ohne Zurück') assert.equal(await karte.getByRole('button', { name: 'Zurück', exact: true }).count(), 0);
  if (zusatz === 'mit Fokus auf der Schrittüberschrift') {
    await this.seite.waitForFunction(() => document.activeElement?.id === 'einfuehrung-titel');
  }
});

When(/^ich in der Einführung mit der Tastatur «(.+)» wähle$/, async function (name) {
  const karte = this.seite.locator('app-start .einfuehrung');
  const fortschritt = await karte.locator('p').first().innerText();
  const knopf = karte.getByRole('button', { name, exact: true });
  await knopf.focus();
  await this.seite.keyboard.press('Enter');
  if (name === 'Weiter' || name === 'Zurück') {
    await this.seite.waitForFunction((alt) =>
      document.querySelector('app-start .einfuehrung p')?.textContent?.trim() !== alt, fortschritt);
  }
});

When('ich die Einführung bis zum letzten Schritt durchgehe', async function () {
  const karte = this.seite.locator('app-start .einfuehrung');
  const fortschritt = await karte.locator('p').first().innerText();
  const [, aktuell, gesamt] = fortschritt.match(/^Schritt (\d+) von (\d+)$/);
  for (let schritt = Number(aktuell) + 1; schritt <= Number(gesamt); schritt++) {
    await karte.getByRole('button', { name: 'Weiter', exact: true }).click({ timeout: 5000 });
    await karte.getByText(`Schritt ${schritt} von ${gesamt}`, { exact: true }).waitFor({ timeout: 5000 });
  }
  await karte.getByRole('button', { name: 'Fertig', exact: true }).waitFor();
});

Then(/^ist die Einführung mit «(ABGESCHLOSSEN|UEBERSPRUNGEN)» und der passenden Statusmeldung geschlossen$/, async function (art) {
  await this.seite.locator('app-start .einfuehrung').waitFor({ state: 'detached' });
  const text = art === 'ABGESCHLOSSEN' ? 'Einführung abgeschlossen.' : 'Einführung übersprungen.';
  await this.seite.locator('app-start [role="status"]').getByText(text, { exact: false }).waitFor();
  await this.seite.waitForFunction(() => document.activeElement?.matches('app-start .seitenkopf h1'));
  await this.seite.waitForFunction((wert) => {
    const daten = JSON.parse(localStorage.getItem('archiv.einfuehrung') ?? '{}');
    return daten.eintraege?.length === 1 && daten.eintraege[0].art === wert;
  }, art);
});

When('ich die Einführung über die Anleitung erneut öffne', async function () {
  await this.seite.getByRole('button', { name: 'Hilfe', exact: true }).click();
  await this.seite.getByRole('dialog', { name: 'Hilfe' }).getByRole('link', { name: 'Ganze Anleitung öffnen' }).click();
  await this.seite.locator('app-hilfe-seite').getByRole('link', { name: 'Einführung erneut zeigen' }).click();
  assert.equal(new URL(this.seite.url()).searchParams.has('einfuehrung'), true);
});

When('ich die Startseite vorübergehend verlasse und zurückkehre', async function () {
  await this.seite.getByRole('button', { name: 'Hilfe', exact: true }).click();
  await this.seite.getByRole('dialog', { name: 'Hilfe' }).getByRole('link', { name: 'Ganze Anleitung öffnen' }).click();
  await this.seite.locator('app-hilfe-seite').waitFor();
  await this.seite.goBack();
});

Then('ist noch kein Einführungsvermerk gespeichert', async function () {
  assert.equal(await this.seite.evaluate(() => localStorage.getItem('archiv.einfuehrung')), null);
});

When('ich die Seite neu lade und mich erneut anmelde', async function () {
  await this.seite.reload();
  await this.seite.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await this.seite.getByRole('button', { name: 'Abmelden', exact: true }).waitFor();
});

Then('bleibt die Startseite mit dem aktuellen Einführungsschritt geöffnet', async function () {
  assert.equal(new URL(this.seite.url()).pathname, '/');
  await this.seite.locator('app-start .einfuehrung').getByText('Schritt 2 von 5', { exact: true }).waitFor({ state: 'attached' });
});
