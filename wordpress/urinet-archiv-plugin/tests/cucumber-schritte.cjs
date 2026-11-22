const { Given, When, Then, setDefaultTimeout } = require('@cucumber/cucumber');
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

When('das Archiv nicht erreichbar ist', function () {
  this.test = 'test_nicht_erreichbares_archiv_liefert_freundlichen_fehler';
});

When('das Plugin mit der unterstützten PHP-Version und WordPress geprüft wird', function () {
  this.test = 'GrundgeruestTest';
  this.codingStandards = true;
});

Then(/^(?:sind die Schlüssel verschlüsselt gespeichert und auf der Einstellungsseite nicht sichtbar|meldet das Plugin die jeweilige höchste Stufe und trennt die Cache-Einträge|liefert das Plugin eine freundliche Meldung ohne technische Einzelheiten|bestehen die Plugin-Tests und die WordPress-Coding-Standards-Prüfung)$/, function () {
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
