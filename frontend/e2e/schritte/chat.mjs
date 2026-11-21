// Seite «Chat» (Spec 02, Aufgabe 7.4) gegen die Attrappe der Chat-Schnittstelle (e2e/hilfen/chat-attrappe.mjs). Die
// Szenarien belegen nur die Oberfläche; Rechte, Sichtbarkeit, Protokoll und Rate-Limit belegt das Backend (7.1–7.3).
import { After, Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { SEEDORF, chatAttrappe, chatAttrappeBeenden } from '../hilfen/chat-attrappe.mjs';

function attrappe(welt) {
  assert.ok(welt.chat, 'die Chat-Schnittstelle ist keine Attrappe');
  return welt.chat;
}

const antworten = (seite) => seite.locator('[data-test=antwort]');
const letzteAntwort = (seite) => antworten(seite).last();
const hinweis = (seite) => seite.locator('[data-test=hinweis]');

/** Auf schmalen Bildschirmen liegt die Liste hinter «Letzte Gespräche zeigen». */
async function listeOeffnen(seite) {
  const zeigen = seite.getByRole('button', { name: 'Letzte Gespräche zeigen', exact: true });
  if (await zeigen.isVisible()) {
    await zeigen.click();
  }
}

After(async function () {
  await chatAttrappeBeenden(this);
});

Given('die Chat-Schnittstelle ist eine Attrappe', async function () {
  await chatAttrappe(this);
});

Given('der Chat hat keine Gespräche', function () {
  const chat = attrappe(this);
  chat.gespraeche = [];
  chat.verlauf.clear();
});

Given('ein Chatgespräch enthält eine Wissensquelle und einen Analysevorschlag', async function () {
  const chat = attrappe(this);
  const gespraech = chat.gespraeche[0];
  gespraech.titel = 'Wissen zur Probe';
  chat.verlauf.set(gespraech.id, [
    {
      id: '101', rolle: 'ASSISTENT', inhalt: 'Eine belegte Antwort.', status: 'OK', analyseMoeglich: true,
      zeit: '2026-11-02T09:15:00Z',
      quellen: [{
        wissen: { titel: 'Synthetische Wissensseite', link: '/wissen/konzepte/probe',
          stand: '2026-01-01T10:00:00Z' },
        dokumente: [{ titel: SEEDORF.titel, link: `/dokument/${SEEDORF.id}`, seiten: '2' }],
      }],
    },
    {
      id: '102', rolle: 'ASSISTENT', inhalt: 'Eine Kennzahl.', status: 'OK', analyseMoeglich: false,
      zeit: '2026-11-02T09:16:00Z', quellen: [],
    },
  ]);
  const id = '11111111-1111-4111-8111-111111111111';
  await this.seite.route((url) => url.pathname.startsWith('/api/v1/wissensentwuerfe'), async (route) => {
    const anfrage = route.request();
    if (anfrage.method() === 'POST' && new URL(anfrage.url()).pathname.endsWith('/analyse')) {
      assert.deepEqual(anfrage.postDataJSON(), { nachrichtId: '101' });
      await route.fulfill({ status: 201, json: { id } });
    } else {
      await route.fulfill({ status: 200, json: {
        id, status: 'ERLEDIGT', entscheidungen: [{ nr: 1, status: 'ABGELEHNT', grund: 'Synthetischer Grund' }],
      } });
    }
  });
});

Then('sind Wissensseite und Quelldokument im Chat unterscheidbar', async function () {
  const antwort = antworten(this.seite).first();
  await antwort.getByRole('link', { name: 'Wissensseite: Synthetische Wissensseite' }).waitFor();
  await antwort.getByRole('link', { name: `${SEEDORF.titel}, Seite 2` }).waitFor();
});

When('ich im Chat die Wissensquelle öffne', async function () {
  await antworten(this.seite).first().getByRole('link', { name: 'Wissensseite: Synthetische Wissensseite' }).click();
});

Then('sehe ich die Wissensseite «Synthetische Wissensseite»', async function () {
  await this.seite.getByRole('heading', { name: 'Synthetische Wissensseite' }).waitFor();
});

Then('gibt es nur für die zulässige Antwort die Aktion «Als Analyse vorschlagen»', async function () {
  const knoepfe = this.seite.getByRole('button', { name: 'Als Analyse vorschlagen', exact: true });
  await knoepfe.first().waitFor();
  assert.equal(await knoepfe.count(), 1);
  assert.equal(await antworten(this.seite).last().getByRole('button', { name: 'Als Analyse vorschlagen' }).count(), 0);
});

When('ich die zulässige Antwort als Analyse vorschlage', async function () {
  await antworten(this.seite).first().getByRole('button', { name: 'Als Analyse vorschlagen' }).click();
});

When('ich den Status des Analysevorschlags öffne', async function () {
  await this.seite.getByRole('link', { name: 'Analyse vorgeschlagen. Status anzeigen' }).click();
});

Then('sehe ich den Entscheid und Grund meines Analysevorschlags', async function () {
  await this.seite.getByText('ABGELEHNT', { exact: false }).waitFor();
  await this.seite.getByText('Synthetischer Grund', { exact: false }).waitFor();
});

When(/^ich die Frage «(.+)» sende$/, async function (frage) {
  const feld = this.seite.getByRole('textbox', { name: 'Frage', exact: true });
  await feld.fill(frage);
  await this.seite.locator('form.eingabe button[type="submit"]:enabled').waitFor();
  await feld.press('Enter');
});

When(/^ich «(.+)» in das Eingabefeld schreibe$/, async function (text) {
  await this.seite.getByRole('textbox', { name: 'Frage', exact: true }).fill(text);
});

When('das Archiv weiterantwortet', function () {
  attrappe(this).weiter();
});

When(/^ich das Gespräch «(.+)» öffne$/, async function (titel) {
  await listeOeffnen(this.seite);
  await this.seite.locator('[data-test=gespraech]').filter({ hasText: titel }).click();
});

When(/^ich auf die Quelle «(.+)» tippe$/, async function (titel) {
  await letzteAntwort(this.seite).locator('a[data-test=quelle]').filter({ hasText: titel }).click();
});

Then(/^sehe ich unter den letzten Gesprächen «(.+)» vom «(.+)»$/, async function (titel, datum) {
  await listeOeffnen(this.seite);
  const eintrag = this.seite.locator('[data-test=gespraech]').filter({ hasText: titel });
  await eintrag.waitFor();
  assert.equal((await eintrag.locator('.datum').innerText()).trim(), datum);
});

Then(/^sehe ich unter den letzten Gesprächen zuoberst «(.+)»$/, async function (titel) {
  await listeOeffnen(this.seite);
  await this.seite.locator('[data-test=gespraech]').first().filter({ hasText: titel }).waitFor();
});

Then('sehe ich keine letzten Gespräche', async function () {
  await listeOeffnen(this.seite);
  await this.seite.getByText('Noch keine Gespräche.', { exact: true }).waitFor();
});

Then('sehe ich die leere Chat-Aufforderung', async function () {
  await this.seite
    .getByText('Stellen Sie eine Frage, zum Beispiel: «Welche Rechnungen sind noch offen?»', {
      exact: true,
    })
    .waitFor();
});

Then(/^sehe ich den Hinweis «(.+)»$/, async function (text) {
  await hinweis(this.seite).filter({ hasText: text }).waitFor();
  assert.equal(await letzteAntwort(this.seite).locator('.inhalt').innerText(), '');
});

Then(/^sehe ich den Anfang der Antwort «(.+)», während sie noch läuft$/, async function (text) {
  const inhalt = letzteAntwort(this.seite).locator('.inhalt');
  await inhalt.filter({ hasText: text }).waitFor();
  assert.equal(await inhalt.innerText(), text, 'die Antwort ist nicht nur ihr Anfang');
  await this.seite.getByRole('button', { name: 'Abbrechen', exact: true }).waitFor();
});

Then('ist das Eingabefeld leer', async function () {
  assert.equal(
    await this.seite.getByRole('textbox', { name: 'Frage', exact: true }).inputValue(),
    '',
  );
});

Then('ist der Hinweis verschwunden', async function () {
  assert.equal((await hinweis(this.seite).innerText()).trim(), '');
});

Then('sehe ich die Antwort:', async function (text) {
  const inhalt = letzteAntwort(this.seite).locator('.inhalt');
  await this.seite.waitForFunction(
    ([el, soll]) => el.textContent === soll,
    [await inhalt.elementHandle(), text],
  );
  // reiner Text: keine Elemente in der Antwort, Zeilenumbrüche bleiben sichtbar
  assert.equal(await inhalt.locator('*').count(), 0);
  await this.seite
    .getByRole('button', { name: 'Abbrechen', exact: true })
    .waitFor({ state: 'detached' });
});

Then(/^sehe ich die Antwort «(.+)»$/, async function (text) {
  await letzteAntwort(this.seite).locator('.inhalt').filter({ hasText: text }).waitFor();
});

Then(/^sehe ich die Quellen «(.+)»$/, async function (liste) {
  const quellen = letzteAntwort(this.seite).locator('[data-test=quelle]');
  await quellen.first().waitFor();
  assert.deepEqual(await quellen.allInnerTexts(), liste.split(' | '));
});

Then(/^ist die Quelle «(.+)» (ein Link|nicht klickbar)$/, async function (titel, art) {
  const quelle = letzteAntwort(this.seite).locator('[data-test=quelle]').filter({ hasText: titel });
  await quelle.waitFor();
  const tag = await quelle.evaluate((el) => el.tagName);
  assert.equal(tag, art === 'ein Link' ? 'A' : 'SPAN', `${titel}: ${tag}`);
});

Then(/^sehe ich (\d+) Quellen$/, async function (anzahl) {
  const quellen = letzteAntwort(this.seite).locator('[data-test=quelle]');
  await quellen.nth(Number(anzahl) - 1).waitFor();
  assert.equal(await quellen.count(), Number(anzahl));
});

Then(
  /^hat die PWA die Frage (ohne Gespräch|im selben Gespräch|im Gespräch «.+») gestellt$/,
  function (art) {
    const chat = attrappe(this);
    const titel = /«(.+)»/.exec(art)?.[1];
    const letzte = chat.fragen.at(-1);
    assert.equal(letzte.accept, 'text/event-stream');
    if (art === 'ohne Gespräch') {
      assert.equal(letzte.gespraechId, undefined);
    } else if (titel) {
      assert.equal(letzte.gespraechId, chat.gespraeche.find((g) => g.titel === titel)?.id);
    } else {
      assert.ok(letzte.gespraechId, 'keine gespraechId');
      assert.equal(
        letzte.gespraechId,
        chat.gespraeche.find((g) => g.titel === chat.fragen.at(-2)?.frage)?.id,
      );
    }
  },
);

Then('sehe ich die Meldung, dass es in 20 Minuten weitergeht', async function () {
  const uhr = (ms) =>
    new Intl.DateTimeFormat('de-CH', {
      timeZone: 'Europe/Zurich',
      hour: '2-digit',
      minute: '2-digit',
    }).format(Math.ceil(ms / 60_000) * 60_000);
  const vorher = uhr(Date.now() + 1200 * 1000);
  const meldung = this.seite
    .getByRole('alert')
    .filter({ hasText: 'Du hast in dieser Stunde 100 Fragen gestellt.' });
  await meldung.waitFor();
  const nachher = uhr(Date.now() + 1200 * 1000);
  const text = await meldung.innerText();
  assert.ok(
    [vorher, nachher].some(
      (zeit) => text === `Du hast in dieser Stunde 100 Fragen gestellt. Ab ${zeit} geht es weiter.`,
    ),
    text,
  );
});

Then('sehe ich keine Meldung über Minuten', async function () {
  const meldungen = await this.seite.getByRole('alert').allInnerTexts();
  assert.ok(meldungen.length > 0, 'keine Meldung sichtbar');
  assert.deepEqual(
    meldungen.filter((t) => /Minuten|Stunde|geht es weiter/.test(t)),
    [],
  );
});

Then(/^steht «(.+)» wieder im Eingabefeld$/, async function (frage) {
  assert.equal(
    await this.seite.getByRole('textbox', { name: 'Frage', exact: true }).inputValue(),
    frage,
  );
});

Then('hat die PWA die Anfrage abgebrochen', async function () {
  const chat = attrappe(this);
  for (let i = 0; i < 50 && chat.abgebrochen === 0; i++) {
    await this.seite.waitForTimeout(100);
  }
  assert.equal(chat.abgebrochen, 1);
  await this.seite
    .getByRole('button', { name: 'Abbrechen', exact: true })
    .waitFor({ state: 'detached' });
  await letzteAntwort(this.seite).getByText('Abgebrochen.', { exact: true }).waitFor();
});

Then(/^steht unter dem Gespräch «(.+)»$/, async function (text) {
  await this.seite.getByText(text, { exact: true }).waitFor();
});

Then(/^bin ich auf der Seite des Dokuments «(.+)»$/, async function (titel) {
  assert.equal(titel, SEEDORF.titel);
  await this.seite.waitForURL((url) => url.pathname === `/dokument/${SEEDORF.id}`);
  await this.seite.getByRole('heading', { name: titel, level: 1 }).waitFor();
});
