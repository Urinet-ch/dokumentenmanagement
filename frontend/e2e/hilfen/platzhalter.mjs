// Prüft den Platzhalter für Dokumente ohne Titel (Aufgabe 4.10, Req 8.11, 14.9, 14.10): Der Dateiname steht sichtbar
// hinter «Ohne Titel ·» in <bdi>, als Text (nie als Markup), mit vollem Namen im title, und der Screenreader hört
// «Ohne Titel, Dateiname …» statt eines Titels.
import assert from 'node:assert/strict';

export async function platzhalterPruefen(bereich, name) {
  const platzhalter = bereich.locator('[data-test=titel-platzhalter]');
  await platzhalter.waitFor();
  assert.equal(await platzhalter.getAttribute('title'), name);
  const sichtbar = platzhalter.locator('[aria-hidden=true]');
  assert.equal((await sichtbar.textContent()).trim(), `Ohne Titel · ${name}`);
  assert.equal(await sichtbar.locator('bdi').textContent(), name);
  assert.equal(await sichtbar.locator('bdi *').count(), 0, 'der Dateiname erscheint als Markup');
  assert.equal(await sichtbar.locator('bdi').evaluate((e) => getComputedStyle(e).fontStyle), 'italic');
  assert.equal(
    (await platzhalter.locator('.nur-screenreader').textContent()).trim(),
    `Ohne Titel, Dateiname ${name}`,
  );
}
