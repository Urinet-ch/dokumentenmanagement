// Schritte für den Chat gegen das echte Archiv (Spec 02, Aufgabe 7.4b): Das Sprachmodell ist der WireMock von
// KiWireMock; die Antworten registriert das Szenario über dessen Admin-Schnittstelle (Adresse `modell` in
// browser-backend.json). Alles synthetisch.
import { After, Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import {
  backend,
  direktHochladen,
  echteDokumente,
  echtesToken,
  inhalt,
  statusSetzen,
} from '../hilfen/echtes-backend.mjs';

const CHAT_PFAD = '/v1/chat/completions';
const MODELL = 'e2e-chat';
const SZENARIO = 'chat-browser';

const chunk = (delta, ende) =>
  `data: ${JSON.stringify({
    id: 'chatcmpl-e2e',
    object: 'chat.completion.chunk',
    created: 1773565200,
    model: MODELL,
    choices: [{ index: 0, delta, finish_reason: ende }],
  })}\n\n`;

const stuecke = (text, laenge) =>
  Array.from({ length: Math.ceil(text.length / laenge) }, (_, i) =>
    text.slice(i * laenge, (i + 1) * laenge),
  );

/** Gestreamte Modellantwort im Format von chat.completion.chunk wie KiWireMock.chatStrom (Text und/oder Aufrufe). */
function strom({ text, aufrufe = [] }) {
  let body = chunk({ role: 'assistant', content: '' }, null);
  for (const stueck of stuecke(text ?? '', 12)) {
    body += chunk({ content: stueck }, null);
  }
  aufrufe.forEach((aufruf, i) => {
    body += chunk(
      {
        tool_calls: [
          {
            index: i,
            id: `call_${i}`,
            type: 'function',
            function: { name: aufruf.name, arguments: '' },
          },
        ],
      },
      null,
    );
    for (const stueck of stuecke(aufruf.argumente, 9)) {
      body += chunk({ tool_calls: [{ index: i, function: { arguments: stueck } }] }, null);
    }
  });
  body += chunk({}, aufrufe.length ? 'tool_calls' : 'stop');
  const ein = 100;
  const aus = 20;
  body += `data: ${JSON.stringify({ id: 'chatcmpl-e2e', object: 'chat.completion.chunk', created: 1773565200, model: MODELL, choices: [], usage: { prompt_tokens: ein, completion_tokens: aus, total_tokens: ein + aus } })}\n\n`;
  return `${body}data: [DONE]\n\n`;
}

async function admin(methode, pfad, koerper) {
  const { modell } = backend();
  assert.ok(
    modell,
    'Kein Sprachmodell-WireMock in browser-backend.json: läuft BrowserBackendTest mit diesem Stand?',
  );
  const antwort = await fetch(`${modell}/__admin${pfad}`, {
    method: methode,
    headers: { 'content-type': 'application/json' },
    body: koerper ? JSON.stringify(koerper) : undefined,
  });
  assert.ok(antwort.ok, `WireMock ${methode} ${pfad}: ${antwort.status}`);
  return antwort.status === 204 ? null : antwort.json();
}

const abbildung = (antwort, zustandVorher, zustandNachher, dribble) => ({
  scenarioName: SZENARIO,
  requiredScenarioState: zustandVorher,
  ...(zustandNachher ? { newScenarioState: zustandNachher } : {}),
  priority: 5,
  request: {
    method: 'POST',
    urlPath: CHAT_PFAD,
    bodyPatterns: [{ matchesJsonPath: { expression: '$.stream', equalTo: 'true' } }],
  },
  response: {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
    body: strom(antwort),
    ...(dribble ? { chunkedDribbleDelay: dribble } : {}),
  },
});

After(async function () {
  for (const id of this.modellAbbildungen ?? []) {
    await admin('DELETE', `/mappings/${id}`);
  }
  this.modellAbbildungen = [];
});

Given(
  /^«(.+)» im Mandanten «(.+)» hat das Dokument «(.+)» mit dem Titel «(.+)» im Eingangskorb$/,
  async function (rolle, mandant, name, titel) {
    const [ergebnis] = await direktHochladen(rolle, mandant, [
      { name: `${name}.pdf`, mimeType: 'application/pdf', buffer: inhalt('x.pdf') },
    ]);
    assert.equal(ergebnis.status, 'NEU', JSON.stringify(ergebnis));
    await statusSetzen(mandant, ergebnis.id, 'EINGANGSKORB', titel);
    echteDokumente(this).set(name, ergebnis.id);
  },
);

Given(
  /^das simulierte Sprachmodell ruft «(.+)» für «(.+)» auf und antwortet danach «(.+)»$/,
  async function (werkzeug, name, text) {
    const id = echteDokumente(this).get(name);
    assert.ok(id, `kein Dokument «${name}» angelegt`);
    await admin('POST', '/scenarios/reset');
    this.modellAbbildungen = [];
    const aufruf = { name: werkzeug, argumente: JSON.stringify({ id }) };
    this.modellText = text;
    for (const [antwort, vorher, nachher, dribble] of [
      [{ aufrufe: [aufruf] }, 'Started', 'nach-werkzeug', undefined],
      // der Text kommt über zwei Sekunden verteilt, damit er in der PWA sichtbar fortlaufend wächst
      [{ text }, 'nach-werkzeug', undefined, { numberOfChunks: 20, totalDuration: 2000 }],
    ]) {
      const angelegt = await admin(
        'POST',
        '/mappings',
        abbildung(antwort, vorher, nachher, dribble),
      );
      this.modellAbbildungen.push(angelegt.id);
    }
  },
);

Given('eine synthetische Wissensseite ist freigegeben', async function () {
  const kennung = randomUUID();
  const pfad = `konzepte/chat-${kennung}`;
  const titel = `Wissensprobe ${kennung}`;
  const headers = {
    authorization: `Bearer ${await echtesToken('inhaber', 'urinet')}`,
    'content-type': 'application/json',
  };
  const entwurf = await fetch(`${backend().api}/api/v1/wissensentwuerfe`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      pfad,
      typ: 'Konzept',
      titel,
      beschreibung: 'Synthetische Wissensprobe.',
      tags: [],
      quellen: [],
      inhalt: '',
    }),
  });
  assert.equal(entwurf.status, 201, 'manueller Wissensentwurf');
  const { id } = await entwurf.json();
  const annahme = await fetch(
    `${backend().api}/api/v1/wissensentwuerfe/${id}/aenderungen/1/annehmen`,
    {
      method: 'POST',
      headers: { ...headers, 'if-match': '"0"' },
      body: '{}',
    },
  );
  assert.equal(annahme.status, 204, 'Freigabe der Wissensprobe');
  const antwort = await fetch(
    `${backend().api}/api/v1/wissensseiten?pfad=${encodeURIComponent(pfad)}`,
    { headers },
  );
  assert.equal(antwort.status, 200, 'freigegebene Wissensprobe');
  this.wissensprobe = { pfad, titel, id: (await antwort.json()).id };
});

async function wissensmodell(welt, id) {
  const { wissensprobe } = welt;
  assert.ok(wissensprobe);
  await admin('POST', '/scenarios/reset');
  welt.modellAbbildungen = [];
  welt.modellText = 'Die synthetische Wissensprobe ist verfügbar.';
  const aufrufe = [
    { name: 'wissen_seite_zeigen', argumente: JSON.stringify({ pfad: wissensprobe.pfad }) },
  ];
  if (id) aufrufe.push({ name: 'dokument_zeigen', argumente: JSON.stringify({ id }) });
  for (const [antwort, vorher, nachher] of [
    [{ aufrufe }, 'Started', 'nach-werkzeug'],
    [{ text: welt.modellText }, 'nach-werkzeug', undefined],
  ]) {
    const angelegt = await admin('POST', '/mappings', abbildung(antwort, vorher, nachher));
    welt.modellAbbildungen.push(angelegt.id);
  }
}

Given(
  /^das simulierte Sprachmodell nutzt die Wissensseite und das Dokument «(.+)»$/,
  async function (name) {
    const id = echteDokumente(this).get(name);
    assert.ok(id, `kein Dokument «${name}» angelegt`);
    await wissensmodell(this, id);
  },
);

Given('das simulierte Sprachmodell nutzt nur die Wissensseite', async function () {
  assert.ok(this.wissensprobe);
  await wissensmodell(this);
});

Then('zeigt der echte Chat unterscheidbare Quellen', async function () {
  const antwort = this.seite.locator('[data-test=antwort]').last();
  await antwort
    .getByRole('link', { name: `Wissensseite: ${this.wissensprobe.titel}` })
    .waitFor({ timeout: 8_000 });
  await antwort
    .getByRole('link', { name: 'Rechnung Testverein Chatdorf' })
    .waitFor({ timeout: 8_000 });
});

When('ich die echte Antwort als Analyse vorschlage', async function () {
  await this.seite
    .locator('[data-test=antwort]')
    .last()
    .getByRole('button', { name: 'Als Analyse vorschlagen', exact: true })
    .click({ timeout: 8_000 });
});

Then('zeigt der echte Chat den Status des Analysevorschlags', async function () {
  await this.seite
    .locator('[data-test=antwort]')
    .last()
    .getByRole('link', { name: 'Analyse vorgeschlagen. Status anzeigen' })
    .waitFor({ timeout: 8_000 });
});

When('ich die echte Wissensquelle im Chat öffne', async function () {
  this.wissensabrufAb = Date.now();
  await this.seite
    .locator('[data-test=antwort]')
    .last()
    .getByRole('link', { name: `Wissensseite: ${this.wissensprobe.titel}` })
    .click();
});

Then('ist die echte Wissensseite geöffnet und ihr Abruf protokolliert', async function () {
  await this.seite.getByRole('heading', { name: this.wissensprobe.titel }).waitFor();
  const headers = { authorization: `Bearer ${await echtesToken('inhaber', 'urinet')}` };
  for (const ende = Date.now() + 15_000; Date.now() < ende;) {
    const antwort = await fetch(
      `${backend().api}/api/v1/protokoll?aktion=WISSEN_ANZEIGEN&groesse=100`,
      { headers },
    );
    assert.equal(antwort.status, 200, 'Zugriffsprotokoll');
    const eintraege = (await antwort.json()).eintraege;
    if (
      eintraege.some(
        (e) =>
          e.dokumentId === this.wissensprobe.id && Date.parse(e.zeit) >= this.wissensabrufAb - 1000,
      )
    )
      return;
    await new Promise((weiter) => setTimeout(weiter, 300));
  }
  assert.fail('WISSEN_ANZEIGEN für den Seitenabruf fehlt');
});

Given('verschiedene Benutzer haben eigene echte Chat-Gespräche', async function () {
  await admin('POST', '/scenarios/reset');
  this.modellAbbildungen = [];
  const mapping = await admin('POST', '/mappings', {
    priority: 5,
    request: {
      method: 'POST',
      urlPath: CHAT_PFAD,
      bodyPatterns: [{ matchesJsonPath: { expression: '$.stream', equalTo: 'true' } }],
    },
    response: {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
      body: strom({ text: 'Synthetische Testantwort.' }),
    },
  });
  this.modellAbbildungen.push(mapping.id);
  const lauf = randomUUID();
  this.eigenesGespraech = `Eigenes Gespräch ${lauf}`;
  this.fremdesGespraech = `Gespräch eines anderen Benutzers ${lauf}`;

  for (const [rolle, titel] of [
    ['mitarbeiter', this.fremdesGespraech],
    ['inhaber', this.eigenesGespraech],
  ]) {
    const antwort = await fetch(`${backend().api}/api/v1/chat`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${await echtesToken(rolle, 'urinet')}`,
        'content-type': 'application/json',
        accept: 'text/event-stream',
      },
      body: JSON.stringify({ frage: titel }),
    });
    assert.equal(antwort.status, 200, `Gespräch für ${rolle}: ${antwort.status}`);
    assert.ok(
      (await antwort.text()).includes('fertig'),
      `Gespräch für ${rolle} wurde nicht beendet`,
    );
  }
});

When('ich den Chat über das Menü öffne', async function () {
  const menue = this.seite.getByRole('button', { name: 'Menü öffnen' });
  if (await menue.isVisible()) {
    await menue.click();
  }
  await this.seite
    .getByRole('navigation', { name: 'Hauptnavigation' })
    .getByRole('link', { name: 'Chat', exact: true })
    .click();
  await this.seite.getByRole('heading', { name: 'Chat', level: 1 }).waitFor();
  await this.seite.getByRole('textbox', { name: 'Frage', exact: true }).waitFor();
});

Then('sehe ich im Chat nur meine eigenen Gespräche', async function () {
  const zeigen = this.seite.getByRole('button', { name: 'Letzte Gespräche zeigen', exact: true });
  if (await zeigen.isVisible()) {
    await zeigen.click();
  }
  const gespraeche = this.seite.locator('[data-test=gespraech]');
  await gespraeche.filter({ hasText: this.eigenesGespraech }).waitFor();
  assert.equal(await gespraeche.filter({ hasText: this.fremdesGespraech }).count(), 0);
});

Then('sehe ich die Antwort fortlaufend, bis sie vollständig ist', async function () {
  const inhaltFeld = this.seite.locator('[data-test=antwort]').last().locator('.inhalt');
  const laengen = new Set();
  for (const ende = Date.now() + 30_000; Date.now() < ende;) {
    const text = (await inhaltFeld.innerText()).trim();
    if (text.length > 0) {
      laengen.add(text.length);
    }
    if (text === this.modellText) {
      break;
    }
    assert.ok(this.modellText.startsWith(text), `Antwort weicht ab: ${text}`);
    await new Promise((weiter) => setTimeout(weiter, 40));
  }
  assert.equal((await inhaltFeld.innerText()).trim(), this.modellText);
  assert.ok(laengen.size >= 3, `die Antwort kam nicht fortlaufend: ${[...laengen].join(', ')}`);
});

Then(/^bin ich auf der Seite des angelegten Dokuments «(.+)»$/, async function (name) {
  const id = echteDokumente(this).get(name);
  assert.ok(id, `kein Dokument «${name}» angelegt`);
  await this.seite.waitForURL((url) => url.pathname === `/dokument/${id}`);
  await this.seite.getByRole('heading', { level: 1 }).waitFor();
});

Then(
  /^enthält das Zugriffsprotokoll von «([^»]+)» ANZEIGEN für das Dokument «(.+)» mindestens einmal mit dem Kanal «(.+)» und mindestens einmal ohne Kanal$/,
  async function (mandant, name, kanal) {
    const id = echteDokumente(this).get(name);
    const kopf = { authorization: `Bearer ${await echtesToken('inhaber', mandant)}` };
    let tipps = [];
    // das GET der Dokumentansicht schreibt nach dem Laden der Seite; kurz warten
    for (const ende = Date.now() + 15_000; Date.now() < ende;) {
      const antwort = await fetch(`${backend().api}/api/v1/protokoll?aktion=ANZEIGEN&groesse=100`, {
        headers: kopf,
      });
      assert.equal(antwort.status, 200);
      tipps = (await antwort.json()).eintraege
        .filter((e) => e.dokumentId === id && Date.parse(e.zeit) >= this.szenarioStart)
        .map((e) => (e.details ? JSON.parse(e.details).kanal : undefined));
      if (tipps.includes(kanal) && tipps.includes(undefined)) {
        return;
      }
      await new Promise((weiter) => setTimeout(weiter, 300));
    }
    assert.fail(
      `ANZEIGEN für ${id} mit Kanal ${kanal} und ohne Kanal fehlt: ${JSON.stringify(tipps)}`,
    );
  },
);
