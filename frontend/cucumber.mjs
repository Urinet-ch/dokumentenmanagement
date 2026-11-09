// Cucumber.js für Browser-Szenarien (tech.md, Tests): dieselben Feature-Dateien wie das Backend, nur @browser.
// Die App muss laufen (Prod-Build mit PWA aus Quinoa); Adresse über ARCHIV_URL, Standard http://localhost:8080.
export default {
  paths: ['../backend/src/test/resources/features/**/*.feature'],
  import: ['e2e/**/*.mjs'],
  tags: '@browser',
  format: ['progress'],
  language: 'de',
};
