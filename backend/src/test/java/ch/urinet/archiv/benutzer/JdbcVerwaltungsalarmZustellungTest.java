package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AbgleichAlarmQuelle;
import ch.urinet.archiv.verarbeitung.application.AlarmSpeicher;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcVerwaltungsalarmZustellungTest {
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject MandantProvisionierung provisionierung;
    @Inject AlarmSpeicher speicher;
    @Inject AbgleichAlarmQuelle quelle;
    @Inject AgroalDataSource datenquelle;

    @Test
    void verschwundenerBefundBleibtBisZurZustellungUndAbgelaufenerAnspruchIstErneuerbar() throws Exception {
        vorbereiten(URINET);
        Befund befund = new Befund(Befund.Quelle.ABGLEICH, UUID.randomUUID().toString(), "MEHRERE_ROLLEN");
        speicher.abgleichen(URINET, Befund.Quelle.ABGLEICH, List.of(befund));
        Instant alterAnspruch = speicher.beanspruchen(URINET, befund).orElseThrow();

        speicher.abgleichen(URINET, Befund.Quelle.ABGLEICH, List.of());
        assertThat(quelle.offene(URINET)).extracting(Befund::schluessel).contains(befund.schluessel());
        assertThat(speicher.beanspruchen(URINET, befund)).isEmpty();
        anspruchAblaufenLassen(befund);
        Instant neuerAnspruch = speicher.beanspruchen(URINET, befund).orElseThrow();
        speicher.freigeben(URINET, befund, alterAnspruch);
        assertThat(speicher.beanspruchen(URINET, befund)).isEmpty();
        assertThatThrownBy(() -> speicher.bestaetigen(URINET, befund, alterAnspruch))
                .isInstanceOf(IllegalStateException.class);
        speicher.bestaetigen(URINET, befund, neuerAnspruch);
        speicher.abgleichen(URINET, Befund.Quelle.ABGLEICH, List.of());
        assertThat(quelle.offene(URINET)).extracting(Befund::schluessel).doesNotContain(befund.schluessel());
    }

    @Test
    void konkurrierendeWorkerBeanspruchenJeMandantNurEinmal() throws Exception {
        vorbereiten(URINET);
        vorbereiten(VHF);
        Befund befund = new Befund(Befund.Quelle.VERWALTUNGSALARM, UUID.randomUUID().toString(),
                "INHABER_VERGEBEN");
        speicher.abgleichen(URINET, Befund.Quelle.VERWALTUNGSALARM, List.of(befund));
        speicher.abgleichen(VHF, Befund.Quelle.VERWALTUNGSALARM, List.of(befund));
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Optional<Instant>> eins = CompletableFuture.supplyAsync(() -> beanspruchen(start, befund));
        CompletableFuture<Optional<Instant>> zwei = CompletableFuture.supplyAsync(() -> beanspruchen(start, befund));
        start.countDown();
        List<Optional<Instant>> ansprueche = List.of(eins.get(30, TimeUnit.SECONDS), zwei.get(30, TimeUnit.SECONDS));
        assertThat(ansprueche)
                .filteredOn(Optional::isPresent).hasSize(1);
        assertThat(speicher.beanspruchen(VHF, befund)).isPresent();
        Instant anspruch = ansprueche.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        speicher.bestaetigen(URINET, befund, anspruch);
        assertThat(quelle.offene(URINET)).extracting(Befund::schluessel).doesNotContain(befund.schluessel());
        assertThat(quelle.offene(VHF)).extracting(Befund::schluessel).contains(befund.schluessel());
    }

    private Optional<Instant> beanspruchen(CountDownLatch start, Befund befund) {
        try {
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Start der Test-Worker überschritten");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test-Worker unterbrochen", e);
        }
        return speicher.beanspruchen(URINET, befund);
    }

    private void anspruchAblaufenLassen(Befund befund) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(
                     "update m_urinet.alarm set anspruch_bis = clock_timestamp() - interval '1 second' "
                             + "where schluessel = ?")) {
            aendern.setString(1, befund.schluessel());
            assertThat(aendern.executeUpdate()).isEqualTo(1);
        }
    }

    private void vorbereiten(MandantCode code) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(code.wert(), null, null);
                }
            }
        }
    }
}
