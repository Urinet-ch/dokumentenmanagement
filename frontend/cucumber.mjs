// Cucumber.js für Browser-Szenarien (tech.md, Tests): dieselben Feature-Dateien wie das Backend, nur @browser.
// Die App muss laufen (Prod-Build mit PWA aus Quinoa); Adresse über ARCHIV_URL, Standard http://localhost:8080.
export default {
  paths: ['../backend/src/test/resources/features/**/*.feature'],
  import: ['e2e/**/*.mjs'],
  // @flaky: bekannt wackelige Szenarien laufen nicht im Hauptlauf (npm run e2e:flaky); bis dahin einmal wiederholen:
  // ein Szenario, das zweimal scheitert, bleibt rot, ein einmaliger Timeout nicht (docs/ci-browser-stabilitaet.md)
  tags: '@browser and not @flaky',
  retry: 1,
  format: ['progress'],
  language: 'de',
};
