import { After, Before, Then, When, setDefaultTimeout } from '@cucumber/cucumber';
import { chromium } from 'playwright';
import assert from 'node:assert/strict';

const basisUrl = process.env.ARCHIV_URL ?? 'http://localhost:8080';

setDefaultTimeout(30_000);

Before(async function () {
  this.browser = await chromium.launch();
  this.seite = await this.browser.newPage({ locale: 'de-CH' });
});

After(async function () {
  await this.browser?.close();
});

When('ich die Startseite öffne', async function () {
  await this.seite.goto(basisUrl);
});

Then(/^sehe ich den Titel «(.+)»$/, async function (titel) {
  await this.seite.getByText(titel, { exact: true }).first().waitFor();
  assert.equal(await this.seite.title(), titel);
});
