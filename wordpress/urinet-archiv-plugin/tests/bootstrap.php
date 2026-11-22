<?php

define( 'URINET_ARCHIV_KEY', base64_encode( random_bytes( SODIUM_CRYPTO_SECRETBOX_KEYBYTES ) ) );

$test_verzeichnis = getenv( 'WP_TESTS_DIR' );
if ( ! $test_verzeichnis ) {
	throw new RuntimeException( 'WP_TESTS_DIR fehlt. Bitte die Tests über wp-env starten.' );
}
require_once $test_verzeichnis . '/includes/functions.php';
tests_add_filter(
	'muplugins_loaded',
	static function () {
		require dirname( __DIR__ ) . '/urinet-archiv.php';
	}
);
require $test_verzeichnis . '/includes/bootstrap.php';
