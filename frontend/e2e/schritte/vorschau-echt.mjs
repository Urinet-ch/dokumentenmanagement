// Vorschau und Download der Dokumentansicht (Req 14.6) gegen das echte Archiv im Testmodus mit MinIO. Die PWA bindet
// die vorsignierte Ablage-Adresse nicht als Ressource ein; der Link wird nur auf Klick im neuen Tab geöffnet.
// Adressen (Zugangsschlüssel auf Zeit) werden nie geloggt oder verglichen.
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

Then('sehe ich einen sicheren Link zur PDF-Vorschau', async function () {
  const link = this.seite.locator('a[data-test=pdf-laden]');
  await link.waitFor();
  const adresse = await link.getAttribute('href');
  assert.ok(adresse && ['http:', 'https:'].includes(new URL(adresse).protocol));
  assert.equal(await link.getAttribute('target'), '_blank');
  assert.equal(await link.getAttribute('rel'), 'noopener noreferrer');
});

// Der Link öffnet die Adresse im neuen Tab; die Ablage muss dort inline darstellen (Req 14.6). Die Adresse wird nur
// abgerufen, nie ausgegeben.
Then(
  /^liefert die Ablage beim Öffnen des Links Content-Type «(.+)» und Content-Disposition «(.+)»$/,
  async function (typ, disposition) {
    const adresse = await this.seite.locator('a[data-test=pdf-laden]').getAttribute('href');
    const antwort = await this.seite.request.get(adresse);
    assert.equal(antwort.status(), 200);
    assert.equal(antwort.headers()['content-type'], typ);
    assert.equal(antwort.headers()['content-disposition']?.split(';')[0], disposition);
  },
);

Then('hat die PWA das PDF nicht automatisch abgerufen', async function () {
  assert.equal(this.ablageAntworten.length, 0, 'die PWA hat die PDF-Adresse automatisch abgerufen');
});

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

// Die vorsignierte Download-Navigation meldet Playwright nicht als Antwort; geprüft werden der fehlende automatische
// Vorschau-Abruf und der Dateiname aus dem Archiv («<Dokument-ID>-<Name aus dem Schlüssel>», Req 3.12).
Then(
  /^hat die PWA das PDF nicht automatisch abgerufen und die Datei als «(.+)» mit der ID von «(.+)» heruntergeladen$/,
  async function (name, dokument) {
    assert.equal(this.ablageAntworten.length, 0, 'die PWA hat die PDF-Adresse vor dem Klick abgerufen');
    assert.equal(this.downloads[0].suggestedFilename(), `${echteDokumente(this).get(dokument)}-${name}`);
  },
);
