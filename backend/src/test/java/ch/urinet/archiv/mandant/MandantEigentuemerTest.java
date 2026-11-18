package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prod-Pfad der DB-Rollen (Req 1.7, design.md «Secrets und Konfiguration»): Liquibase und Provisionierung mit
 * {@code archiv_owner} über eine eigene Verbindung, die App mit {@code archiv_app} nur mit DML-Rechten.
 */
@Tag("start-4")
@QuarkusTest
@TestProfile(MandantEigentuemerTest.ProdRollen.class)
class MandantEigentuemerTest {

    private static final List<String> DML = List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    public static class ProdRollen implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.devservices.init-script-path", "db-test/rollen.sql",
                    "quarkus.liquibase.username", "archiv_owner",
                    "quarkus.liquibase.password", "owner-nur-test");
        }
    }

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantDatenbank datenbank;

    @Test
    void appRolleNurDml() throws SQLException {
        provisionierung.anlegen("probe-eigner", null, null);
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            for (String recht : DML) {
                assertThat(wahr(befehl, "has_table_privilege('m_probe_eigner.dokumentart', '" + recht + "')")).as(recht).isTrue();
                for (String tabelle : List.of("databasechangelog", "databasechangeloglock")) {
                    assertThat(wahr(befehl, "has_table_privilege('m_probe_eigner." + tabelle + "', '" + recht + "')"))
                            .as(tabelle + " " + recht).isFalse();
                }
                for (String tabelle : List.of("mandant", "auftrag", "api_client")) {
                    assertThat(wahr(befehl, "has_table_privilege('public." + tabelle + "', '" + recht + "')"))
                            .as("public." + tabelle + " " + recht).isTrue();
                }
            }
            assertThat(wahr(befehl, "has_sequence_privilege('public.auftrag_id_seq', 'USAGE')")).isTrue();
            assertThat(wahr(befehl, "has_schema_privilege('public', 'CREATE')")).isFalse();
            assertThat(wahr(befehl, "has_schema_privilege('m_probe_eigner', 'CREATE')")).isFalse();
            assertThat(wahr(befehl, "has_database_privilege(current_database(), 'CREATE')")).isFalse();

            befehl.execute("insert into m_probe_eigner.dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht, aktiv)"
                    + " values ('APP_PROBE', 'App', 'NORMAL', false, true)");
            befehl.execute("update m_probe_eigner.dokumentart set name = 'App 2' where code = 'APP_PROBE'");
            assertThat(wert(befehl, "select name from m_probe_eigner.dokumentart where code = 'APP_PROBE'")).isEqualTo("App 2");
            befehl.execute("delete from m_probe_eigner.dokumentart where code = 'APP_PROBE'");

            verweigert(befehl, "create table m_probe_eigner.x()");
            for (String tabelle : List.of("databasechangelog", "databasechangeloglock")) {
                verweigert(befehl, "select count(*) from m_probe_eigner." + tabelle);
                verweigert(befehl, "update m_probe_eigner." + tabelle + " set id = id");
                verweigert(befehl, "delete from m_probe_eigner." + tabelle);
            }
            verweigert(befehl, "insert into m_probe_eigner.databasechangeloglock (id, locked) values (99, false)");
        }

        // Default Privileges: eine später von archiv_owner angelegte Tabelle und Sequenz ist für archiv_app nutzbar
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test"); Statement befehl = eigentuemer.createStatement()) {
            befehl.execute("create table m_probe_eigner.spaeter (id int)");
            befehl.execute("create sequence m_probe_eigner.spaeter_seq");
        }
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            befehl.execute("insert into m_probe_eigner.spaeter values (1)");
            assertThat(wert(befehl, "select count(*) from m_probe_eigner.spaeter")).isEqualTo("1");
            assertThat(wert(befehl, "select nextval('m_probe_eigner.spaeter_seq')")).isEqualTo("1");
            assertThat(wahr(befehl, "has_sequence_privilege('m_probe_eigner.spaeter_seq', 'USAGE')")).isTrue();
            assertThat(wahr(befehl, "has_sequence_privilege('m_probe_eigner.spaeter_seq', 'SELECT')")).isTrue();
        }
    }

    /**
     * Zugriffsprotokoll nur Einfügen (Req 10.3): {@code archiv_app} hat auf {@code zugriff_protokoll} nur INSERT und
     * SELECT (dazu die Sequenz), kein UPDATE, DELETE oder TRUNCATE – auch nicht über die Default Privileges. Selbst der
     * Eigentümer scheitert am Trigger.
     */
    @Test
    void zugriffProtokollNurEinfuegen() throws SQLException {
        provisionierung.anlegen("probe-protokoll", null, null);
        String tabelle = "m_probe_protokoll.zugriff_protokoll";
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'INSERT')")).isTrue();
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'SELECT')")).isTrue();
            for (String recht : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', '" + recht + "')")).as(recht).isFalse();
            }
            assertThat(wahr(befehl, "has_sequence_privilege('m_probe_protokoll.zugriff_protokoll_id_seq', 'USAGE')"))
                    .isTrue();
            befehl.execute("insert into " + tabelle + " (benutzer, rolle, aktion) values ('probe', 'INHABER', 'ANZEIGEN')");
            assertThat(wert(befehl, "select count(*) from " + tabelle)).isEqualTo("1");
            verweigert(befehl, "update " + tabelle + " set benutzer = 'x'");
            verweigert(befehl, "delete from " + tabelle);
            verweigert(befehl, "truncate " + tabelle);
        }
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String sql : List.of("update " + tabelle + " set benutzer = 'x'", "delete from " + tabelle,
                    "truncate " + tabelle)) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
            assertThat(wert(befehl, "select count(*) from " + tabelle)).isEqualTo("1");
        }
    }

    /**
     * Versionen der Metadaten nur Einfügen (Req 10.3, Aufgabe 2.2): wie beim Zugriffsprotokoll hat {@code archiv_app}
     * auf {@code dokument_version} nur INSERT und SELECT, und selbst der Eigentümer scheitert am Trigger.
     */
    @Test
    void dokumentVersionNurEinfuegen() throws SQLException {
        provisionierung.anlegen("probe-version", null, null);
        String tabelle = "m_probe_version.dokument_version";
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'INSERT')")).isTrue();
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'SELECT')")).isTrue();
            for (String recht : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', '" + recht + "')")).as(recht).isFalse();
            }
            assertThat(wahr(befehl, "has_sequence_privilege('m_probe_version.dokument_version_id_seq', 'USAGE')"))
                    .isTrue();
            befehl.execute("insert into m_probe_version.dokument (id, quelle, erstellt_von, status) values "
                    + "('00000000-0000-4000-8000-0000000000a1', 'UPLOAD', 'probe', 'NEU')");
            // Version 1 (Migration, leer) und Version 2 (Seed) legt die Provisionierung an; Dokumente erhalten die letzte
            assertThat(wert(befehl, "select konfiguration_version from m_probe_version.dokument"
                    + " where id = '00000000-0000-4000-8000-0000000000a1'")).isEqualTo("2");
            befehl.execute("insert into " + tabelle + " (dokument_id, version, daten, geaendert_von, grund) values "
                    + "('00000000-0000-4000-8000-0000000000a1', 0, '{}', 'probe', 'Probe')");
            assertThat(wert(befehl, "select konfiguration_version from " + tabelle)).isEqualTo("2");
            assertThat(wert(befehl, "select count(*) from " + tabelle)).isEqualTo("1");
            verweigert(befehl, "update " + tabelle + " set grund = 'x'");
            verweigert(befehl, "delete from " + tabelle);
            verweigert(befehl, "truncate " + tabelle);
        }
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String sql : List.of("update " + tabelle + " set grund = 'x'", "delete from " + tabelle,
                    "truncate " + tabelle)) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
            assertThat(wert(befehl, "select count(*) from " + tabelle)).isEqualTo("1");
        }
    }

    /**
     * Benutzerverzeichnis nur Einfügen (Req 10.10, Aufgabe 5.1): wie beim Zugriffsprotokoll hat {@code archiv_app} auf
     * {@code benutzer_verzeichnis} nur INSERT und SELECT, und selbst der Eigentümer scheitert am Trigger.
     */
    @Test
    void benutzerVerzeichnisNurEinfuegen() throws SQLException {
        provisionierung.anlegen("probe-verzeichnis", null, null);
        String tabelle = "m_probe_verzeichnis.benutzer_verzeichnis";
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'INSERT')")).isTrue();
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'SELECT')")).isTrue();
            for (String recht : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', '" + recht + "')")).as(recht).isFalse();
            }
            assertThat(wahr(befehl,
                    "has_sequence_privilege('m_probe_verzeichnis.benutzer_verzeichnis_id_seq', 'USAGE')")).isTrue();
            befehl.execute("insert into " + tabelle + " (benutzer, name, rolle, gueltig_von, anlass) values "
                    + "('probe', 'Probe Person', 'MITARBEITER', clock_timestamp(), 'AKTION')");
            assertThat(wert(befehl, "select count(*) from " + tabelle)).isEqualTo("1");
            verweigert(befehl, "update " + tabelle + " set name = 'x'");
            verweigert(befehl, "delete from " + tabelle);
            verweigert(befehl, "truncate " + tabelle);
        }
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String sql : List.of("update " + tabelle + " set gueltig_bis = clock_timestamp()",
                    "delete from " + tabelle, "truncate " + tabelle)) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
            assertThat(wert(befehl, "select count(*) from " + tabelle)).isEqualTo("1");
        }
    }

    @Test
    void wissenVersionenUndLogNurEinfuegen() throws SQLException {
        String schema = "m_probe_wissen_" + UUID.randomUUID().toString().replace("-", "");
        datenbank.schemaAnlegen(schema);
        String seiteId = "00000000-0000-4000-8000-0000000000b1";
        String dokumentId = "00000000-0000-4000-8000-0000000000b2";
        List<String> tabellen = List.of("wissensseite_version", "wissensseite_version_dokument", "wissens_log");
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            befehl.execute("insert into " + schema + ".dokument (id, quelle, erstellt_von, status) values "
                    + "('" + dokumentId + "', 'UPLOAD', 'probe', 'NEU')");
            befehl.execute("insert into " + schema + ".wissensseite "
                    + "(id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt, version, erzeugt_von, erzeugt_am, "
                    + "freigegeben_von, freigegeben_am) values ('" + seiteId + "', 'probe/wissen', 'Konzept', "
                    + "'Probe', 'Probe', 'NORMAL', 'synthetischer Inhalt', 1, 'probe', clock_timestamp(), 'probe', "
                    + "clock_timestamp())");
            befehl.execute("insert into " + schema + ".wissensseite_version "
                    + "(seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit) values "
                    + "('" + seiteId + "', 1, '{}', 'synthetischer Inhalt', 'probe', 'probe', clock_timestamp())");
            befehl.execute("insert into " + schema + ".wissensseite_version_dokument (seite_id, version, dokument_id) "
                    + "values ('" + seiteId + "', 1, '" + dokumentId + "')");
            befehl.execute("insert into " + schema + ".wissens_log (zeit, aktion, akteur, seiten) values "
                    + "(clock_timestamp(), 'CREATION', 'probe', array['" + seiteId + "']::uuid[])");

            for (String tabelle : tabellen) {
                String voll = schema + "." + tabelle;
                assertThat(wahr(befehl, "has_table_privilege('" + voll + "', 'INSERT')")).as(tabelle).isTrue();
                assertThat(wahr(befehl, "has_table_privilege('" + voll + "', 'SELECT')")).as(tabelle).isTrue();
                for (String recht : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                    assertThat(wahr(befehl, "has_table_privilege('" + voll + "', '" + recht + "')"))
                            .as(tabelle + " " + recht).isFalse();
                }
                verweigert(befehl, "delete from " + voll);
                verweigert(befehl, "truncate " + voll);
                verweigert(befehl, "update " + voll + " set " + (tabelle.equals("wissensseite_version_dokument")
                        ? "version = version" : "zeit = zeit"));
            }
        }
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String tabelle : tabellen) {
                String voll = schema + "." + tabelle;
                String truncate = "truncate " + voll
                        + (tabelle.equals("wissensseite_version") ? " cascade" : "");
                for (String sql : List.of("delete from " + voll, truncate,
                        "update " + voll + " set " + (tabelle.equals("wissensseite_version_dokument")
                                ? "version = version" : "zeit = zeit"))) {
                    assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                            .hasMessageContaining("nur Einfügen erlaubt");
                }
            }
        }
    }

    /**
     * Alle Tabellen mit «nur Einfügen» (Req 10.3, 15.1; Review PR #87, S-g): {@code archiv_app} hat weder UPDATE,
     * DELETE noch TRUNCATE, und für den Eigentümer gilt ein Zeilen-Trigger auf UPDATE und DELETE sowie ein Trigger auf
     * TRUNCATE. Die Liste muss {@code LiquibaseMandantDatenbank.NUR_EINFUEGEN} entsprechen.
     */
    @Test
    void alleNurEinfuegenTabellenSindGeschuetzt() throws Exception {
        List<String> tabellen = List.of("zugriff_protokoll", "dokument_version", "benutzer_verzeichnis",
                "manifest", "manifest_zeitstempel", "protokoll_blatt", "konfiguration_version",
                "wissensseite_version", "wissensseite_version_dokument", "wissens_log");
        var feld = Class.forName("ch.urinet.archiv.mandant.adapter.out.persistence.LiquibaseMandantDatenbank")
                .getDeclaredField("NUR_EINFUEGEN");
        feld.setAccessible(true);
        assertThat(feld.get(null)).isEqualTo(tabellen);

        provisionierung.anlegen("probe-einfuegen", null, null);
        String schema = "m_probe_einfuegen";
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            for (String tabelle : tabellen) {
                for (String recht : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                    assertThat(wahr(befehl, "has_table_privilege('" + schema + "." + tabelle + "', '" + recht + "')"))
                            .as(tabelle + " " + recht).isFalse();
                }
                assertThat(wahr(befehl, "has_table_privilege('" + schema + "." + tabelle + "', 'INSERT')"))
                        .as(tabelle).isTrue();
            }
        }
        String versionen = schema + ".konfiguration_version";
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String sql : List.of("update " + versionen + " set wer = 'geändert'",
                    "delete from " + versionen)) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
            assertThat(wert(befehl, "select count(*) from " + versionen)).isEqualTo("2");
        }
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String tabelle : tabellen) {
                String klasse = "'" + schema + "." + tabelle + "'::regclass";
                // tgtype: 1 = je Zeile, 2 = BEFORE, 8 = DELETE, 16 = UPDATE, 32 = TRUNCATE
                assertThat(wahr(befehl, "exists (select 1 from pg_trigger where tgrelid = " + klasse
                        + " and not tgisinternal and tgtype & 27 = 27)")).as(tabelle + " UPDATE/DELETE").isTrue();
                assertThat(wahr(befehl, "exists (select 1 from pg_trigger where tgrelid = " + klasse
                        + " and not tgisinternal and tgtype & 34 = 34)")).as(tabelle + " TRUNCATE").isTrue();
                if (!tabelle.equals("konfiguration_version")) {
                    assertThatThrownBy(() -> befehl.execute("truncate " + schema + "." + tabelle + " cascade"))
                            .as(tabelle).isInstanceOf(SQLException.class).hasMessageContaining("nur Einfügen erlaubt");
                }
            }
        }
    }

    @Test
    void sicherungsexportNurEinfuegen() throws SQLException {
        String tabelle = "public.sicherung_export";
        String sha256 = "a".repeat(64);
        try (Connection app = verbinden("archiv_app", "app-nur-test"); Statement befehl = app.createStatement()) {
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'INSERT')")).isTrue();
            assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', 'SELECT')")).isTrue();
            for (String recht : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(wahr(befehl, "has_table_privilege('" + tabelle + "', '" + recht + "')")).as(recht).isFalse();
            }
            befehl.execute("insert into " + tabelle + " (art, sha256) values ('db', '" + sha256 + "')");
            assertThat(wert(befehl, "select count(*) from " + tabelle + " where sha256 = '" + sha256 + "'"))
                    .isNotEqualTo("0");
            verweigert(befehl, "update " + tabelle + " set sha256 = '" + "b".repeat(64)
                    + "' where sha256 = '" + sha256 + "'");
            verweigert(befehl, "delete from " + tabelle + " where sha256 = '" + sha256 + "'");
            verweigert(befehl, "truncate " + tabelle);
        }
        try (Connection eigentuemer = verbinden("archiv_owner", "owner-nur-test");
             Statement befehl = eigentuemer.createStatement()) {
            for (String sql : List.of("update " + tabelle + " set sha256 = '" + "c".repeat(64)
                            + "' where sha256 = '" + sha256 + "'",
                    "delete from " + tabelle + " where sha256 = '" + sha256 + "'", "truncate " + tabelle)) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
        }
    }

    /**
     * {@code berechtigen()} ist atomar (Review PR #29): Scheitert das REVOKE auf {@code databasechangelog*} nach den GRANTs,
     * bleibt kein Recht der App-Rolle zurück – weder auf die Changelog-Tabellen noch auf die übrigen Tabellen.
     */
    @Test
    void berechtigenAtomar() throws SQLException {
        String schema = "m_probe_atomar";
        try (Connection admin = verbinden(); Statement befehl = admin.createStatement()) {
            befehl.execute("create function public.probe_revoke_scheitert() returns event_trigger language plpgsql as $$ "
                    + "begin if current_setting('search_path') = '" + schema + "' then "
                    + "raise exception 'Probe: Fehler zwischen GRANT und REVOKE'; end if; end $$");
            befehl.execute("create event trigger probe_revoke_scheitert on ddl_command_start when tag in ('REVOKE') "
                    + "execute function public.probe_revoke_scheitert()");
        }
        try {
            assertThatThrownBy(() -> datenbank.schemaAnlegen(schema)).hasMessageContaining(schema);
        } finally {
            try (Connection admin = verbinden(); Statement befehl = admin.createStatement()) {
                befehl.execute("drop event trigger probe_revoke_scheitert");
                befehl.execute("drop function public.probe_revoke_scheitert()");
            }
        }
        try (Connection admin = verbinden(); Statement befehl = admin.createStatement()) {
            assertThat(wahr(befehl, "has_schema_privilege('archiv_app', '" + schema + "', 'USAGE')")).as("USAGE").isFalse();
            for (String recht : DML) {
                for (String tabelle : List.of("databasechangelog", "databasechangeloglock", "dokumentart")) {
                    assertThat(wahr(befehl, "has_table_privilege('archiv_app', '" + schema + "." + tabelle + "', '"
                            + recht + "')"))
                            .as(tabelle + " " + recht).isFalse();
                }
            }
        }
    }

    private static Connection verbinden() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static Connection verbinden(String benutzer, String passwort) throws SQLException {
        String url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
        return DriverManager.getConnection(url, benutzer, passwort);
    }

    private static void verweigert(Statement befehl, String sql) {
        assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    private static boolean wahr(Statement befehl, String ausdruck) throws SQLException {
        try (ResultSet ergebnis = befehl.executeQuery("select " + ausdruck)) {
            ergebnis.next();
            return ergebnis.getBoolean(1);
        }
    }

    private static String wert(Statement befehl, String sql) throws SQLException {
        try (ResultSet ergebnis = befehl.executeQuery(sql)) {
            ergebnis.next();
            return ergebnis.getString(1);
        }
    }
}
