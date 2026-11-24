import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import axe from 'axe-core';

Given('der Chat-Auswertungs-Export verlangt Step-up', function () {
  this.chat.exportStepUpNoetig = true;
});

When('ich den Chat-Auswertungs-Export vorbereite', async function () {
  await this.seite.getByRole('button', { name: 'Export vorbereiten' }).click();
});

Then('ist die Schwärzung im Export standardmässig aktiviert', async function () {
  const schwaerzen = this.seite.getByRole('checkbox', {
    name: 'Bekannte Personendaten im Export schwärzen',
  });
  await schwaerzen.waitFor();
  assert.equal(await schwaerzen.isChecked(), true);
});

When('ich den Chat-Auswertungs-Export bestätige', async function () {
  if (this.chat.exportStepUpNoetig) {
    await this.seite.getByRole('button', { name: 'ZIP herunterladen' }).click();
    return;
  }
  const download = this.seite.waitForEvent('download');
  await this.seite.getByRole('button', { name: 'ZIP herunterladen' }).click();
  this.chatDownload = await download;
});

Then('sehe ich die Chat-Auswertung mit 3 Bewertungen', async function () {
  await this.seite.getByRole('heading', { name: 'Chat-Auswertung', level: 1 }).waitFor();
  await this.seite.locator('[data-test=anzahl-bewertungen]').filter({ hasText: '3' }).waitFor();
  await this.seite.getByText('Niedrige Bewertungen', { exact: true }).waitFor();
  await this.seite.locator('[data-test=schlechte-antwort]').getByText('Synthetische Frage für die Auswertung').waitFor();
});

Then(
  'ist die Chat-Auswertung ohne horizontalen Überlauf und axe-Befund bei 375 und 1280 Pixeln in hell und dunkel',
  async function () {
    await this.seite.addScriptTag({ content: axe.source });
    for (const farbschema of ['light', 'dark']) {
      for (const breite of [375, 1280]) {
        await this.seite.emulateMedia({ colorScheme: farbschema });
        await this.seite.setViewportSize({ width: breite, height: breite === 375 ? 812 : 800 });
        const pruefung = await this.seite.evaluate(() => window.axe.run(document, {
          runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
        }));
        assert.deepEqual(pruefung.violations.map(({ id, nodes }) => ({
          id,
          targets: nodes.map((node) => node.target),
        })), [], `axe ${farbschema}, ${breite}px`);
        const breiteDokument = await this.seite.evaluate(() => ({
          inhalt: document.documentElement.scrollWidth,
          fenster: document.documentElement.clientWidth,
        }));
        assert.ok(breiteDokument.inhalt <= breiteDokument.fenster,
          `Horizontaler Überlauf bei ${farbschema}, ${breite}px: ${JSON.stringify(breiteDokument)}`);
      }
    }
    await this.seite.emulateMedia({ colorScheme: 'light' });
    await this.seite.setViewportSize({ width: 375, height: 812 });
  },
);

Then('der Exportdialog hat keinen axe-Befund', async function () {
  await this.seite.locator('mat-dialog-container').evaluate(async (dialog) => {
    await Promise.all(dialog.getAnimations({ subtree: true }).map((animation) => animation.finished));
  });
  await this.seite.addScriptTag({ content: axe.source });
  const pruefung = await this.seite.evaluate(() => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
  }));
  assert.deepEqual(pruefung.violations.map(({ id, nodes }) => ({
    id,
    nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })),
  })), []);
});

Then('wird ein geschwärztes ZIP mit den gewählten Filtern heruntergeladen', async function () {
  assert.equal(this.chatDownload.suggestedFilename(), 'chat-bewertungen.zip');
  assert.deepEqual(this.chat.exporte.at(-1), {
    von: this.chat.auswertungsAnfragen.at(-1).von,
    bis: this.chat.auswertungsAnfragen.at(-1).bis,
    minSterne: 1,
    maxSterne: 5,
    schwaerzen: true,
    klartextBestaetigt: false,
  });
});

Then('wird eine frische Anmeldung für den Export verlangt', async function () {
  const status = this.seite.getByRole('status').filter({
    hasText: 'Für den Export ist eine frische Anmeldung erforderlich.',
  });
  try {
    await status.waitFor({ timeout: 5000 });
  } catch {
    throw new Error('Step-up-Hinweis fehlt.');
  }
  await this.seite.getByRole('button', { name: 'Erneut anmelden' }).waitFor();
});

When('ich mich für den Chat-Auswertungs-Export erneut anmelde', async function () {
  await this.seite.getByRole('button', { name: 'Erneut anmelden' }).click();
  for (let versuch = 0; versuch < 50 && this.keycloak.authAnfragen.length < 2; versuch++) {
    await this.seite.waitForTimeout(20);
  }
  assert.ok(this.keycloak.authAnfragen.length >= 2, 'keine erneute Anmeldung bei Keycloak');
  await this.seite.getByRole('heading', { name: 'Chat-Auswertung', level: 1 }).waitFor();
});

Then('hat Keycloak eine frische Anmeldung verlangt und wurden keine Filter im Browser gespeichert', async function () {
  const anfrage = this.keycloak.authAnfragen.at(-1);
  assert.equal(anfrage.max_age, '0');
  assert.equal(anfrage.prompt, 'login');
  const gespeichert = await this.seite.evaluate(() =>
    [localStorage, sessionStorage].flatMap((speicher) =>
      Array.from({ length: speicher.length }, (_, i) => `${speicher.key(i)}=${speicher.getItem(speicher.key(i))}`),
    ),
  );
  assert.deepEqual(gespeichert.filter((wert) => !wert.startsWith('archiv.branding.')), []);
});
