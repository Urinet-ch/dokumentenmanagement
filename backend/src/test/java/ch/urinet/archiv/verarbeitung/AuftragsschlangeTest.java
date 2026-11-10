package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragAufraeumen;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Auftragsschlange (Aufgabe 2.5): {@code SKIP LOCKED} ohne Doppelvergabe, Mandanten-Kontext je Auftrag (Req 1.6),
 * Wiederholung mit Wartezeit und {@code FEHLER} nach dem dritten Versuch (Req 5.4), Zeitgrenze (Req 5.6), Lebenszeichen
 * und Freigabe verwaister Aufträge (Req 5.7), keine Werte im Log (Req 16.7). Poller und Aufräumer sind im Test
 * abgeschaltet; die Tests rufen sie selbst auf.
 */
@QuarkusTest
class AuftragsschlangeTest {

    private static final String FEHLER_ID = "fehler-id=[0-9a-f-]{36}";

    @Inject
    AuftragTestHilfe hilfe;

    @Inject
    AuftragSchlange schlange;

    @Inject
    AuftragService service;

    @Inject
    AuftragAufraeumen aufraeumen;

    @BeforeEach
    void vorbereiten() throws Exception {
        hilfe.vorbereiten();
    }

    @Test
    void zweiAbholerErhaltenKeinenAuftragDoppelt() throws Exception {
        for (int i = 0; i < 50; i++) {
            hilfe.einreihen(i % 2 == 0 ? "urinet" : "vhf-gsk", TestHandler.OK);
        }
        CyclicBarrier gleichzeitig = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> a = threads.submit(() -> alleHolen(gleichzeitig));
            Future<List<Long>> b = threads.submit(() -> alleHolen(gleichzeitig));
            List<Long> alle = new ArrayList<>(a.get(30, TimeUnit.SECONDS));
            alle.addAll(b.get(30, TimeUnit.SECONDS));
            assertThat(alle).hasSize(50).doesNotHaveDuplicates();
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void fehlerEinesMandantenHaeltDenAnderenNichtAufUndJederLaeuftInSeinemSchema() throws Exception {
        long fehler = hilfe.einreihen("urinet", TestHandler.FEHLER);
        long vhfGsk = hilfe.einreihen("vhf-gsk", TestHandler.OK);
        long urinet = hilfe.einreihen("urinet", TestHandler.OK);

        hilfe.abarbeiten();

        assertThat(TestHandler.SCHEMAS).containsEntry(vhfGsk, "m_vhf_gsk").containsEntry(urinet, "m_urinet");
        assertThat(hilfe.zeile(vhfGsk).status()).isEqualTo("FERTIG");
        assertThat(hilfe.zeile(vhfGsk).mandant()).isEqualTo("vhf-gsk");
        AuftragTestHilfe.Zeile nachFehler = hilfe.zeile(fehler);
        assertThat(nachFehler.status()).isEqualTo("OFFEN");
        assertThat(nachFehler.versuche()).isEqualTo(1);
        assertThat(nachFehler.fehler()).matches("fehlgeschlagen " + FEHLER_ID);
        // 2^1 Minuten
        assertThat(nachFehler.wartezeitSekunden()).isBetween(100L, 120L);
    }

    @Test
    void nachDemDrittenFehlversuchFehlerOhneWerteImLog() throws Exception {
        long id = hilfe.einreihen("vhf-gsk", TestHandler.FEHLER);
        hilfe.aendern(id, "versuche = 2");
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            hilfe.abarbeiten();

            AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
            assertThat(zeile.status()).isEqualTo("FEHLER");
            assertThat(zeile.versuche()).isEqualTo(3);
            assertThat(zeile.beendet()).isTrue();
            assertThat(zeile.fehler()).matches("fehlgeschlagen " + FEHLER_ID).doesNotContain(TestHandler.GEHEIM);
            assertThat(TestHandler.NACH_LETZTEM).containsEntry(id, new MandantCode("vhf-gsk"));
            String fehlerId = zeile.fehler().substring(zeile.fehler().indexOf('=') + 1);
            assertThat(log.volltext()).contains(fehlerId, "Versuch 3 von 3", "Status FEHLER", "java.lang.Exception")
                    .doesNotContain(TestHandler.GEHEIM);
        }
    }

    @Test
    void zeitgrenzeBrichtAbUndLebenszeichenLaufenMit() throws Exception {
        long id = hilfe.einreihen("urinet", TestHandler.HAENGT);
        CompletableFuture<Void> lauf = hilfe.einenStarten();
        // Lebenszeichen alle 200 ms, Zeitgrenze 1 s
        Thread.sleep(600);
        assertThat(hilfe.zeile(id).status()).isEqualTo("LAEUFT");
        assertThat(hilfe.zeile(id).lebenszeichenNachStart()).isTrue();

        lauf.get(10, TimeUnit.SECONDS);

        assertThat(TestHandler.UNTERBROCHEN).contains(id);
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).matches("zeitgrenze " + FEHLER_ID);
        assertThat(zeile.wartezeitSekunden()).isPositive();
    }

    @Test
    void verwaisterAuftragWirdFreigegebenLaufenderNicht() throws Exception {
        long verwaist = hilfe.einreihen("urinet", TestHandler.OK);
        long laeuft = hilfe.einreihen("urinet", TestHandler.OK);
        hilfe.aendern(verwaist, "status = 'LAEUFT', gestartet = now() - interval '1 hour', "
                + "lebenszeichen = now() - interval '1 hour'");
        hilfe.aendern(laeuft, "status = 'LAEUFT', gestartet = now() - interval '1 hour', lebenszeichen = now()");

        assertThat(aufraeumen.aufraeumen()).isEqualTo(1);

        AuftragTestHilfe.Zeile zeile = hilfe.zeile(verwaist);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).matches("verwaist " + FEHLER_ID);
        assertThat(zeile.wartezeitSekunden()).isPositive();
        assertThat(hilfe.zeile(laeuft).status()).isEqualTo("LAEUFT");
    }

    @Test
    void verwaistNachDemDrittenVersuchIstFehler() throws Exception {
        long id = hilfe.einreihen("vhf-gsk", TestHandler.FEHLER);
        hilfe.aendern(id, "status = 'LAEUFT', versuche = 2, lebenszeichen = now() - interval '1 hour'");

        assertThat(aufraeumen.aufraeumen()).isEqualTo(1);

        assertThat(hilfe.zeile(id).status()).isEqualTo("FEHLER");
        assertThat(TestHandler.NACH_LETZTEM).containsEntry(id, new MandantCode("vhf-gsk"));
    }

    @Test
    void verlorenerAnspruchAendertNichts() throws Exception {
        long id = hilfe.einreihen("urinet", TestHandler.OK);
        Auftrag geholt = schlange.holen(Set.of(TestHandler.OK), 1).getFirst();
        // der Aufräumer hat inzwischen freigegeben: versuche ist höher
        hilfe.aendern(id, "versuche = 1, status = 'OFFEN'");

        assertThat(schlange.lebenszeichen(geholt)).isFalse();
        assertThat(schlange.abschliessen(geholt)).isFalse();
        assertThat(hilfe.zeile(id).status()).isEqualTo("OFFEN");
    }

    @Test
    void einreihenOhneMandantOderOhneHandlerBrichtAb() throws Exception {
        // Request-Kontext ohne Anmeldung und ohne Mandanten-Kontext
        assertThatThrownBy(() -> service.einreihen(TestHandler.OK, Nutzlast.leer()))
                .isInstanceOf(IllegalStateException.class);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            assertThat(thread.submit(() -> {
                try {
                    service.einreihen(TestHandler.OK, Nutzlast.leer());
                    return "eingereiht";
                } catch (IllegalStateException erwartet) {
                    return "abgebrochen";
                }
            }).get(10, TimeUnit.SECONDS)).isEqualTo("abgebrochen");
        } finally {
            thread.shutdownNow();
        }
        assertThatThrownBy(() -> hilfe.einreihen("urinet", new AuftragTyp("TEST_UNBEKANNT")))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private List<Long> alleHolen(CyclicBarrier gleichzeitig) throws Exception {
        List<Long> ids = new ArrayList<>();
        Set<AuftragTyp> typen = new HashSet<>(Set.of(TestHandler.OK));
        gleichzeitig.await();
        List<Auftrag> geholt;
        do {
            geholt = schlange.holen(typen, 3);
            geholt.forEach(auftrag -> ids.add(auftrag.id()));
        } while (!geholt.isEmpty());
        return ids;
    }
}
