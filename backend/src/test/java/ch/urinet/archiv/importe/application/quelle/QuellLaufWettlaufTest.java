package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.QuellenTyp;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wettlauf zwischen Upload und Abschluss eines Quelllaufs (Spec 09, 10.3; Review zu #750): Der Upload sperrt den Lauf
 * bis zu seinem Commit, der Abschluss wartet und zählt den Upload mit; ein Upload nach dem Abschluss wird mit
 * {@code STATUS_KONFLIKT} abgelehnt und verbucht nichts. Synthetische Quelle im Mandanten {@code vhf-gsk}.
 */
@QuarkusTest
class QuellLaufWettlaufTest {

    private static final MandantCode MANDANT = new MandantCode("vhf-gsk");

    @Inject QuellLaufZuordnung zuordnung;
    @Inject QuellenVerwaltung verwaltung;
    @Inject QuellenRegister register;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;

    @Test
    void abschlussWartetAufDenUploadUndSpaeterAbgelehnt() throws Exception {
        if (!imRegister()) {
            provisionierung.anlegen(MANDANT.wert(), null, null);
        }
        String code = "wettlauf-" + UUID.randomUUID().toString().substring(0, 8);
        try (ExecutorService upload = Executors.newSingleThreadExecutor();
             ExecutorService abschluss = Executors.newSingleThreadExecutor()) {
            in(upload, () -> register.anlegen(UUID.randomUUID(), code, "Wettlauf", QuellenTyp.ORDNER, "{}",
                    Optional.empty())).get(30, TimeUnit.SECONDS);
            QuellLauf lauf = in(upload, () -> verwaltung.laufStarten(code, "ECHT", Optional.of("synthetisch")))
                    .get(30, TimeUnit.SECONDS);

            CountDownLatch gesperrt = new CountDownLatch(1);
            CountDownLatch weiter = new CountDownLatch(1);
            Future<Void> hochladen = in(upload, () -> {
                QuellLaufZuordnung.Ziel ziel = zuordnung.bestimmen(null, lauf.id().toString()).orElseThrow();
                gesperrt.countDown();
                assertThat(weiter.await(30, TimeUnit.SECONDS)).isTrue();
                zuordnung.verbuchen(ziel, List.of(new QuellLaufZuordnung.Eintrag(
                        QuellLaufZuordnung.Ergebnis.DUBLETTE, Optional.empty())));
                return null;
            });
            assertThat(gesperrt.await(30, TimeUnit.SECONDS)).isTrue();
            Future<QuellLauf> abschliessen = in(abschluss, () -> verwaltung.laufAbschliessen(lauf.id(),
                    Optional.empty(), 0, List.of(), false));

            // solange der Upload offen ist, wartet der Abschluss auf die Zeilensperre
            assertThatThrownBy(() -> abschliessen.get(700, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            weiter.countDown();
            hochladen.get(30, TimeUnit.SECONDS);
            QuellLauf beendet = abschliessen.get(30, TimeUnit.SECONDS);
            assertThat(beendet.status()).isEqualTo(QuellLaufStatus.FERTIG);
            assertThat(beendet.zaehler()).isEqualTo(new Zaehler(1, 0, 1, 0, 0, 0));

            Future<Void> spaet = in(upload, () -> {
                zuordnung.bestimmen(null, lauf.id().toString());
                return null;
            });
            assertThatThrownBy(() -> spaet.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(FachFehler.class,
                            f -> assertThat(f.code()).isEqualTo(Fehlercode.STATUS_KONFLIKT));
            assertThat(in(upload, () -> register.verbuchen(lauf.id(), 1, 0, 0)).get(30, TimeUnit.SECONDS))
                    .isFalse();
            assertThat(in(upload, () -> register.lauf(lauf.id(), false)).get(30, TimeUnit.SECONDS).orElseThrow()
                    .zaehler()).isEqualTo(new Zaehler(1, 0, 1, 0, 0, 0));
        } finally {
            aufraeumen(code);
        }
    }

    private <T> Future<T> in(ExecutorService thread, Callable<T> arbeit) {
        return thread.submit(() -> kontext.mit(MANDANT, () -> QuarkusTransaction.requiringNew().call(arbeit)));
    }

    private boolean imRegister() throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, MANDANT.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    private void aufraeumen(String code) throws Exception {
        try (Connection verbindung = datenbank.getConnection()) {
            for (String sql : List.of("update m_vhf_gsk.quelle set letzter_lauf = null where code = ?",
                    "delete from m_vhf_gsk.quelle_lauf where quelle_id in (select id from m_vhf_gsk.quelle "
                            + "where code = ?)", "delete from m_vhf_gsk.quelle where code = ?")) {
                try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
                    befehl.setString(1, code);
                    befehl.executeUpdate();
                }
            }
        }
    }
}
