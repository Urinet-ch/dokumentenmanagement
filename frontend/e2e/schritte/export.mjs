// Seite «Export» (Spec 02, Aufgabe 6.5) gegen eine Attrappe der Export-Schnittstelle: Im Testmodus läuft kein Worker,
// der Export würde nie fertig. Die Attrappe antwortet wie ExportResource (OFFEN, nach einer weiteren Abfrage der Liste
// FERTIG, Adresse nur bei GET /exporte/{id}) und hält fest, was die PWA sendet. Alle Daten sind synthetisch.
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const PFAD = '/api/v1/exporte';
const ZIP = '/attrappe/jahresexport.zip';

function attrappe(welt) {
  assert.ok(welt.exporte, 'die Export-Schnittstelle ist keine Attrappe');
  return welt.exporte;
}

function antwort(route, status, inhalt) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(inhalt) });
}

async function bedienen(welt, route) {
  const exp = attrappe(welt);
  const anfrage = route.request();
  const url = new URL(anfrage.url());
  exp.anfragen.push(`${anfrage.method()} ${url.pathname}`);
  if (url.pathname === ZIP) {
    return route.fulfill({
      status: 200,
      contentType: 'application/zip',
      headers: { 'Content-Disposition': 'attachment; filename="jahresexport-urinet-2025.zip"' },
      body: Buffer.from([0x50, 0x4b, 0x05, 0x06, ...new Array(18).fill(0)]),
    });
  }
  if (!(await anfrage.headerValue('authorization'))?.startsWith('Bearer ')) {
    return antwort(route, 401, { fehlercode: 'NICHT_ANGEMELDET' });
  }
  if (url.pathname === PFAD && anfrage.method() === 'POST') {
    const body = anfrage.postDataJSON();
    exp.anforderungen.push(body);
    const jetzt = new Date().toISOString();
    exp.liste.unshift({ id: randomUUID(), geschaeftsjahr: body.geschaeftsjahr, status: 'OFFEN', erstellt: jetzt });
    exp.abfragenSeitAnforderung = 0;
    return antwort(route, 202, exp.liste[0]);
  }
  if (url.pathname === PFAD) {
    // die zweite Abfrage nach der Anforderung findet den Export fertig, wie nach dem Auftrag EXPORT_JAHR im Worker
    if (exp.abfragenSeitAnforderung++ >= 1) {
      for (const e of exp.liste.filter((x) => x.status === 'OFFEN')) {
        const fertig = new Date();
        Object.assign(e, {
          status: 'FERTIG',
          fertig: fertig.toISOString(),
          gueltigBis: new Date(fertig.getTime() + 24 * 3600 * 1000).toISOString(),
          anzahl: 42,
          groesse: 3 * 1024 * 1024,
          sha256: 'c'.repeat(64),
        });
      }
    }
    return antwort(route, 200, exp.liste);
  }
  const id = url.pathname.slice(PFAD.length + 1);
  const eintrag = exp.liste.find((e) => e.id === id);
  if (!eintrag) {
    return antwort(route, 404, { fehlercode: 'NICHT_GEFUNDEN' });
  }
  return antwort(route, 200, { ...eintrag, url: eintrag.status === 'FERTIG' ? url.origin + ZIP : null });
}

Given('die Export-Schnittstelle ist eine Attrappe', async function () {
  this.exporte = { liste: [], anfragen: [], anforderungen: [], abfragenSeitAnforderung: 0, download: null };
  this.seite.on('download', (download) => {
    this.exporte.download = download;
  });
  await this.seite.route(
    (url) => url.pathname === PFAD || url.pathname.startsWith(`${PFAD}/`) || url.pathname === ZIP,
    (route) =>
      bedienen(this, route).catch((fehler) => {
        this.exporte.fehler = fehler;
        return route.abort('failed');
      }),
  );
});

When(/^ich das Geschäftsjahr «(\d+)» wähle und auf «(.+)» klicke$/, async function (jahr, knopf) {
  await this.seite.locator('[data-test=jahr]').fill(String(jahr));
  await this.seite.getByRole('button', { name: knopf, exact: true }).click();
});

Then(/^hat das Archiv die Anforderung für das Geschäftsjahr (\d+) erhalten$/, async function (jahr) {
  await this.seite.getByRole('status').filter({ hasText: 'angefordert' }).waitFor();
  assert.deepEqual(attrappe(this).anforderungen, [{ geschaeftsjahr: Number(jahr) }]);
});

Then(/^sehe ich den Export (\d+) mit Status «(.+)»$/, async function (jahr, status) {
  await this.seite
    .locator('[data-test=export]')
    .filter({ hasText: `Geschäftsjahr ${jahr}` })
    .locator('.marke', { hasText: status })
    .waitFor({ timeout: 15_000 });
});

Then(/^lädt der Browser «(.+)» herunter$/, async function (name) {
  const exp = attrappe(this);
  for (let i = 0; i < 100 && !exp.download; i++) {
    await this.seite.waitForTimeout(100);
  }
  assert.ok(exp.download, 'kein Download');
  assert.equal(exp.download.suggestedFilename(), name);
});

Then('hat die PWA die Export-Schnittstelle nicht aufgerufen', async function () {
  await this.seite.waitForLoadState('networkidle');
  assert.deepEqual(attrappe(this).anfragen, []);
});
