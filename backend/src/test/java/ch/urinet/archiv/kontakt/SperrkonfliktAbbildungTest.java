package ch.urinet.archiv.kontakt;

import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAbbildung;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Reproduziert den Deadlock zwischen Zusammenführen (Kontakt, dann Dokument) und Bestätigen (Dokument, dann Kontakt). */
@Tag("start-1")
@TestProfile(SperrkonfliktAbbildungTest.SperrtestProfil.class)
@QuarkusTest
class SperrkonfliktAbbildungTest {

    public static class SperrtestProfil implements QuarkusTestProfile {
    }

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final ProblemAbbildung ABBILDUNG = new ProblemAbbildung();

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    private final ExecutorService transaktionen = Executors.newFixedThreadPool(2);
    private UUID quelle;
    private UUID ziel;
    private UUID dokument;

    @AfterEach
    void aufraeumen() throws SQLException {
        transaktionen.shutdownNow();
        if (dokument == null) {
            return;
        }
        try (Connection verbindung = datenbank.getConnection()) {
            loeschen(verbindung, "delete from " + URINET.schema() + ".dokument where id = ?", dokument);
            loeschen(verbindung, "delete from " + URINET.schema() + ".kontakt where id = any(?)",
                    verbindung.createArrayOf("uuid", new Object[] {quelle, ziel}));
        }
    }

    @Test
    void parallelesZusammenfuehrenUndBestaetigenLiefert409Statt500() throws Exception {
        datenAnlegen();
        CountDownLatch ersteSperreJeTransaktion = new CountDownLatch(2);

        var zusammenfuehren = transaktionen.submit(() -> transaktion(true, ersteSperreJeTransaktion));
        var bestaetigen = transaktionen.submit(() -> transaktion(false, ersteSperreJeTransaktion));
        Ergebnis merge = zusammenfuehren.get(6, TimeUnit.SECONDS);
        Ergebnis bestaetigung = bestaetigen.get(6, TimeUnit.SECONDS);

        assertThat(java.util.List.of(merge.erfolgreich(), bestaetigung.erfolgreich()))
                .containsExactlyInAnyOrder(true, false);
        Ergebnis fehlgeschlagen = merge.erfolgreich() ? bestaetigung : merge;
        assertThat(Ursachen.enthaeltSqlState(fehlgeschlagen.fehler(), "40P01")).isTrue();
        try (var antwort = ABBILDUNG.unerwartet(new PersistenceException("synthetischer Transaktionsfehler",
                fehlgeschlagen.fehler()))) {
            assertThat(antwort.getStatus()).isEqualTo(409);
            assertThat(antwort.getEntity()).asString().contains("\"fehlercode\":\"STATUS_KONFLIKT\"");
        }

        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select d.partner_id, k.aktiv, (select aktiv from " + URINET.schema()
                        + ".kontakt where id = ?) as quelle_aktiv from " + URINET.schema()
                        + ".dokument d join " + URINET.schema() + ".kontakt k on k.id = d.partner_id where d.id = ?")) {
            abfrage.setObject(1, quelle);
            abfrage.setObject(2, dokument);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getBoolean("aktiv")).isTrue();
                assertThat(zeile.getObject("partner_id", UUID.class))
                        .isEqualTo(merge.erfolgreich() ? ziel : quelle);
                assertThat(zeile.getBoolean("quelle_aktiv")).isEqualTo(!merge.erfolgreich());
            }
        }
    }

    private Ergebnis transaktion(boolean zusammenfuehren, CountDownLatch ersteSperreJeTransaktion) {
        try (Connection verbindung = datenbank.getConnection()) {
            verbindung.setAutoCommit(false);
            try {
                try (Statement anweisung = verbindung.createStatement()) {
                    anweisung.execute("set local lock_timeout = '5s'");
                }
                if (zusammenfuehren) {
                    sperrenKontakt(verbindung);
                } else {
                    sperrenDokument(verbindung);
                }
                ersteSperreJeTransaktion.countDown();
                if (!ersteSperreJeTransaktion.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Parallele Transaktion hat ihre erste Sperre nicht erreicht");
                }
                if (zusammenfuehren) {
                    sperrenDokument(verbindung);
                    aktualisieren(verbindung, "update " + URINET.schema()
                            + ".dokument set partner_id = ? where id = ?", ziel, dokument);
                    aktualisieren(verbindung, "update " + URINET.schema()
                            + ".kontakt set aktiv = false, inaktiv_seit = now() where id = ?", quelle);
                } else {
                    sperrenKontaktLesend(verbindung);
                    aktualisieren(verbindung, "update " + URINET.schema()
                            + ".dokument set status = 'ARCHIVIERUNG' where id = ?", dokument);
                }
                verbindung.commit();
                return new Ergebnis(true, null);
            } catch (SQLException e) {
                zurueckrollen(verbindung, e);
                return new Ergebnis(false, e);
            } catch (InterruptedException e) {
                zurueckrollen(verbindung, e);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Testtransaktion unterbrochen", e);
            }
        } catch (SQLException e) {
            return new Ergebnis(false, e);
        }
    }

    private void datenAnlegen() throws SQLException {
        if (!mandantVorhanden()) {
            provisionierung.anlegen(URINET.wert(), null, null);
        }
        quelle = UUID.randomUUID();
        ziel = UUID.randomUUID();
        dokument = UUID.randomUUID();
        try (Connection verbindung = datenbank.getConnection()) {
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + URINET.schema()
                    + ".kontakt (id, typ, name, name_normalisiert, quelle) values (?, 'ORGANISATION', ?, ?, 'TEST')")) {
                kontaktEinfuegen(einfuegen, quelle, "Sperrprobe Quelle");
                kontaktEinfuegen(einfuegen, ziel, "Sperrprobe Ziel");
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + URINET.schema()
                    + ".dokument (id, quelle, erstellt_von, status, partner_id, partner_name) "
                    + "values (?, 'UPLOAD', 'synthetisch', 'EINGANGSKORB', ?, 'Sperrprobe Quelle')")) {
                einfuegen.setObject(1, dokument);
                einfuegen.setObject(2, quelle);
                einfuegen.executeUpdate();
            }
        }
    }

    private boolean mandantVorhanden() throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, URINET.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    private static void kontaktEinfuegen(PreparedStatement einfuegen, UUID id, String name) throws SQLException {
        einfuegen.setObject(1, id);
        einfuegen.setString(2, name);
        einfuegen.setString(3, name.toLowerCase(java.util.Locale.ROOT));
        einfuegen.executeUpdate();
    }

    private void sperrenKontakt(Connection verbindung) throws SQLException {
        sperren(verbindung, "select id from " + URINET.schema() + ".kontakt where id = ? for update", quelle);
    }

    private void sperrenKontaktLesend(Connection verbindung) throws SQLException {
        sperren(verbindung, "select id from " + URINET.schema() + ".kontakt where id = ? for share", quelle);
    }

    private void sperrenDokument(Connection verbindung) throws SQLException {
        sperren(verbindung, "select id from " + URINET.schema() + ".dokument where id = ? for update", dokument);
    }

    private static void sperren(Connection verbindung, String sql, UUID id) throws SQLException {
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    throw new SQLException("Synthetische Sperrzeile fehlt");
                }
            }
        }
    }

    private static void aktualisieren(Connection verbindung, String sql, Object... werte) throws SQLException {
        try (PreparedStatement update = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                update.setObject(i + 1, werte[i]);
            }
            update.executeUpdate();
        }
    }

    private static void loeschen(Connection verbindung, String sql, Object wert) throws SQLException {
        try (PreparedStatement update = verbindung.prepareStatement(sql)) {
            update.setObject(1, wert);
            update.executeUpdate();
        }
    }

    private static void zurueckrollen(Connection verbindung, Throwable fehler) {
        try {
            verbindung.rollback();
        } catch (SQLException rollback) {
            fehler.addSuppressed(rollback);
        }
    }

    private record Ergebnis(boolean erfolgreich, Throwable fehler) {
    }
}
