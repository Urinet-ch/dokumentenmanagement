import { Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const basisUrl = process.env.ARCHIV_URL ?? 'http://localhost:8080';

// Spec 07, Req 7.1: Die Datei kommt unverändert aus frontend/public/anleitung/ (Cucumber.js läuft in frontend/) und ist
// nicht die Rückfallseite der SPA.
Then(/^liefert das Archiv die Anleitung «([a-z0-9-]+)» als Markdown aus dem Build aus$/, async function (anker) {
  const antwort = await this.seite.request.get(`${basisUrl}/anleitung/${anker}.md`);
  assert.equal(antwort.status(), 200);
  assert.doesNotMatch(antwort.headers()['content-type'] ?? '', /text\/html/);
  assert.equal(await antwort.text(), await readFile(`public/anleitung/${anker}.md`, 'utf8'));
});
