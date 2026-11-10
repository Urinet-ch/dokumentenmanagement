package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prod-Pfad der DB-Rollen (Req 1.7, design.md «Secrets und Konfiguration»): Liquibase und Provisionierung mit
 * {@code archiv_owner} über eine eigene Verbindung, die App mit {@code archiv_app} nur mit DML-Rechten.
 */
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
