package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schleifenschutz der Manifest-Planung (Prod-Fix 14.11.): Ein Neustart nach einem Fehlschlag reiht nicht sofort
 * dieselbe Arbeit wieder ein, ein offener Auftrag wird nicht verdoppelt, der Zeitplan-Lauf bleibt unabhängig.
 */
@QuarkusTest
class ManifestPlanenTest {

    @Inject
    ManifestPlanen planen;

    @Inject
    MandantRegister register;

    @Inject
    AuftragTestHilfe hilfe;

    private String mandant;

    @BeforeEach
    void vorbereiten() throws SQLException {
        hilfe.vorbereiten();
        hilfe.ausfuehren("delete from public.auftrag where typ = '" + ManifestErzeugen.TYP.wert() + "'");
        mandant = register.aktive().get(0).code().wert();
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        hilfe.ausfuehren("delete from public.auftrag where typ = '" + ManifestErzeugen.TYP.wert() + "'");
    }

    @Test
    void beimStartNachFrischemFehlschlagNichtsEinreihenZeitplanLaufSchon() throws SQLException {
        auftrag("FEHLER", Instant.now().minus(10, ChronoUnit.MINUTES));

        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planenBeimStart(Instant.now()));
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planenBeimStart(Instant.now()));
        assertThat(anzahl()).as("zweimaliger Start nach FEHLER").isEqualTo(1);

        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planen(Instant.now()));
        assertThat(anzahl()).as("Zeitplan-Lauf unabhängig von der Karenz").isEqualTo(2);
    }

    @Test
    void beimStartNachAbgelaufenerKarenzOderErfolgEinreihen() throws SQLException {
        auftrag("FEHLER", Instant.now().minus(2, ChronoUnit.HOURS));
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planenBeimStart(Instant.now()));
        assertThat(anzahl()).as("Karenz abgelaufen").isEqualTo(2);
    }

    @Test
    void einAelterFehlerMitSpaeteremErfolgSperrtDenStartNicht() throws SQLException {
        auftrag("FEHLER", Instant.now().minus(10, ChronoUnit.MINUTES));
        auftrag("FERTIG", Instant.now().minus(5, ChronoUnit.MINUTES));
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planenBeimStart(Instant.now()));
        assertThat(anzahl()).isEqualTo(3);
    }

    @Test
    void einOffenerOderLaufenderAuftragWirdNichtVerdoppelt() throws SQLException {
        auftrag("OFFEN", null);
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planenBeimStart(Instant.now()));
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planen(Instant.now()));
        assertThat(anzahl()).isEqualTo(1);
        hilfe.ausfuehren("update public.auftrag set status = 'LAEUFT' where typ = '" + ManifestErzeugen.TYP.wert() + "'");
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planen(Instant.now()));
        assertThat(anzahl()).isEqualTo(1);
    }

    @Test
    void einAelterOffenerAuftragHinterEinemNeuerenFehlerVerhindertDasEinreihen() throws SQLException {
        auftrag("OFFEN", null);
        auftrag("FEHLER", Instant.now().minus(2, ChronoUnit.HOURS));
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planen(Instant.now()));
        TaktThread.ausfuehren("manifest-planer-test", () -> planen.planenBeimStart(Instant.now()));
        assertThat(anzahl()).isEqualTo(2);
    }

    @Test
    void gleichzeitigePlanerReihenNurEinmalEin() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var planer = new Thread[4];
        for (int i = 0; i < planer.length; i++) {
            planer[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                TaktThread.ausfuehren("manifest-planer-test", () -> planen.planen(Instant.now()));
            });
            planer[i].start();
        }
        start.countDown();
        for (Thread t : planer) {
            t.join();
        }
        assertThat(anzahl()).isEqualTo(1);
    }

    private void auftrag(String status, Instant beendet) throws SQLException {
        long id = hilfe.rohEinreihen(mandant, ManifestErzeugen.TYP, "{}");
        hilfe.aendern(id, "status = '" + status + "'" + (status.equals("OFFEN") ? ""
                : ", nachbearbeitet = now()") + (beendet == null ? "" : ", beendet = timestamptz '" + beendet + "'"));
    }

    private int anzahl() throws SQLException {
        return Integer.parseInt(String.valueOf(hilfe.wertAus("select count(*) from public.auftrag where typ = '"
                + ManifestErzeugen.TYP.wert() + "' and mandant = '" + mandant + "'")));
    }
}
