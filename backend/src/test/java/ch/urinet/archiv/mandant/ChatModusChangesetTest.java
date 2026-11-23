package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 04, Requirement 21.1 (Aufgabe 18.1), Changeset {@code 130-chat-gespraech-modus}: Standard ARCHIV, Check-Constraint
 * auf ARCHIV und WISSEN, Rollback ohne Datenverlust und wiederholbar, Vorwärts-Changeset danach wiederholbar.
 */
@QuarkusTest
class ChatModusChangesetTest {

    private static final MandantCode MANDANT = new MandantCode("chat-modus");
    private static final String CHANGESET = "130-chat-gespraech-modus";

    @Inject MandantProvisionierung provisionierung;
    @Inject MandantDatenbank mandantDatenbank;
    @Inject Instance<AgroalDataSource> datenbank;

    @Test
    void standardCheckUndRollbackOhneDatenverlust() throws Exception {
        if (wert("select count(*) from public.mandant where code = '" + MANDANT.wert() + "'").equals("0")) {
            provisionierung.anlegen(MANDANT.wert(), null, null);
        }
        String s = MANDANT.schema();
        UUID alt = UUID.randomUUID();
        UUID wissen = UUID.randomUUID();
        ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) "
                + "values ('" + alt + "', 'b', 'Alt', now(), now())");
        ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, modus, erstellt, geaendert) "
                + "values ('" + wissen + "', 'b', 'Wissen', 'WISSEN', now(), now())");
        assertThat(wert("select modus from " + s + ".chat_gespraech where id = '" + alt + "'")).isEqualTo("ARCHIV");
        assertThatThrownBy(() -> ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, modus, erstellt, "
                + "geaendert) values (gen_random_uuid(), 'b', 'X', 'ALLES', now(), now())"))
                .hasMessageContaining("chat_gespraech_modus_ck");
        assertThatThrownBy(() -> ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, modus, erstellt, "
                + "geaendert) values (gen_random_uuid(), 'b', 'X', null, now(), now())")).isInstanceOf(SQLException.class);

        rollback(s);
        rollback(s);
        assertThat(wert("select count(*) from pg_constraint where conname = 'chat_gespraech_modus_ck' "
                + "and connamespace = '" + s + "'::regnamespace")).isEqualTo("0");
        assertThat(wert("select modus from " + s + ".chat_gespraech where id = '" + wissen + "'")).isEqualTo("WISSEN");
        assertThat(wert("select modus from " + s + ".chat_gespraech where id = '" + alt + "'")).isEqualTo("ARCHIV");
        assertThat(wert("select count(*) from " + s + ".chat_gespraech")).isEqualTo("2");

        ausfuehren("delete from " + s + ".databasechangelog where id = '" + CHANGESET + "'");
        mandantDatenbank.schemaMigrieren(s);
        assertThat(wert("select count(*) from pg_constraint where conname = 'chat_gespraech_modus_ck' "
                + "and connamespace = '" + s + "'::regnamespace")).isEqualTo("1");
        assertThat(wert("select modus from " + s + ".chat_gespraech where id = '" + wissen + "'")).isEqualTo("WISSEN");
        ausfuehren("delete from " + s + ".chat_gespraech");
    }

    private void rollback(String schema) throws Exception {
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("set search_path to " + schema);
            }
            Database datenbankLiquibase = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
            datenbankLiquibase.setDefaultSchemaName(schema);
            datenbankLiquibase.setLiquibaseSchemaName(schema);
            Liquibase liquibase = new Liquibase("db/mandant/changelog.xml",
                    new ClassLoaderResourceAccessor(getClass().getClassLoader()), datenbankLiquibase);
            liquibase.getDatabaseChangeLog().getChangeSets().stream()
                    .filter(changeSet -> changeSet.getId().equals(CHANGESET))
                    .findFirst().orElseThrow().rollback(datenbankLiquibase);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("reset search_path");
            }
        }
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.executeUpdate(sql);
        }
    }

    private String wert(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery(sql)) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }
}
