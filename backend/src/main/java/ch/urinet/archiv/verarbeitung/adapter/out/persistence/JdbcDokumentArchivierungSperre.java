package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.DokumentArchivierungSperre;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Hält eine sitzungsgebundene PostgreSQL-Sperre, solange ein Dokument archiviert wird. */
@ApplicationScoped
public class JdbcDokumentArchivierungSperre implements DokumentArchivierungSperre {

    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final long WARTEZEIT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final String VERSUCH = "select pg_try_advisory_lock(hashtextextended(?, 0))";
    private static final String FREIGEBEN = "select pg_advisory_unlock(hashtextextended(?, 0))";

    private final Instance<AgroalDataSource> datenquelle;
    private final long wartezeitNanos;

    @Inject
    public JdbcDokumentArchivierungSperre(Instance<AgroalDataSource> datenquelle) {
        this(datenquelle, WARTEZEIT_NANOS);
    }

    JdbcDokumentArchivierungSperre(Instance<AgroalDataSource> datenquelle, long wartezeitNanos) {
        this.datenquelle = datenquelle;
        this.wartezeitNanos = wartezeitNanos;
    }

    @Override
    public Sperre sperren(MandantCode mandant, UUID dokumentId) throws InterruptedException {
        String schluessel = "dokument-archivierung:" + mandant.wert() + ":" + dokumentId;
        Connection verbindung = null;
        try {
            verbindung = datenquelle.get().getConnection();
            verbindung.setAutoCommit(true);
            long beginn = System.nanoTime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Warten auf Dokumentarchivierungssperre unterbrochen");
                }
                if (System.nanoTime() - beginn >= wartezeitNanos) {
                    throw new SQLException("Wartezeit für Dokumentarchivierungssperre überschritten");
                }
                if (versuchen(verbindung, schluessel)) {
                    Connection gehalten = verbindung;
                    return () -> freigeben(gehalten, schluessel);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            verbindungSchliessen(verbindung, e);
            Thread.currentThread().interrupt();
            throw e;
        } catch (SQLException e) {
            verbindungAbbrechen(verbindung, e);
            throw new IllegalStateException("Dokumentarchivierungssperre nicht gesetzt", e);
        }
    }

    private static boolean versuchen(Connection verbindung, String schluessel) throws SQLException {
        try (PreparedStatement abfrage = verbindung.prepareStatement(VERSUCH)) {
            abfrage.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            abfrage.setString(1, schluessel);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() && zeile.getBoolean(1);
            }
        }
    }

    private static void freigeben(Connection verbindung, String schluessel) {
        boolean freigegeben;
        try (PreparedStatement abfrage = verbindung.prepareStatement(FREIGEBEN)) {
            abfrage.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            abfrage.setString(1, schluessel);
            try (ResultSet zeile = abfrage.executeQuery()) {
                freigegeben = zeile.next() && zeile.getBoolean(1);
            }
        } catch (SQLException e) {
            verbindungAbbrechen(verbindung, e);
            throw new IllegalStateException("Dokumentarchivierungssperre nicht freigegeben", e);
        }
        try {
            verbindung.close();
        } catch (SQLException e) {
            verbindungAbbrechen(verbindung, e);
            throw new IllegalStateException("Verbindung nach Dokumentarchivierung nicht geschlossen", e);
        }
        if (!freigegeben) {
            throw new IllegalStateException("Dokumentarchivierungssperre nicht gehalten");
        }
    }

    private static void verbindungSchliessen(Connection verbindung, Throwable ursache) {
        if (verbindung != null) {
            try {
                verbindung.close();
            } catch (SQLException e) {
                ursache.addSuppressed(e);
            }
        }
    }

    private static void verbindungAbbrechen(Connection verbindung, Throwable ursache) {
        if (verbindung != null) {
            try {
                verbindung.abort(Runnable::run);
            } catch (SQLException e) {
                ursache.addSuppressed(e);
            }
            verbindungSchliessen(verbindung, ursache);
        }
    }
}
