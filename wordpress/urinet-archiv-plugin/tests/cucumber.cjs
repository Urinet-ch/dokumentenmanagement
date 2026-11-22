module.exports = {
  default: {
    paths: ['../../backend/src/test/resources/features/integration/wordpress-grundgeruest.feature'],
    require: ['tests/cucumber-schritte.cjs'],
    tags: '@wordpress',
    language: 'de',
    format: ['progress'],
  },
};
