package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.BenutzerStatusSpeicher;
import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcBenutzerStatusTest {

    @Inject BenutzerStatusSpeicher speicher;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenquelle;

    @Test
    void statusIstMandantenbezogenUndRolltMitDerTransaktionZurueck() throws Exception {
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
        String benutzer = UUID.randomUUID().toString();
        var gesperrt = new BenutzerStatusSpeicher.Eintrag(BenutzerStatus.GESPERRT,
                Instant.parse("2026-01-01T10:00:00Z"), "Synthetischer Grund", "akteur");
        var deaktiviert = new BenutzerStatusSpeicher.Eintrag(BenutzerStatus.DEAKTIVIERT,
                Instant.parse("2026-01-02T10:00:00Z"), "Synthetischer Grund", "akteur");
        try (var thread = Executors.newSingleThreadExecutor()) {
            try {
                thread.submit(() -> {
                    assertThatThrownBy(() -> speicher.lesen(benutzer)).isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(() -> kontext.mit(new MandantCode("urinet"),
                            () -> speicher.setzen(benutzer, gesperrt))).isInstanceOf(IllegalStateException.class);
                }).get(30, TimeUnit.SECONDS);
                thread.submit(() -> kontext.mit(new MandantCode("urinet"), () ->
                        QuarkusTransaction.requiringNew().run(() -> speicher.setzen(benutzer, gesperrt))))
                        .get(30, TimeUnit.SECONDS);
                assertThat(in(thread, "vhf-gsk", () -> speicher.lesen(benutzer))).isEmpty();
                in(thread, "vhf-gsk", () -> {
                    speicher.setzen(benutzer, deaktiviert);
                    return null;
                });
                assertThat(in(thread, "urinet", () -> speicher.lesen(benutzer))).contains(gesperrt);
                assertThat(in(thread, "vhf-gsk", () -> speicher.lesen(benutzer))).contains(deaktiviert);

                assertThatThrownBy(() -> in(thread, "urinet", () -> {
                    speicher.setzen(benutzer, deaktiviert);
                    throw new IllegalStateException("synthetischer Fehler");
                })).hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(in(thread, "urinet", () -> speicher.lesen(benutzer))).contains(gesperrt);
            } finally {
                for (String mandant : List.of("urinet", "vhf-gsk")) {
                    in(thread, mandant, () -> speicher.entfernen(benutzer));
                }
            }
        }
        assertThat(gesperrt.toString()).doesNotContain("Synthetischer Grund", "akteur");
        assertThatThrownBy(() -> new BenutzerStatusSpeicher.Eintrag(BenutzerStatus.AKTIV,
                Instant.now(), "Synthetischer Grund", "akteur")).isInstanceOf(IllegalArgumentException.class);
    }

    private <T> T in(java.util.concurrent.ExecutorService thread, String code, Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(code), () ->
                QuarkusTransaction.requiringNew().call(arbeit))).get(30, TimeUnit.SECONDS);
    }
}
