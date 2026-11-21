package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ch.urinet.archiv.benutzer.application.VerwaltungGrenzen.Art;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcVerwaltungGrenzenTest {

    @Inject VerwaltungGrenzen grenzen;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenquelle;

    @Test
    void grenzenZaehlenAbsichtenUndTrennenMandanten() throws Exception {
        mandantenVorbereiten();
        String akteur = UUID.randomUUID().toString();
        UUID ziel = UUID.randomUUID();
        try (var thread = Executors.newSingleThreadExecutor()) {
            in(thread, "urinet", () -> {
                for (int i = 0; i < 29; i++) {
                    protokoll("urinet", akteur, "BENUTZER_AENDERN", "ANGEFRAGT", "SPERREN", ziel, 0);
                }
                protokoll("urinet", akteur, "BENUTZER_AENDERN", "ABGESCHLOSSEN", "SPERREN", ziel, 0);
                protokoll("urinet", akteur, "BENUTZER_AENDERN", "ANGEFRAGT", "SPERREN", ziel, 7200);
                grenzen.pruefen(akteur, Art.BENUTZER, ziel);
                protokoll("urinet", akteur, "BENUTZER_AENDERN", "ANGEFRAGT", "SPERREN", ziel, 0);
                return null;
            });
            assertThatThrownBy(() -> in(thread, "urinet", () -> {
                grenzen.pruefen(akteur, Art.BENUTZER, ziel);
                return null;
            })).hasRootCauseInstanceOf(ZuVieleAnfragen.class);
            in(thread, "vhf-gsk", () -> {
                grenzen.pruefen(akteur, Art.BENUTZER, ziel);
                return null;
            });

            String anderer = UUID.randomUUID().toString();
            in(thread, "urinet", () -> {
                for (int i = 0; i < 10; i++) {
                    protokoll("urinet", UUID.randomUUID().toString(), "BENUTZER_ANLEGEN",
                            "ANGEFRAGT", "ANLEGEN", null, 0);
                }
                protokoll("urinet", anderer, "BENUTZER_ANLEGEN", "ABGESCHLOSSEN", "ANLEGEN", ziel, 0);
                protokoll("urinet", anderer, "BENUTZER_AENDERN", "ANGEFRAGT", "EINLADUNG_ERNEUT", ziel, 0);
                protokoll("urinet", anderer, "BENUTZER_AENDERN", "ANGEFRAGT", "ZUGANG_ZURUECKSETZEN", ziel, 0);
                UUID altesZiel = UUID.randomUUID();
                for (int i = 0; i < 3; i++) {
                    protokoll("urinet", anderer, "BENUTZER_AENDERN", "ANGEFRAGT", "EINLADUNG_ERNEUT",
                            altesZiel, 25 * 3600);
                }
                grenzen.pruefen(anderer, Art.EMAIL, altesZiel);
                return null;
            });
            for (Art art : List.of(Art.ANLEGEN, Art.EMAIL)) {
                assertThatThrownBy(() -> in(thread, "urinet", () -> {
                    grenzen.pruefen(anderer, art, ziel);
                    return null;
                })).hasRootCauseInstanceOf(ZuVieleAnfragen.class);
                in(thread, "vhf-gsk", () -> {
                    grenzen.pruefen(anderer, art, ziel);
                    return null;
                });
            }
            UUID verwaist = UUID.randomUUID();
            in(thread, "urinet", () -> {
                protokoll("urinet", anderer, "BENUTZER_ANLEGEN", "UNKLAR", "ANLEGEN", verwaist, 0);
                protokoll("urinet", anderer, "BENUTZER_ANLEGEN", "FEHLGESCHLAGEN", "ANLEGEN", verwaist, 0);
                protokoll("urinet", anderer, "BENUTZER_ANLEGEN", "ABGESCHLOSSEN", "ANLEGEN", verwaist, 0);
                return null;
            });
            assertThatThrownBy(() -> in(thread, "urinet", () -> {
                grenzen.pruefen(anderer, Art.EMAIL, verwaist);
                return null;
            })).hasRootCauseInstanceOf(ZuVieleAnfragen.class);
            in(thread, "urinet", () -> {
                for (int i = 0; i < 60; i++) {
                    protokoll("urinet", UUID.randomUUID().toString(), "KONFIGURATION_AENDERN",
                            null, null, null, 0);
                }
                return null;
            });
            assertThatThrownBy(() -> in(thread, "urinet", () -> {
                grenzen.pruefen(anderer, Art.EINSTELLUNG, null);
                return null;
            })).hasRootCauseInstanceOf(ZuVieleAnfragen.class);
        }
    }

    @Test
    void gleichzeitigePruefungenKoennenDieGrenzeNichtUeberschreiten() throws Exception {
        mandantenVorbereiten();
        String akteur = UUID.randomUUID().toString();
        try (var threads = Executors.newFixedThreadPool(2)) {
            in(threads, "urinet", () -> {
                for (int i = 0; i < 29; i++) {
                    protokoll("urinet", akteur, "BENUTZER_AENDERN", "ANGEFRAGT", "SPERREN", null, 0);
                }
                return null;
            });
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> versuch = () -> kontext.mit(new MandantCode("urinet"), () ->
                    QuarkusTransaction.requiringNew().call(() -> {
                        start.await();
                        try {
                            grenzen.pruefen(akteur, Art.BENUTZER, null);
                        } catch (ZuVieleAnfragen e) {
                            assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.VERWALTUNG);
                            assertThat(e.wartezeitSekunden()).isBetween(1L, 3600L);
                            return false;
                        }
                        protokoll("urinet", akteur, "BENUTZER_AENDERN", "ANGEFRAGT", "SPERREN", null, 0);
                        return true;
                    }));
            var erster = threads.submit(versuch);
            var zweiter = threads.submit(versuch);
            start.countDown();
            assertThat(List.of(erster.get(30, TimeUnit.SECONDS), zweiter.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private void mandantenVorbereiten() throws Exception {
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
    }

    private void protokoll(String mandant, String akteur, String aktion, String phase, String vorgang,
                           UUID ziel, int alterSekunden) throws Exception {
        String schema = mandant.equals("urinet") ? "m_urinet" : "m_vhf_gsk";
        String details = "{\"phase\":\"" + phase + "\",\"vorgang\":\"" + vorgang
                + "\",\"ziel\":\"" + ziel + "\"}";
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                     + ".zugriff_protokoll (zeit, benutzer, aktion, details) "
                     + "values (clock_timestamp() - (? * interval '1 second'), ?, ?, cast(? as jsonb))")) {
            einfuegen.setInt(1, alterSekunden);
            einfuegen.setString(2, akteur);
            einfuegen.setString(3, aktion);
            einfuegen.setString(4, details);
            einfuegen.executeUpdate();
        }
    }

    private <T> T in(ExecutorService thread, String code, Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(code), () ->
                QuarkusTransaction.requiringNew().call(arbeit))).get(30, TimeUnit.SECONDS);
    }
}
