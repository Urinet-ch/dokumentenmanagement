const { After, Given, When, Then, setDefaultTimeout } = require('@cucumber/cucumber');
const { spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');

setDefaultTimeout(120000);

Given('ein WordPress mit dem Archiv-Plugin', function () {
  this.test = null;
});

When('alle Archiv-Shortcodes und Gutenberg-Blöcke eingerichtet sind', function () {
  this.test = 'test_shortcodes_und_bloecke_sind_registriert';
});

When('die Webseiten-Schlüssel und die Rolle der Geschäftsstelle eingestellt werden', function () {
  this.test = 'test_(verschluesselung_und_keine_ausgabe_des_schluessels|erfassungsschluessel_und_geschaeftsstellenrolle)';
});

When('Gäste, Mitglieder und Personen mit beiden Rollen Dokumente abrufen', function () {
  this.test = 'test_stufen_und_cache_sind_getrennt';
});

When('die Einstellungsdaten ohne Längenangabe als Eingabestrom eintreffen', function () {
  this.test = 'test_json_eingang_(ohne_content_length_ist_begrenzt|prueft_laenge_und_festes_schema)';
});

When('das Archiv nicht erreichbar ist', function () {
  this.test = 'test_nicht_erreichbares_archiv_liefert_freundlichen_fehler';
});

When('Download-Sperren ausfallen und ein grosser Download gestreamt wird', function () {
  this.test = 'test_download_(slots_laufen_ab_und_loeschen_keine_fremde_lease|stream_uebernimmt_content_length_und_gibt_bloecke_aus)';
});

When('ein Dokument im Lese-Feed zurückgezogen wird oder der Feed ausfällt', function () {
  this.test = 'test_feed_(invalidiert_cache_vor_der_ausgabe_geaenderter_dokumente|ausfall_gibt_keinen_veralteten_cache_zurueck)';
});

When('Besucher die Archivliste und den Chat öffnen', async function () {
  const { chromium } = require('playwright');
  const basis = process.env.WP_TEST_URL || 'http://localhost:8888';
  this.browser = await chromium.launch({ headless: true });
  this.page = await this.browser.newPage();
  const response = await this.page.request.get(`${basis}/wp-json/wp/v2/pages?slug=urinet-archiv-testseite`);
  assert.equal(response.status(), 200);
  const seiten = await response.json();
  assert.equal(seiten.length, 1, 'Die synthetische Archiv-Testseite fehlt');
  await this.page.goto(seiten[0].link);
  this.browserTest = 'liste-chat';
});

When('die Geschäftsstelle die Erfassungsseite öffnet', function () {
  return (async () => {
    const { chromium } = require('playwright');
    const basis = process.env.WP_TEST_URL || 'http://localhost:8888';
    this.browser = await chromium.launch({ headless: true });
    this.page = await this.browser.newPage();
    await this.page.goto(`${basis}/wp-login.php`);
    await this.page.locator('#user_login').fill(process.env.WP_TEST_USER || 'admin');
    await this.page.locator('#user_pass').fill(process.env.WP_TEST_PASSWORD || 'password');
    await this.page.locator('#wp-submit').click();
    await this.page.goto(`${basis}/wp-admin/admin.php?page=urinet-archiv-erfassung`);
    await this.page.getByRole('heading', { name: 'Archiv – Geschäftsstelle' }).waitFor();
    this.browserTest = true;
  })();
});

Then('zeigt das Plugin dynamische Filter, Seitennavigation und lesbare Chatquellen', async function () {
  assert.equal(this.browserTest, 'liste-chat');
  const liste = this.page.locator('.urinet-archiv-liste-bereich');
  await liste.getByLabel('Fachgebiet').selectOption('bau');
  await liste.getByRole('button', { name: 'Filtern' }).click();
  await this.page.waitForURL(/urinet_archiv_filter_fachgebiet=bau/);
  assert.equal(await liste.locator('select[name="urinet_archiv_filter_fachgebiet"]').inputValue(), 'bau');
  assert.equal(await liste.getByText('Synthetisches Merkblatt 1').count(), 1);
  const naechsteSeite = liste.getByRole('link', { name: 'Nächste Seite' });
  const url = new URL(await naechsteSeite.getAttribute('href'));
  assert.equal(url.searchParams.get('urinet_archiv_filter_fachgebiet'), 'bau');
  assert.equal(url.searchParams.get('urinet_archiv_seite'), '2');
  await naechsteSeite.click();
  await this.page.waitForURL(/urinet_archiv_seite=2/);
  assert.equal(await liste.getByText('Synthetisches Merkblatt 2').count(), 1);
  assert.match(await liste.locator('.urinet-archiv-seiten').innerText(), /Seite 2 von 2/);

  await this.page.locator('[data-urinet-chat-frage]').fill('synthetische Frage');
  await this.page.getByRole('button', { name: 'Fragen' }).click();
  const quelle = this.page.getByRole('link', { name: 'Quelle: Synthetisches Merkblatt (Merkblatt, 3/2071)' });
  await quelle.waitFor();
  const ausgabe = await this.page.locator('[data-urinet-chat-ausgabe]').innerText();
  assert.match(ausgabe, /Synthetische Antwort/);
  assert.match(ausgabe, /Synthetischer Hinweis aus dem Archiv/);
  assert.match(await quelle.getAttribute('href'), /00000000-0000-4000-8000-000000000001$/);
});

Then('übernimmt die Plugin-Ausgabe die Theme-Schrift und besteht WCAG 2.1 AA', async function () {
  const schriftarten = await this.page.evaluate(() => {
    const body = getComputedStyle(document.body).fontFamily;
    const liste = getComputedStyle(document.querySelector('.urinet-archiv-liste-bereich')).fontFamily;
    const chatbot = getComputedStyle(document.querySelector('.urinet-archiv-chatbot')).fontFamily;
    return [body, liste, chatbot];
  });
  assert.ok(schriftarten[0]);
  assert.equal(schriftarten[1], schriftarten[0]);
  assert.equal(schriftarten[2], schriftarten[0]);

  await this.page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
  const pruefung = await this.page.evaluate(async () => {
    const optionen = { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } };
    const wurzeln = [
      document.querySelector('.urinet-archiv-liste-bereich'),
      document.querySelector('.urinet-archiv-chatbot'),
    ];
    const verstosse = [];
    for (const wurzel of wurzeln) {
      const ergebnis = await window.axe.run(wurzel, optionen);
      verstosse.push(...ergebnis.violations.map((verletzung) => verletzung.id));
    }
    return verstosse;
  });
  assert.deepEqual(pruefung, [], `axe meldet WCAG-Verstösse: ${pruefung.join(', ')}`);
});

When('das Plugin mit der unterstützten PHP-Version und WordPress geprüft wird', function () {
  this.test = 'GrundgeruestTest';
  this.codingStandards = true;
});

Then(/^(?:sind alle Archiv-Shortcodes und Gutenberg-Blöcke registriert|sind die Schlüssel verschlüsselt gespeichert und auf der Einstellungsseite nicht sichtbar|prüft das Plugin die Grösse und das feste Schema der Anfrage|meldet das Plugin die jeweilige höchste Stufe und trennt die Cache-Einträge|liefert das Plugin eine freundliche Meldung ohne technische Einzelheiten|gibt das Plugin keinen veralteten Cache aus und bricht bei Feed-Ausfall geschlossen ab|prüft das Plugin ablaufende Sperren und die Länge eines Teil-Streams|zeigt das Plugin Schema-Felder und sperrt die öffentliche Zugriffsstufe|bestehen die Plugin-Tests und die WordPress-Coding-Standards-Prüfung)$/, async function () {
  if (this.browserTest) {
    const art = this.page.locator('[data-urinet-art]');
    const merkblatt = this.page.locator('fieldset[data-urinet-zusatz-art="merkblatt"]');
    const protokoll = this.page.locator('fieldset[data-urinet-zusatz-art="protokoll"]');
    assert.equal(await this.page.getByRole('heading', { name: 'Publikation erfassen' }).count(), 1);
    assert.equal(await this.page.locator('form[enctype="multipart/form-data"]').count(), 1);
    assert.equal(await merkblatt.isVisible(), true);
    assert.equal(await this.page.locator('input[name="urinet_zusatz[merkblatt][ausgabe]"]').isVisible(), true);
    await art.selectOption('protokoll');
    assert.equal(await merkblatt.isVisible(), false);
    assert.equal(await this.page.locator('input[name="urinet_zusatz[merkblatt][ausgabe]"]').isDisabled(), true);
    assert.equal(await protokoll.isVisible(), true);
    assert.equal(await this.page.locator('input[name="urinet_zusatz[protokoll][jahr]"]').isVisible(), true);
    const zugriff = this.page.locator('select[name="urinet_zugriff"]');
    assert.equal(await zugriff.locator('option[value="oeffentlich"]').count(), 0);
    assert.equal(await zugriff.locator('option[value="mitglieder"]').count(), 1);
    assert.equal(await zugriff.locator('option[value="vorstand"]').count(), 1);
    return;
  }
  assert.ok(this.test, 'Kein WordPress-Test ausgewählt');
  const lokal = Boolean(process.env.WP_TESTS_DIR);
  const command = lokal ? (process.env.WORDPRESS_PHP || 'php') : 'npm';
  const args = lokal
    ? ['-n', './vendor/bin/phpunit', '--configuration', 'phpunit.xml', '--filter', this.test]
    : ['run', 'wp-env', '--', 'run', 'tests-cli', '--env-cwd=wp-content/plugins/urinet-archiv-plugin', './vendor/bin/phpunit', '--configuration', 'phpunit.xml', '--filter', this.test];
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 100000 });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /OK \([1-9]\d* tests?, [1-9]\d* assertions?\)/);
  if (this.codingStandards) {
    const phpcsArgs = lokal
      ? ['./vendor/bin/phpcs', '--standard=phpcs.xml']
      : ['run', 'wp-env', '--', 'run', 'tests-cli', '--env-cwd=wp-content/plugins/urinet-archiv-plugin', './vendor/bin/phpcs', '--standard=phpcs.xml'];
    const phpcs = spawnSync(lokal ? 'php' : command, phpcsArgs, { encoding: 'utf8', timeout: 100000 });
    if (phpcs.error) throw phpcs.error;
    assert.equal(phpcs.status, 0, `${phpcs.stdout}\n${phpcs.stderr}`);
  }
});

After(async function () {
  if (this.browser) {
    await this.browser.close();
  }
});
