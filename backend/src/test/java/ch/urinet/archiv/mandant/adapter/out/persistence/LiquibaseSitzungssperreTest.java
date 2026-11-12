package ch.urinet.archiv.mandant.adapter.out.persistence;

import io.quarkus.test.junit.QuarkusTest;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Liquibase-Sperre mit Sitzungsbindung (design.md «Betrieb auf der VPS», Liquibase-Sperre; Deploy 12.11.2026): eine
 * verwaiste Tabellensperre gibt der nächste Start frei, eine Sperre mit offener Sitzung der Migrationsrolle nie, und
 * zwei Migrationen laufen nie gleichzeitig. Eigene Rolle und eigenes Schema, damit die Sitzungen der Anwendung nicht
 * mitzählen.
 */
@QuarkusTest
class LiquibaseSitzungssperreTest {

    private static final String ROLLE = "lb_sperre_probe";
    private static final String PASSWORT = "probe-nur-test";
    private static final String SCHEMA = "lb_sperre";
    private static final String TABELLE = SCHEMA + ".databasechangeloglock";

    @BeforeEach
    void vorbereiten() throws SQLException {
        try (Connection admin = verbinden(); Statement befehl = admin.createStatement()) {
            befehl.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + ROLLE + "') then"
                    + " create role " + ROLLE + " login password '" + PASSWORT + "'; end if; end $$");
            befehl.execute("drop schema if exists " + SCHEMA + " cascade");
            befehl.execute("create schema " + SCHEMA + " authorization " + ROLLE);
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        try (Connection admin = verbinden(); Statement befehl = admin.createStatement()) {
            befehl.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void kuenstlicheSperreBleibtSolangeEineSitzungDerRolleOffenIst() throws Exception {
        try (Connection einrichten = alsRolle()) {
            LockService dienst = sperre(einrichten);
            assertThat(dienst.acquireLock()).isTrue();
            dienst.releaseLock();
        }
        warteBisSitzungen(0);
        try (Connection admin = verbinden(); Statement befehl = admin.createStatement()) {
            befehl.execute("update " + TABELLE + " set locked = true, lockgranted = now(), lockedby = 'kuenstlich'"
                    + " where id = 1");
        }

        try (Connection start = alsRolle()) {
            LockService dienst = sperre(start);
            try (Connection lebend = alsRolle()) {
                // Eine offene Sitzung der Rolle könnte eine laufende Migration (älteres Release) sein
                assertThat(dienst.acquireLock()).isFalse();
                assertThat(gesperrtVon()).isEqualTo("kuenstlich");
            }
            warteBisSitzungen(1);

            assertThat(dienst.acquireLock()).isTrue();
            assertThat(gesperrtVon()).isNotNull().isNotEqualTo("kuenstlich");
            dienst.releaseLock();
            assertThat(gesperrtVon()).isNull();
        }
    }

    @Test
    void nieZweiMigrationenGleichzeitigUndAbsturzHinterlaesstKeineSperre() throws Exception {
        try (Connection zweite = alsRolle()) {
            LockService zweiterStart = sperre(zweite);
            try (Connection erste = alsRolle()) {
                LockService ersterStart = sperre(erste);
                assertThat(ersterStart.acquireLock()).isTrue();
                assertThat(zweiterStart.acquireLock()).isFalse();
                // Absturz: Verbindung endet ohne releaseLock, die Tabellensperre bleibt stehen
            }
            assertThat(gesperrtVon()).isNotNull();
            warteBisSitzungen(1);

            assertThat(zweiterStart.acquireLock()).isTrue();
            zweiterStart.releaseLock();
            assertThat(gesperrtVon()).isNull();
        }
    }

    @Test
    void zweitesReleaseLockLaesstFremdeSperreStehen() throws Exception {
        try (Connection erste = alsRolle(); Connection zweite = alsRolle()) {
            LockService ersterStart = sperre(erste);
            LockService zweiterStart = sperre(zweite);
            assertThat(ersterStart.acquireLock()).isTrue();
            ersterStart.releaseLock();
            assertThat(zweiterStart.acquireLock()).isTrue();
            String gesperrt = gesperrtVon();

            // wie Quarkus nach liquibase.update: ein zweites releaseLock
            ersterStart.releaseLock();

            assertThat(gesperrtVon()).isEqualTo(gesperrt).isNotNull();
            zweiterStart.releaseLock();
        }
    }

    private static LockService sperre(Connection verbindung) throws Exception {
        Database datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(SCHEMA);
        datenbank.setLiquibaseSchemaName(SCHEMA);
        LockService dienst = LockServiceFactory.getInstance().getLockService(datenbank);
        assertThat(dienst).as("über META-INF/services registriert").isInstanceOf(LiquibaseSitzungssperre.class);
        dienst.setChangeLogLockWaitTime(0);
        return dienst;
    }

    private static String gesperrtVon() throws SQLException {
        try (Connection admin = verbinden(); Statement befehl = admin.createStatement();
             ResultSet zeile = befehl.executeQuery("select lockedby from " + TABELLE + " where id = 1 and locked")) {
            return zeile.next() ? zeile.getString(1) : null;
        }
    }

    /** Eine beendete Verbindung verschwindet nicht sofort aus {@code pg_stat_activity}. */
    private static void warteBisSitzungen(int anzahl) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (Connection admin = verbinden(); Statement befehl = admin.createStatement();
                 ResultSet zeile = befehl.executeQuery("select count(*) from pg_stat_activity where usename = '"
                         + ROLLE + "'")) {
                zeile.next();
                if (zeile.getInt(1) <= anzahl) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Sitzungen der Rolle " + ROLLE + " nicht beendet");
    }

    private static Connection alsRolle() throws SQLException {
        return DriverManager.getConnection(url(), ROLLE, PASSWORT);
    }

    private static Connection verbinden() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(url(), config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static String url() {
        return ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
    }
}
