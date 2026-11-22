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

    private static final String CHANGELOG = "db/public/changesets/044-token-widerruf.xml";

    @Test
    void updateRollbackUpdateErhaeltTabelleConstraintsUndWiderrufe() throws Exception {
        String benutzer = "token-widerruf-migration-" + UUID.randomUUID();
        String liquibaseSchema = "test_liquibase_" + UUID.randomUUID().toString().replace("-", "");
        try {
            try (Connection verbindung = verbinden()) {
                verbindung.setAutoCommit(false);
                try (Statement anlegen = verbindung.createStatement()) {
                    anlegen.execute("create schema " + liquibaseSchema);
                }
                verbindung.commit();
                aendern(verbindung, "updateCount", liquibaseSchema);
                verbindung.commit();
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "insert into public.token_widerruf (benutzer, ab) values (?, now())")) {
                    einfuegen.setString(1, benutzer);
                    einfuegen.executeUpdate();
                }
                verbindung.commit();

                aendern(verbindung, "rollbackCount", liquibaseSchema);
                verbindung.commit();
                assertThat(wert(verbindung, "select count(*) from " + liquibaseSchema
                        + ".databasechangelog where id = '044-token-widerruf'")).isEqualTo("0");
                assertThat(wert(verbindung, "select count(*) from public.token_widerruf where benutzer = ?", benutzer))
                        .isEqualTo("1");

                aendern(verbindung, "updateCount", liquibaseSchema);
                verbindung.commit();

                assertThat(wert(verbindung, "select count(*) from " + liquibaseSchema
                        + ".databasechangelog where id = '044-token-widerruf'")).isEqualTo("1");
                assertThat(wert(verbindung, "select count(*) from public.token_widerruf where benutzer = ?", benutzer))
                        .isEqualTo("1");
                assertThat(wert(verbindung, "select count(*) from pg_constraint "
                        + "where conrelid = 'public.token_widerruf'::regclass and contype in ('p', 'c')"))
                        .isEqualTo("2");
                assertThat(wert(verbindung, "select count(*) from pg_index "
                        + "where indrelid = 'public.token_widerruf'::regclass and indisvalid and indisprimary"))
                        .isEqualTo("1");
            }
        } finally {
            try (Connection verbindung = verbinden()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.token_widerruf where benutzer = ?")) {
                    loeschen.setString(1, benutzer);
                    loeschen.executeUpdate();
                }
                try (Statement entfernen = verbindung.createStatement()) {
                    entfernen.execute("drop schema if exists " + liquibaseSchema + " cascade");
                }
            }
        }
    }

    private static void aendern(Connection verbindung, String kommando, String liquibaseSchema) throws Exception {
        var datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName("public");
        datenbank.setLiquibaseSchemaName(liquibaseSchema);
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(),
                new ClassLoaderResourceAccessor(TokenWiderrufMigrationTest.class.getClassLoader()),
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
