package ch.urinet.archiv.mandant;

import io.quarkus.test.junit.QuarkusTest;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 04, Req 22.2, Changeset {@code 142-protokoll-wissen-freischaltung}: Der Rollback nach dem ersten Schalten löscht
 * keinen Protokolleintrag und lässt die beiden Aktionen gültig (Protokoll unveränderlich, wie 083). Läuft in einem
 * eigenen Probe-Schema.
 */
@QuarkusTest
@Tag("req-22.2")
class WissenFreischaltungProtokollMigrationTest {

    private static final String CHANGELOG = "db/mandant/changesets/142-protokoll-wissen-freischaltung.xml";

    @Test
    void rollbackNachDemErstenSchaltenErhaeltDieProtokolleintraege() throws Exception {
        String schema = "test_wissen_schalter_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection verbindung = verbinden()) {
            verbindung.setAutoCommit(false);
            try (Statement sql = verbindung.createStatement()) {
                sql.execute("create schema " + schema);
                sql.execute("set search_path to " + schema);
                sql.execute("create table zugriff_protokoll (aktion text not null "
                        + "constraint zugriff_protokoll_aktion_ck check (aktion in ('ANZEIGEN')))");
            }
            verbindung.commit();

            aendern(verbindung, schema, "updateCount");
            verbindung.commit();
            try (Statement sql = verbindung.createStatement()) {
                sql.execute("insert into zugriff_protokoll (aktion) values ('WISSEN_FREIGESCHALTET'), "
                        + "('WISSEN_ABGESCHALTET')");
            }
            verbindung.commit();

            aendern(verbindung, schema, "rollbackCount");
            verbindung.commit();

            assertThat(wert(verbindung, "select count(*) from " + schema + ".zugriff_protokoll "
                    + "where aktion like 'WISSEN_%GESCHALTET'")).isEqualTo("2");
            try (Statement sql = verbindung.createStatement()) {
                sql.execute("insert into " + schema + ".zugriff_protokoll (aktion) values ('WISSEN_ABGESCHALTET')");
            }
            verbindung.commit();
            assertThat(wert(verbindung, "select count(*) from " + schema + ".zugriff_protokoll "
                    + "where aktion like 'WISSEN_%GESCHALTET'")).isEqualTo("3");
        } finally {
            try (Connection verbindung = verbinden(); Statement sql = verbindung.createStatement()) {
                sql.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void aendern(Connection verbindung, String schema, String kommando) throws Exception {
        var datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(schema);
        datenbank.setLiquibaseSchemaName(schema);
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(),
                new ClassLoaderResourceAccessor(WissenFreischaltungProtokollMigrationTest.class.getClassLoader()),
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope(kommando)
                        .addArgumentValue("count", 1)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                        .execute());
    }

    private static Connection verbinden() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static String wert(Connection verbindung, String abfrage) throws SQLException {
        try (Statement sql = verbindung.createStatement(); ResultSet ergebnis = sql.executeQuery(abfrage)) {
            assertThat(ergebnis.next()).isTrue();
            return ergebnis.getString(1);
        }
    }
}
