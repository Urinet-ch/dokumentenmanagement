const { After, Given, When, Then, setDefaultTimeout } = require('@cucumber/cucumber');
const { spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');

setDefaultTimeout(120000);

Given('ein WordPress mit dem Archiv-Plugin', function () {
  this.test = null;
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

When('ein Dokument im Lese-Feed zurückgezogen wird oder der Feed ausfällt', function () {
  this.test = 'test_feed_(invalidiert_cache_vor_der_ausgabe_geaenderter_dokumente|ausfall_gibt_keinen_veralteten_cache_zurueck)';
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

When('das Plugin mit der unterstützten PHP-Version und WordPress geprüft wird', function () {
  this.test = 'GrundgeruestTest';
  this.codingStandards = true;
});

Then(/^(?:sind die Schlüssel verschlüsselt gespeichert und auf der Einstellungsseite nicht sichtbar|prüft das Plugin die Grösse und das feste Schema der Anfrage|meldet das Plugin die jeweilige höchste Stufe und trennt die Cache-Einträge|liefert das Plugin eine freundliche Meldung ohne technische Einzelheiten|gibt das Plugin keinen veralteten Cache aus und bricht bei Feed-Ausfall geschlossen ab|zeigt das Plugin Schema-Felder und sperrt die öffentliche Zugriffsstufe|bestehen die Plugin-Tests und die WordPress-Coding-Standards-Prüfung)$/, async function () {
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
