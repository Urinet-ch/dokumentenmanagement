package ch.urinet.archiv.mandant.adapter.out.persistence;

import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.StandardLockService;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Liquibase-Sperre für PostgreSQL mit Sitzungsbindung (design.md «Betrieb auf der VPS», Liquibase-Sperre): Zusätzlich
 * zur Tabellensperre {@code databasechangeloglock} hält die Migration eine Session-gebundene Sperre
 * ({@code pg_advisory_lock}) auf ihrer Verbindung; PostgreSQL gibt sie frei, sobald die Verbindung endet, auch wenn der
 * Prozess abstürzt. Wer die Sitzungssperre hält, ist die einzige Migration nach diesem Verfahren.
 * <p>
 * Ist die Tabellensperre trotzdem gesetzt, stammt sie von einem abgebrochenen Start oder von einem Prozess ohne dieses
 * Verfahren (älteres Release). Freigegeben wird sie nur, wenn ausser der eigenen keine Sitzung derselben Datenbankrolle
 * offen ist: Eine laufende Migration hält ihre Verbindung bis zum Ende offen. Sonst bleibt die Sperre unangetastet,
 * Liquibase wartet wie bisher, und das Log nennt einmal {@value #FEHLERCODE_BELEGT} mit Anleitung. Eine fremde aktive
 * Sperre wird nie gebrochen; zwei Migrationen laufen nie gleichzeitig.
 * <p>
 * Registriert über {@code META-INF/services/liquibase.lockservice.LockService}; gilt für {@code db/public} (Quarkus)
 * und für jedes Mandanten-Schema ({@link LiquibaseMandantDatenbank}).
 */
public class LiquibaseSitzungssperre extends StandardLockService {

    public static final String FEHLERCODE_VERWAIST = "LIQUIBASE_SPERRE_VERWAIST";
    public static final String FEHLERCODE_BELEGT = "LIQUIBASE_SPERRE_BELEGT";
    /** Erster Schlüssel der Sitzungssperre («LB»); der zweite ist der Hash der Sperrtabelle (je Schema eigene). */
    static final int NAMENSRAUM = 0x4C42;

    private static final Logger LOG = Logger.getLogger(LiquibaseSitzungssperre.class);

    private boolean sitzungssperre;
    private boolean zeileGehalten;
    private boolean belegtGemeldet;

    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(Database database) {
        return database instanceof PostgresDatabase;
    }

    @Override
    public boolean acquireLock() throws LockException {
        if (hasChangeLogLock) {
            return true;
        }
        boolean erhalten = false;
        try {
            if (!sitzungssperreVersuchen()) {
                return false;
            }
            erhalten = super.acquireLock() || verwaisteFreigeben() && super.acquireLock();
            zeileGehalten = erhalten;
            return erhalten;
        } catch (SQLException | DatabaseException e) {
            throw new LockException(e);
        } finally {
            if (!erhalten) {
                sitzungssperreLoesen();
            }
        }
    }

    /**
     * Gibt die Tabellensperre nur frei, wenn diese Instanz sie hält: Quarkus ruft nach {@code update} (das schon
     * freigibt) noch einmal {@code releaseLock}; ohne diese Bedingung setzte der zweite Aufruf eine inzwischen fremde
     * Sperre zurück.
     */
    @Override
    public void releaseLock() throws LockException {
        try {
            if (hasChangeLogLock || zeileGehalten) {
                super.releaseLock();
            }
        } finally {
            zeileGehalten = false;
            sitzungssperreLoesen();
        }
    }

    /** {@code release-locks} bleibt eine bewusste Handlung und gibt wie bisher ohne Bedingung frei. */
    @Override
    public void forceReleaseLock() throws LockException, DatabaseException {
        init();
        zeileGehalten = true;
        releaseLock();
    }

    private boolean sitzungssperreVersuchen() throws SQLException, DatabaseException {
        if (sitzungssperre) {
            return true;
        }
        Connection verbindung = verbindung();
        try (PreparedStatement abfrage = verbindung.prepareStatement("select pg_try_advisory_lock(?, hashtext(?))")) {
            abfrage.setInt(1, NAMENSRAUM);
            abfrage.setString(2, sperrtabelle());
            try (ResultSet zeile = abfrage.executeQuery()) {
                sitzungssperre = zeile.next() && zeile.getBoolean(1);
            }
        } finally {
            database.rollback();
        }
        return sitzungssperre;
    }

    private void sitzungssperreLoesen() {
        if (!sitzungssperre) {
            return;
        }
        try {
            Connection verbindung = verbindung();
            try (PreparedStatement abfrage = verbindung.prepareStatement("select pg_advisory_unlock(?, hashtext(?))")) {
                abfrage.setInt(1, NAMENSRAUM);
                abfrage.setString(2, sperrtabelle());
                abfrage.execute();
            } finally {
                database.rollback();
            }
        } catch (SQLException | DatabaseException | RuntimeException e) {
            // Endet die Verbindung, gibt PostgreSQL die Sitzungssperre selbst frei
            LOG.warnf("Liquibase: Sitzungssperre nicht gelöst, ursache=%s", e.getClass().getName());
        } finally {
            sitzungssperre = false;
        }
    }

    /**
     * Aufgerufen mit gehaltener Sitzungssperre, wenn die Tabellensperre gesetzt ist. Gibt sie frei, wenn keine andere
     * Sitzung derselben Rolle offen ist (Vergleich auf den gelesenen Zeitpunkt, damit nur genau diese Sperre fällt).
     */
    private boolean verwaisteFreigeben() throws SQLException, DatabaseException {
        Connection verbindung = verbindung();
        String tabelle = sperrtabelle();
        try {
            Timestamp gesetzt;
            try (PreparedStatement abfrage = verbindung.prepareStatement(
                    "select lockgranted from " + tabelle + " where id = 1 and locked")) {
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        // inzwischen frei: noch einmal versuchen
                        return true;
                    }
                    gesetzt = zeile.getTimestamp(1);
                }
            }
            int offen = andereSitzungen(verbindung);
            if (offen > 0) {
                if (!belegtGemeldet) {
                    belegtGemeldet = true;
                    LOG.errorf("Fehler %s: fehlercode=%s, Liquibase-Sperre in %s gesetzt seit %s, %d weitere Sitzung(en)"
                                    + " der Migrationsrolle offen; Liquibase wartet und bricht nach der Wartezeit ab."
                                    + " Läuft eine Migration (zweite API, Admin-Kommando), abwarten. Sonst die offene"
                                    + " Sitzung beenden; der nächste Start gibt die verwaiste Sperre dann selbst frei."
                                    + " Die Sperre nie von Hand löschen, solange eine Sitzung offen ist.",
                            UUID.randomUUID(), FEHLERCODE_BELEGT, tabelle, gesetzt, offen);
                }
                return false;
            }
            int frei;
            try (PreparedStatement freigeben = verbindung.prepareStatement("update " + tabelle
                    + " set locked = false, lockgranted = null, lockedby = null"
                    + " where id = 1 and locked and lockgranted is not distinct from ?")) {
                freigeben.setTimestamp(1, gesetzt);
                frei = freigeben.executeUpdate();
            }
            database.commit();
            if (frei == 1) {
                LOG.warnf("fehlercode=%s: verwaiste Liquibase-Sperre in %s (gesetzt seit %s) freigegeben, keine andere"
                        + " Sitzung der Migrationsrolle offen", FEHLERCODE_VERWAIST, tabelle, gesetzt);
            }
            return true;
        } finally {
            database.rollback();
        }
    }

    private static int andereSitzungen(Connection verbindung) throws SQLException {
        try (PreparedStatement abfrage = verbindung.prepareStatement("select count(*) from pg_stat_activity"
                + " where datname = current_database() and usename = session_user and pid <> pg_backend_pid()");
             ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            return zeile.getInt(1);
        }
    }

    private String sperrtabelle() {
        return database.escapeTableName(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName(),
                database.getDatabaseChangeLogLockTableName());
    }

    private Connection verbindung() throws DatabaseException {
        if (database.getConnection() instanceof JdbcConnection jdbc) {
            return jdbc.getUnderlyingConnection();
        }
        throw new DatabaseException("Liquibase-Sitzungssperre braucht eine JDBC-Verbindung");
    }
}
