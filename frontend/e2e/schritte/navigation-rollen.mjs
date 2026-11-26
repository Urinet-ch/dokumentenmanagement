import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { basisUrl } from '../hilfen/keycloak-attrappe.mjs';
import { navigationAttrappe } from '../hilfen/navigation-attrappe.mjs';

Given('ich zeichne Browserfehler für die Navigation auf', function () {
  this.navigationFehler = [];
  this.seite.setViewportSize({ width: 1280, height: 900 });
  this.seite.on('pageerror', (fehler) =>
    this.navigationFehler.push(`pageerror: ${fehler.message}`),
  );
  this.seite.on('console', (meldung) => {
    if (meldung.type() === 'error') {
      this.navigationFehler.push(`console.error: ${meldung.text()}`);
    }
  });
  this.seite.on('response', (antwort) => {
    const url = new URL(antwort.url());
    if (url.origin === new URL(basisUrl).origin && [403, 404].includes(antwort.status())) {
      this.navigationFehler.push(`HTTP ${antwort.status()}: ${url.pathname}`);
    }
  });
});

When('ich alle sichtbaren Menüpunkte öffne', async function () {
  await navigationAttrappe(this);
  const nav = this.seite.getByRole('navigation', { name: 'Hauptnavigation' });
  const punkte = await nav.getByRole('link').evaluateAll((links) =>
    links.map((link) => ({
      name: link.querySelector('.punkt')?.firstChild?.textContent?.trim() ?? link.innerText.trim(),
      pfad: new URL(link.href).pathname,
    })),
  );
  assert.ok(punkte.length > 0, 'Hauptnavigation enthält keine Menüpunkte');

  for (const { name, pfad } of punkte) {
    const link = nav.locator(`a[href="${pfad}"]`);
    assert.equal(await link.count(), 1, `Menüpunkt «${name}» fehlt in der Hauptnavigation`);
    await link.click();
    await this.seite.waitForURL((url) => url.pathname === pfad);
    const ueberschrift = this.seite.getByRole('heading', { level: 1 }).first();
    await ueberschrift.waitFor({ state: 'visible' });
    const text = (await ueberschrift.innerText()).trim();
    assert.ok(text, `Menüpunkt «${name}» hat keine Überschrift`);
    assert.doesNotMatch(
      text,
      /nicht gefunden|403|zugriff verweigert/i,
      `Fehlerseite bei «${name}»`,
    );
    assert.notEqual(pfad, '/nicht-gefunden', `Menüpunkt «${name}» führt zur Fehlerseite`);
  }
});

Then('ist jede Seite korrekt geladen und ohne Browserfehler', function () {
  assert.deepEqual(this.navigationFehler, [], this.navigationFehler.join('\n'));
});
