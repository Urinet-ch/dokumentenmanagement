package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Antrag;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Art;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Status;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcVerwaltungAntragTest {

    @Inject VerwaltungAntragSpeicher speicher;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenquelle;

    @Test
    void antraegeSindGetrenntUndEntscheideAtomar() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, code);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
        Instant zeit = Instant.parse("2026-01-01T10:00:00Z");
        var antrag = new Antrag(UUID.randomUUID(), Art.INHABER_ROLLE, "ziel", Rolle.INHABER, null,
                "antragsteller", zeit, zeit.plusSeconds(60), Status.OFFEN, null, null);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> thread.submit(() -> speicher.lesen(antrag.id())).get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            in(thread, "urinet", () -> {
                speicher.anlegen(antrag);
                return null;
            });
            assertThat(in(thread, "urinet", () -> speicher.lesen(antrag.id()))).contains(antrag);
            assertThat(in(thread, "vhf-gsk", () -> speicher.lesen(antrag.id()))).isEmpty();
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "antragsteller", zeit))).isFalse();
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit.minusSeconds(1)))).isFalse();
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit.plusSeconds(60)))).isFalse();
            assertThatThrownBy(() -> in(thread, "urinet", () -> {
                assertThat(speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit)).isTrue();
                throw new IllegalStateException("synthetischer Fehler");
            })).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(in(thread, "urinet", () -> speicher.lesen(antrag.id()))).contains(antrag);
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.ABGELAUFEN, null, zeit.plusSeconds(60)))).isTrue();
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit))).isFalse();
        }
    }

    private <T> T in(java.util.concurrent.ExecutorService thread, String code, Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(code), () ->
                QuarkusTransaction.requiringNew().call(arbeit))).get(30, TimeUnit.SECONDS);
    }
}
