package ch.urinet.archiv.sicherheit;

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
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TokenWiderrufMigrationTest {

    @Test
    void updateRollbackUpdateErhaeltTabelleConstraintsUndWiderrufe() throws Exception {
        String benutzer = "token-widerruf-migration-" + UUID.randomUUID();
        try (Connection verbindung = verbinden()) {
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "insert into public.token_widerruf (benutzer, ab) values (?, now())")) {
                einfuegen.setString(1, benutzer);
                einfuegen.executeUpdate();
            }

            assertThat(wert(verbindung, "select id from public.databasechangelog "
                    + "order by orderexecuted desc limit 1")).isEqualTo("044-token-widerruf");

            aendern(verbindung, "rollbackCount");
            verbindung.commit();
            assertThat(wert(verbindung, "select count(*) from public.databasechangelog "
                    + "where id = '044-token-widerruf'")).isEqualTo("0");
            assertThat(wert(verbindung, "select count(*) from public.token_widerruf where benutzer = ?", benutzer))
                    .isEqualTo("1");

            aendern(verbindung, "updateCount");
            verbindung.commit();

            assertThat(wert(verbindung, "select count(*) from public.databasechangelog "
                    + "where id = '044-token-widerruf'")).isEqualTo("1");
            assertThat(wert(verbindung, "select count(*) from public.token_widerruf where benutzer = ?", benutzer))
                    .isEqualTo("1");
            assertThat(wert(verbindung, "select count(*) from pg_constraint "
                    + "where conrelid = 'public.token_widerruf'::regclass and contype in ('p', 'c')"))
                    .isEqualTo("2");
            assertThat(wert(verbindung, "select count(*) from pg_index "
                    + "where indrelid = 'public.token_widerruf'::regclass and indisvalid and indisprimary"))
                    .isEqualTo("1");
        } finally {
            try (Connection verbindung = verbinden()) {
                if ("0".equals(wert(verbindung, "select count(*) from public.databasechangelog "
                        + "where id = '044-token-widerruf'"))) {
                    aendern(verbindung, "updateCount");
                    verbindung.commit();
                }
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.token_widerruf where benutzer = ?")) {
                    loeschen.setString(1, benutzer);
                    loeschen.executeUpdate();
                }
            }
        }
    }

    private static void aendern(Connection verbindung, String kommando) throws Exception {
        var datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName("public");
        datenbank.setLiquibaseSchemaName("public");
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(),
                new ClassLoaderResourceAccessor(TokenWiderrufMigrationTest.class.getClassLoader()),
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope(kommando)
                        .addArgumentValue("count", 1)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db/public/changelog.xml")
                        .execute());
    }

    private static Connection verbinden() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static String wert(Connection verbindung, String sql) throws SQLException {
        try (Statement befehl = verbindung.createStatement(); ResultSet ergebnis = befehl.executeQuery(sql)) {
            assertThat(ergebnis.next()).isTrue();
            return ergebnis.getString(1);
        }
    }

    private static String wert(Connection verbindung, String sql, String parameter) throws SQLException {
        try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, parameter);
            try (ResultSet ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                return ergebnis.getString(1);
            }
        }
    }
}
