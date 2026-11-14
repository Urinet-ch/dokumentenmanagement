// Vorbereitung im Archiv im Testmodus (Aufgabe 4.10): Die PWA spricht über weitergeleitete Anfragen mit dem echten
// Archiv (hilfen/echtes-backend.mjs); Dokumente kommen direkt über die API, den Status setzt die Testhilfe von
// BrowserBackendTest (im Testmodus läuft kein Worker). Alles synthetisch, keine echten Belege.
import { After, Given, Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import {
  backend,
  direktHochladen,
  direktLesen,
  echteDokumente,
  echtesToken,
  inhalt,
  statusSetzen,
  weiterleiten,
} from '../hilfen/echtes-backend.mjs';

const OHNE_NAMEN = 'ohne Namen';

Given('die PWA spricht mit dem Archiv im Testmodus', async function () {
  await weiterleiten(this);
});

// Eine gescheiterte Weiterleitung bricht die Anfrage nur ab (hilfen/echtes-backend.mjs); ihre Ursache soll im Ergebnis
// stehen statt eines blossen Timeouts eines späteren Schritts.
After(function () {
  if (this.weiterleitung?.fehler) {
    throw this.weiterleitung.fehler;
  }
});

Given(/^«(.+)» im Mandanten «(.+)» hat eine Datei ohne Namen hochgeladen$/, async function (rolle, mandant) {
  const datei = { name: null, mimeType: 'application/pdf', buffer: inhalt('ohne.pdf') };
  const [ergebnis] = await direktHochladen(rolle, mandant, [datei]);
  assert.equal(ergebnis.status, 'NEU', JSON.stringify(ergebnis));
  assert.equal((await direktLesen(rolle, mandant, ergebnis.id)).dateiname ?? null, null);
  echteDokumente(this).set(OHNE_NAMEN, ergebnis.id);
});

Given(
  /^das Dokument «(.+)» liegt im Mandanten «(.+)» ohne Titel und Vorschlag im Status «(EINGANGSKORB|FEHLER)»$/,
  async function (name, mandant, status) {
    const id = echteDokumente(this).get(name);
    assert.ok(id, `kein Dokument «${name}» angelegt`);
    await statusSetzen(mandant, id, status);
  },
);

// Alle Arten der Testmandanten verlangen den Titel; für die Meldung nach einem erfolgreichen Bestätigen ohne Titel
// legt das Szenario über die API eine synthetische Art ohne Pflichtfelder an (vorhanden aus einem früheren Szenario: 409).
Given(/^im Mandanten «(.+)» gibt es die Art «(.+)» ohne Pflichtfelder$/, async function (mandant, name) {
  const antwort = await fetch(`${backend().api}/api/v1/dokumentarten`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${await echtesToken('inhaber', mandant)}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      code: name.toUpperCase().replace(/[^A-Z0-9]+/g, '_'),
      name,
      gruppen: [],
      pflichtfelder: [],
      zusatzSchema: { type: 'object', properties: {}, additionalProperties: false },
      vertraulichkeit: 'NORMAL',
      gesetzlichePflicht: false,
      aktiv: true,
    }),
  });
  assert.ok([201, 409].includes(antwort.status), `Art anlegen: ${antwort.status} ${await antwort.text()}`);
});

Then(/^steht «(.+)» im Archiv im Status «(.+)» ohne Titel$/, async function (name, status) {
  const {
    rollen: [rolle],
    mandant,
  } = this.keycloak.benutzer;
  const dokument = await direktLesen(rolle, mandant, echteDokumente(this).get(name));
  assert.equal(dokument.status, status);
  assert.equal(dokument.titel ?? null, null, dokument.titel);
  assert.equal(dokument.dateiname, name);
});
