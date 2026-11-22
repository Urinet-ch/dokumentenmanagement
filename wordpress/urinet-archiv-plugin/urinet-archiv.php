<?php
/**
 * Plugin Name: UriNet Archiv
 * Description: Server-seitige Anbindung an die Webseiten-Schnittstelle des Archivs.
 * Version: 0.1.0
 * Requires PHP: 8.2
 * Text Domain: urinet-archiv
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

require_once __DIR__ . '/includes/class-einstellungen.php';
require_once __DIR__ . '/includes/class-client.php';
require_once __DIR__ . '/includes/class-feed.php';
require_once __DIR__ . '/includes/class-erfassung.php';

add_action( 'admin_menu', array( 'Urinet_Archiv_Einstellungen', 'menue' ) );
add_action( 'admin_init', array( 'Urinet_Archiv_Einstellungen', 'registrieren' ) );
add_action( 'admin_enqueue_scripts', array( 'Urinet_Archiv_Einstellungen', 'skript' ) );
add_action( 'admin_post_urinet_archiv_speichern', array( 'Urinet_Archiv_Einstellungen', 'speichern' ) );
add_action( 'init', array( 'Urinet_Archiv_Erfassung', 'post_type' ) );
add_action( 'init', array( 'Urinet_Archiv_Feed', 'registrieren' ) );
add_action( 'admin_init', array( 'Urinet_Archiv_Erfassung', 'verarbeiten' ) );
add_action( 'admin_menu', array( 'Urinet_Archiv_Erfassung', 'menue' ) );
add_action( 'admin_enqueue_scripts', array( 'Urinet_Archiv_Erfassung', 'skript' ) );
register_deactivation_hook( __FILE__, array( 'Urinet_Archiv_Feed', 'deaktivieren' ) );
