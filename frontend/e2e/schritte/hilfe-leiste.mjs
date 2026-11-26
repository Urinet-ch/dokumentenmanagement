import { Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { verzeichnisOeffnen } from '../hilfen/hilfe-verzeichnis.mjs';

const dialog = (seite) => seite.getByRole('dialog', { name: 'Hilfe', exact: true });

When('ich die Hilfe-Leiste öffne', async function () {
  await this.seite.getByRole('button', { name: 'Hilfe', exact: true }).click();
  await dialog(this.seite).waitFor();
  await this.seite.waitForFunction(() => document.activeElement?.id === 'hilfe-leiste-titel');
});

When(/^ich die Hilfe-Leiste über «(.+)» schliesse$/, async function (name) {
  await dialog(this.seite).getByRole('button', { name, exact: true }).click();
  await dialog(this.seite).waitFor({ state: 'detached' });
});

When('ich in der Hilfe-Leiste weitertabbe', async function () {
  for (let i = 0; i < 20; i++) {
    await this.seite.keyboard.press('Tab');
    assert.equal(
      await dialog(this.seite).evaluate((element) => element.contains(document.activeElement)),
      true,
      'Fokus hat die Hilfe-Leiste verlassen',
    );
  }
});

When('ich die Hilfe-Leiste mit Escape schliesse', async function () {
  await this.seite.keyboard.press('Escape');
  await dialog(this.seite).waitFor({ state: 'detached' });
});

When(/^ich in der Hilfe-Leiste den Abschnitt «(.+)» wähle$/, async function (titel) {
  const leiste = dialog(this.seite);
  const link = (await verzeichnisOeffnen(leiste)).getByRole('link', { name: titel, exact: true });
  await link.click();
  await this.seite.waitForFunction(
    (name) =>
      [...document.querySelectorAll('#hilfe-leiste .hilfe-inhalt a')].some(
        (element) =>
          element.textContent?.trim() === name &&
          element.getAttribute('aria-current') === 'location',
      ),
    titel,
  );
  await leiste.getByRole('heading', { name: titel, level: 3, exact: true }).waitFor();
  await this.seite.waitForFunction(() => {
    const text = document.querySelector('#hilfe-leiste app-anleitung-text');
    return text?.querySelector('.text') || text?.querySelector('[data-test=anleitung-fehler]');
  });
  assert.equal(await leiste.locator('[data-test=anleitung-fehler]').count(), 0, titel);
});

When(/^ich den Link «(.+)» in der Hilfe-Leiste wähle$/, async function (text) {
  await dialog(this.seite).getByRole('link', { name: text, exact: true }).click();
});

Then(/^ist der Knopf «(.+)» mit seinem zugänglichen Namen sichtbar$/, async function (name) {
  const knopf = this.seite.getByRole('button', { name, exact: true });
  await knopf.waitFor();
  assert.equal(await knopf.getAttribute('aria-label'), name);
});

Then(/^zeigt der Knopf das Symbol «(.+)»$/, async function (symbol) {
  await this.seite.getByRole('button', { name: 'Hilfe', exact: true }).locator(`app-symbol[name="${symbol}"]`).waitFor();
});

Then(/^ist der Hilfetext (sichtbar|verborgen)$/, async function (status) {
  const text = this.seite.getByRole('button', { name: 'Hilfe', exact: true }).locator('span');
  const visuellSichtbar = await text.evaluate((element) => {
    const stil = getComputedStyle(element);
    return stil.position !== 'absolute' || stil.width !== '1px' || stil.height !== '1px';
  });
  assert.equal(visuellSichtbar, status === 'sichtbar');
});

Then(/^zeigt die Hilfe-Leiste den Abschnitt «(.+)»$/, async function (titel) {
  await dialog(this.seite).getByRole('heading', { name: titel, level: 3, exact: true }).waitFor();
});

Then(/^zeigt die Hilfe-Leiste ihr Inhaltsverzeichnis «(.+)»$/, async function (name) {
  await dialog(this.seite).getByRole('navigation', { name, exact: true }).waitFor();
});

Then('zeigt die Hilfe-Leiste keinen ausgewählten Abschnitt', async function () {
  assert.equal(await dialog(this.seite).locator('.hilfe-abschnitt').count(), 0);
});

Then(/^hat der Hilfe-Dialog den Namen «(.+)»$/, async function (name) {
  await this.seite.getByRole('dialog', { name, exact: true }).waitFor();
});

Then('liegt der Fokus auf der Überschrift der Hilfe-Leiste', async function () {
  await this.seite.waitForFunction(
    () => document.activeElement?.id === 'hilfe-leiste-titel',
  );
});

Then('bleibt der Fokus in der Hilfe-Leiste', async function () {
  assert.equal(await dialog(this.seite).evaluate((element) => element.contains(document.activeElement)), true);
});

Then(/^ist der Knopf «(.+)» wieder fokussiert$/, async function (name) {
  await this.seite.waitForFunction(
    (text) => document.activeElement?.matches('button[aria-label]') &&
      document.activeElement.getAttribute('aria-label') === text,
    name,
  );
});

Then('ist die Hilfe-Leiste geschlossen', async function () {
  await dialog(this.seite).waitFor({ state: 'detached' });
});

const leistenBreite = (seite) => dialog(seite).evaluate((element) => element.closest('mat-sidenav').getBoundingClientRect().width);

Then(/^ist die Hilfe-Leiste (\d+) Pixel breit$/, async function (breite) {
  await this.seite.waitForFunction(
    (soll) => Math.round(document.querySelector('#hilfe-leiste').getBoundingClientRect().width) === soll,
    Number(breite),
  );
  assert.equal(Math.round(await leistenBreite(this.seite)), Number(breite));
});

Then(/^ist die Hilfe-Leiste zwischen (\d+) und (\d+) Pixel breit$/, async function (von, bis) {
  await this.seite.waitForFunction(
    ([min, max]) => {
      const b = document.querySelector('#hilfe-leiste').getBoundingClientRect().width;
      return b >= min && b <= max;
    },
    [Number(von), Number(bis)],
  );
});

Then('ist das Inhaltsverzeichnis der Hilfe-Leiste zugeklappt', async function () {
  const knopf = dialog(this.seite).getByRole('button', { name: /^Abschnitte/ });
  assert.equal(await knopf.getAttribute('aria-expanded'), 'false');
  assert.equal(await dialog(this.seite).getByRole('navigation', { name: 'Inhalt der Anleitung' }).count(), 0);
});

When('ich das Inhaltsverzeichnis der Hilfe-Leiste aufklappe', async function () {
  await verzeichnisOeffnen(dialog(this.seite));
});

Then('ist das Inhaltsverzeichnis der Hilfe-Leiste aufgeklappt', async function () {
  const knopf = dialog(this.seite).getByRole('button', { name: /^Abschnitte/ });
  assert.equal(await knopf.getAttribute('aria-expanded'), 'true');
  await dialog(this.seite).getByRole('navigation', { name: 'Inhalt der Anleitung' }).waitFor({ state: 'visible' });
});

Then(/^enthält das Inhaltsverzeichnis der Hilfe-Leiste den Abschnitt «(.+)» nicht$/, async function (titel) {
  const verzeichnis = await verzeichnisOeffnen(dialog(this.seite));
  assert.equal(await verzeichnis.getByRole('link', { name: titel, exact: true }).count(), 0);
});
