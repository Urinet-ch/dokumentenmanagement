// Vorschau und Download der Dokumentansicht (Req 14.6) gegen das echte Archiv im Testmodus mit MinIO. Der Browser lädt
// die Datei von der vorsignierten Adresse der Ablage (anderer Host als die API); geprüft werden die Antwort-Header und
// ob der Browser einen Download auslöst. Adressen (Zugangsschlüssel auf Zeit) werden nie geloggt oder verglichen.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { direktHochladen, echteDokumente, inhalt, statusSetzen } from '../hilfen/echtes-backend.mjs';

Given(
  /^«(.+)» im Mandanten «(.+)» hat das PDF «(.+)» im Eingangskorb mit dem Titel «(.+)»$/,
  async function (rolle, mandant, name, titel) {
    const [ergebnis] = await direktHochladen(rolle, mandant, [
      { name, mimeType: 'application/pdf', buffer: inhalt(name) },
    ]);
    assert.equal(ergebnis.status, 'NEU', JSON.stringify(ergebnis));
    await statusSetzen(mandant, ergebnis.id, 'EINGANGSKORB', titel);
    echteDokumente(this).set(name, ergebnis.id);
  },
);

When(/^ich die Dokumentansicht von «(.+)» öffne$/, async function (name) {
  const id = echteDokumente(this).get(name);
  assert.ok(id, `kein Dokument «${name}» angelegt`);
  // Antworten der Ablage (nicht der API): nur Typ und Disposition merken, nie die Adresse
  this.ablageAntworten = [];
  this.downloads = [];
  // Die Keycloak-Attrappe blockiert fremde Hosts; die Ablage (MinIO) ist für Vorschau und Download ausdrücklich erlaubt,
  // aber nur mit vorsignierter Adresse. Spätere Routen gehen vor.
  await this.kontext.route(
    (url) => url.searchParams.has('X-Amz-Signature'),
    (route) => route.continue(),
  );
  const anhaengen = (seite) => {
    seite.on('download', (download) => this.downloads.push(download));
    seite.on('response', (antwort) => {
      const url = antwort.url();
      if (url.includes('/api/v1/') || !url.includes('X-Amz-Signature')) {
        return;
      }
      const kopf = antwort.headers();
      this.ablageAntworten.push({
        status: antwort.status(),
        typ: kopf['content-type'],
        disposition: kopf['content-disposition'],
      });
    });
  };
  anhaengen(this.seite);
  // der Download öffnet einen neuen Tab (window.open), dort kommt das Download-Ereignis an
  this.kontext.on('page', anhaengen);
  // in der App navigieren: ein neues goto verlöre das Token im Speicher der Seite (Anmeldung nur im Arbeitsspeicher)
  await this.seite.evaluate((pfad) => {
    history.pushState({}, '', pfad);
    dispatchEvent(new PopStateEvent('popstate', { state: {} }));
  }, `/dokument/${id}`);
  await this.seite.getByRole('heading', { level: 1 }).waitFor();
});

Then('sehe ich die Vorschau des PDF', async function () {
  const vorschau = this.seite.locator('object[data-test=vorschau]');
  await vorschau.waitFor();
  assert.equal(await vorschau.getAttribute('type'), 'application/pdf');
});

async function warteAufAblage(welt) {
  for (const ende = Date.now() + 15_000; Date.now() < ende && welt.ablageAntworten.length === 0; ) {
    await new Promise((weiter) => setTimeout(weiter, 100));
  }
  assert.ok(welt.ablageAntworten.length > 0, 'der Browser hat die Ablage nicht abgefragt');
}

Then(
  /^hat der Browser die Vorschau mit Content-Type «(.+)» und Content-Disposition «(.+)» erhalten$/,
  async function (typ, disposition) {
    await warteAufAblage(this);
    assert.deepEqual(this.ablageAntworten.map((a) => [a.status, a.typ, a.disposition]), [[200, typ, disposition]]);
  },
);

Then('hat der Browser keine Datei heruntergeladen', async function () {
  // dem Browser Zeit lassen, einen Download auszulösen, falls die Antwort ein attachment wäre
  await new Promise((weiter) => setTimeout(weiter, 1500));
  assert.equal(this.downloads.length, 0, 'der Browser hat die Datei heruntergeladen statt angezeigt');
});

Then('hat der Browser eine Datei heruntergeladen', async function () {
  for (const ende = Date.now() + 15_000; Date.now() < ende && this.downloads.length === 0; ) {
    await new Promise((weiter) => setTimeout(weiter, 100));
  }
  assert.equal(this.downloads.length, 1, 'der Klick hat keinen Download ausgelöst');
});

// Die Antwort einer Navigation, die zum Download wird, meldet Playwright nicht als Antwort; geprüft wird darum das
// Ergebnis: Die Vorschau kam inline, die Datei heisst nach dem Archiv («<Dokument-ID>-<Name aus dem Schlüssel>»),
// nicht nach dem Namen der hochgeladenen Datei (Req 3.12).
Then(
  /^hat der Browser nur die Vorschau mit Content-Disposition «(.+)» erhalten und die Datei als «(.+)» mit der ID von «(.+)» heruntergeladen$/,
  async function (vorschau, name, dokument) {
    assert.deepEqual(this.ablageAntworten.map((a) => a.disposition?.split(';')[0]), [vorschau]);
    assert.equal(this.downloads[0].suggestedFilename(), `${echteDokumente(this).get(dokument)}-${name}`);
  },
);
