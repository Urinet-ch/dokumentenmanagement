package ch.urinet.archiv.mandant;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 04, Req 22.1, Changeset {@code 045-mandant-wissen-freigeschaltet}: Mit zwei bestehenden Mandanten setzt die
 * Migration {@code urinet} auf ja und den anderen auf nein; ein danach angelegter Mandant hat nein. Der Rollback
 * entfernt die Spalte, die Migration ist danach wiederholbar. Läuft in einem eigenen Probe-Schema mit einer Tabelle
 * {@code mandant} im Stand vor dem Changeset, damit das Register der übrigen Tests unberührt bleibt.
 */
@QuarkusTest
@Tag("req-22.1")
class WissenFreischaltungMigrationTest {

    private static final String SCHEMA = "probe_wissen_migration";
    private static final String CHANGESET = "db/public/changesets/045-mandant-wissen-freigeschaltet.xml";

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Test
    void urinetJaAndereNeinNeueNein() throws Exception {
        ausfuehren("drop schema if exists " + SCHEMA + " cascade");
        ausfuehren("create schema " + SCHEMA);
        try {
            ausfuehren("create table " + SCHEMA + ".mandant (code text primary key, name text not null)");
            ausfuehren("insert into " + SCHEMA + ".mandant (code, name) values ('urinet', 'U'), ('vhf-gsk', 'V')");

            liquibase(Liquibase::update);
            assertThat(wert("select wissen_freigeschaltet from " + SCHEMA + ".mandant where code = 'urinet'"))
                    .isEqualTo("t");
            assertThat(wert("select wissen_freigeschaltet from " + SCHEMA + ".mandant where code = 'vhf-gsk'"))
                    .isEqualTo("f");
            ausfuehren("insert into " + SCHEMA + ".mandant (code, name) values ('neu', 'N')");
            assertThat(wert("select wissen_freigeschaltet from " + SCHEMA + ".mandant where code = 'neu'"))
                    .as("neuer Mandant ohne Wissen").isEqualTo("f");

            liquibase(l -> l.rollback(1, null));
            assertThat(wert("select count(*) from information_schema.columns where table_schema = '" + SCHEMA
                    + "' and table_name = 'mandant' and column_name = 'wissen_freigeschaltet'")).isEqualTo("0");
            assertThat(wert("select count(*) from " + SCHEMA + ".mandant")).as("Rollback ohne Datenverlust")
                    .isEqualTo("3");

            liquibase(Liquibase::update);
            assertThat(wert("select string_agg(code || '=' || wissen_freigeschaltet, ',' order by code) from "
                    + SCHEMA + ".mandant")).isEqualTo("neu=false,urinet=true,vhf-gsk=false");
        } finally {
            ausfuehren("drop schema if exists " + SCHEMA + " cascade");
        }
        // das echte Register: Spalte vorhanden, Standard nein, Pflichtfeld
        assertThat(wert("select column_default || ',' || is_nullable from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'mandant' and column_name = 'wissen_freigeschaltet'"))
                .isEqualTo("false,NO");
    }

    private interface Schritt {
        void ausfuehren(Liquibase liquibase) throws Exception;
    }

    private void liquibase(Schritt schritt) throws Exception {
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("set search_path to " + SCHEMA);
            }
            Database ziel = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
            ziel.setDefaultSchemaName(SCHEMA);
            ziel.setLiquibaseSchemaName(SCHEMA);
            Liquibase liquibase = new Liquibase(CHANGESET, new ClassLoaderResourceAccessor(getClass().getClassLoader()),
                    ziel);
            schritt.ausfuehren(liquibase);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("reset search_path");
            }
        }
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute(sql);
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
