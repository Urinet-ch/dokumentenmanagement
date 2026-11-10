package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.AuftragAufraeumen;
import ch.urinet.archiv.verarbeitung.application.AuftragHandlerVerzeichnis;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.Fehlgeschlagen;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nachbesserung Aufgabe 2.5 (Review PR #41): Platz und Auftrag bleiben belegt, solange der Handler läuft (Req 5.6,
 * 5.7); Nachbearbeitung nach dem letzten Fehlversuch ist dauerhaft (Req 5.4); eine unlesbare Zeile hält niemanden auf;
 * Absicherung des Anspruchs über {@code versuche}; Herunterfahren ohne Versuch. Jeder Test arbeitet mit einem eigenen
 * {@link AuftragArbeiter}, damit ein absichtlich hängender Handler keinen Platz eines anderen Tests belegt.
 */
@QuarkusTest
class AuftragArbeiterTest {

    private static final String FEHLER_ID = "fehler-id=[0-9a-f-]{36}";
    private static final Duration LEBENSZEICHEN = Duration.ofMillis(200);

    @Inject
    AuftragTestHilfe hilfe;

    @Inject
    AuftragSchlange schlange;

    @Inject
    AuftragHandlerVerzeichnis verzeichnis;

    @Inject
    MandantKontext kontext;

    @Inject
    AuftragAufraeumen aufraeumen;

    private final List<AuftragArbeiter> arbeiter = new ArrayList<>();
    private final List<CompletableFuture<Void>> laeufe = new ArrayList<>();

    @BeforeEach
    void vorbereiten() throws Exception {
        hilfe.vorbereiten();
    }

    @AfterEach
    void aufraeumen() throws Exception {
        TestHandler.sturFreigeben();
        for (CompletableFuture<Void> lauf : laeufe) {
            lauf.get(30, TimeUnit.SECONDS);
        }
        arbeiter.forEach(AuftragArbeiter::herunterfahren);
    }

    // Soll 1: ein Handler, der den Interrupt ignoriert, läuft nie doppelt

    @Test
    void keinZweiterLaufSolangeDerErsteNachDerZeitgrenzeWeiterlaeuft() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 2);
        AuftragArbeiter andereJvm = arbeiter(schlange, 2);
        long id = hilfe.einreihen("urinet", TestHandler.STUR);
        starten(worker, 1);

        // Zeitgrenze 1 s: der Handler wurde unterbrochen, läuft aber weiter
        Thread.sleep(1_300);
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("LAEUFT");
        assertThat(zeile.versuche()).isZero();
        assertThat(zeile.lebenszeichenAlterMs()).isLessThan(1_000L);
        // wäre der Fehlversuch schon gespeichert, wäre der Auftrag jetzt sofort wieder fällig
        hilfe.ausfuehren("update public.auftrag set nicht_vor = null where id = " + id + " and status = 'OFFEN'");
        laeufe.addAll(worker.abholen());
        laeufe.addAll(andereJvm.abholen());

        assertThat(TestHandler.STUR_GESTARTET).hasValue(1);
        assertThat(TestHandler.STUR_HOECHSTENS.get(id)).hasValue(1);

        TestHandler.sturFreigeben();
        warten();
        assertThat(TestHandler.UNTERBROCHEN).contains(id);
        zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).matches("zeitgrenze " + FEHLER_ID);
        assertThat(zeile.wartezeitSekunden()).isPositive();
    }

    @Test
    void platzBleibtBelegtBisDerHandlerEndet() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 1);
        hilfe.einreihen("urinet", TestHandler.STUR);
        long ok = hilfe.einreihen("urinet", TestHandler.OK);
        starten(worker, 1);
        Thread.sleep(1_300);

        assertThat(worker.abholen()).isEmpty();
        assertThat(hilfe.zeile(ok).status()).isEqualTo("OFFEN");

        TestHandler.sturFreigeben();
        warten();
        laeufe.addAll(worker.abholen());
        warten();
        assertThat(hilfe.zeile(ok).status()).isEqualTo("FERTIG");
    }

    @Test
    void fehlerBeimSpeichernGibtDenPlatzNichtFrei() throws Exception {
        StoerbareSchlange stoerbar = new StoerbareSchlange(schlange);
        stoerbar.fehlversuchScheitert = true;
        AuftragArbeiter worker = arbeiter(stoerbar, 1);
        long stur = hilfe.einreihen("urinet", TestHandler.STUR);
        long ok = hilfe.einreihen("urinet", TestHandler.OK);
        starten(worker, 1);
        Thread.sleep(1_300);

        assertThat(worker.abholen()).isEmpty();
        assertThat(hilfe.zeile(ok).status()).isEqualTo("OFFEN");

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            TestHandler.sturFreigeben();
            warten();
            assertThat(log.volltext()).containsPattern("Fehler [0-9a-f-]{36}, Ergebnis nicht gespeichert");
        }
        // Fehlversuch nicht gespeichert: bleibt LAEUFT, bis der Aufräumer freigibt
        assertThat(hilfe.zeile(stur).status()).isEqualTo("LAEUFT");
        laeufe.addAll(worker.abholen());
        warten();
        assertThat(hilfe.zeile(ok).status()).isEqualTo("FERTIG");
    }

    @Test
    void haengenderHandlerBehaeltSeinenPlatzUndDerAufraeumerGibtDenAuftragFrei() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 1);
        long id = hilfe.einreihen("urinet", TestHandler.STUR);
        long ok = hilfe.einreihen("urinet", TestHandler.OK);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            starten(worker, 1);
            // hängend nach 2 × Zeitgrenze (1 s)
            Thread.sleep(2_500);
            assertThat(log.volltext()).containsPattern("Fehler [0-9a-f-]{36}, Handler endet seit dem Abbruch nicht "
                    + "und gilt als hängend");
        }
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("LAEUFT");
        assertThat(zeile.versuche()).isZero();
        // keine Lebenszeichen mehr: der Aufräumer gibt nach einer weiteren Zeitgrenze frei
        Thread.sleep(1_200);
        assertThat(hilfe.zeile(id).lebenszeichenAlterMs()).isGreaterThan(1_000L);
        assertThat(aufraeumen.aufraeumen()).isEqualTo(1);
        zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).matches("verwaist " + FEHLER_ID);
        // der Platz bleibt belegt
        assertThat(worker.abholen()).isEmpty();

        TestHandler.sturFreigeben();
        warten();
        // der Worker schreibt nach dem Ende nichts mehr
        AuftragTestHilfe.Zeile danach = hilfe.zeile(id);
        assertThat(danach.status()).isEqualTo("OFFEN");
        assertThat(danach.versuche()).isEqualTo(1);
        assertThat(danach.fehler()).isEqualTo(zeile.fehler());
        assertThat(danach.wartezeitSekunden()).isPositive();
        laeufe.addAll(worker.abholen());
        warten();
        assertThat(hilfe.zeile(ok).status()).isEqualTo("FERTIG");
    }

    // Soll 2: Nachbearbeitung nach dem letzten Fehlversuch ist dauerhaft

    @Test
    void absturzZwischenFehlerUndHookHoltDerAufraeumerNach() throws Exception {
        long id = hilfe.einreihen("vhf-gsk", TestHandler.FEHLER);
        UUID fehlerId = UUID.randomUUID();
        // Worker abgestürzt, nachdem FEHLER gespeichert war und bevor der Hook lief
        hilfe.aendern(id, "status = 'FEHLER', versuche = 3, fehler = 'fehlgeschlagen fehler-id=" + fehlerId + "', "
                + "beendet = now() - interval '1 hour'");

        assertThat(aufraeumen.aufraeumen()).isZero();

        assertThat(TestHandler.NACH_LETZTEM).containsEntry(id, new MandantCode("vhf-gsk"));
        assertThat(TestHandler.NACH_LETZTEM_FEHLER).containsEntry(id, fehlerId);
        assertThat(hilfe.zeile(id).nachbearbeitet()).isTrue();
        aufraeumen.aufraeumen();
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(1);
    }

    @Test
    void gescheiterterHookWirdNachDerZeitgrenzeNachgeholt() throws Exception {
        long id = hilfe.einreihen("urinet", TestHandler.FEHLER);
        hilfe.aendern(id, "versuche = 2");
        TestHandler.HOOK_SCHEITERT.set(true);
        AuftragArbeiter worker = arbeiter(schlange, 1);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            starten(worker, 1);
            warten();
            assertThat(log.volltext()).contains("Nachbearbeitung folgt beim Aufräumen").doesNotContain(TestHandler.GEHEIM);
        }
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("FEHLER");
        assertThat(zeile.nachbearbeitet()).isFalse();
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(1);

        // innerhalb der Zeitgrenze könnte der Hook des Workers noch laufen
        aufraeumen.aufraeumen();
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(1);

        hilfe.aendern(id, "beendet = now() - interval '1 hour'");
        aufraeumen.aufraeumen();
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(2);
        assertThat(TestHandler.NACH_LETZTEM).containsEntry(id, new MandantCode("urinet"));
        assertThat(TestHandler.NACH_LETZTEM_FEHLER.get(id)).hasToString(zeile.fehlerId());
        assertThat(hilfe.zeile(id).nachbearbeitet()).isTrue();
    }

    @Test
    void nachbearbeitungPausiertBeiInaktivemMandanten() throws Exception {
        long id = hilfe.einreihen("vhf-gsk", TestHandler.FEHLER);
        hilfe.aendern(id, "status = 'FEHLER', versuche = 3, fehler = 'fehlgeschlagen fehler-id=" + UUID.randomUUID()
                + "', beendet = now() - interval '1 hour'");
        Map<AuftragTyp, Duration> grenzen = verzeichnis.zeitgrenzen();
        hilfe.ausfuehren("update public.mandant set aktiv = false where code = 'vhf-gsk'");
        try {
            assertThat(schlange.ohneNachbearbeitung(grenzen)).extracting(f -> f.auftrag().id()).doesNotContain(id);
        } finally {
            hilfe.ausfuehren("update public.mandant set aktiv = true where code = 'vhf-gsk'");
        }
        assertThat(schlange.ohneNachbearbeitung(grenzen)).extracting(f -> f.auftrag().id()).contains(id);
    }

    // Soll 3: eine unlesbare Zeile hält Poller und Aufräumer nicht auf

    @Test
    void unlesbareZeileGehtAufFehlerUndDieGuteLaeuft() throws Exception {
        long kaputt = hilfe.rohEinreihen("urinet", TestHandler.OK, "{\"kunde\":\"FC Beispiel\"}");
        long gut = hilfe.einreihen("vhf-gsk", TestHandler.OK);
        AuftragArbeiter worker = arbeiter(schlange, 2);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            laeufe.addAll(worker.abholen());
            warten();
            AuftragTestHilfe.Zeile zeile = hilfe.zeile(kaputt);
            assertThat(zeile.status()).isEqualTo("FEHLER");
            assertThat(zeile.versuche()).isEqualTo(1);
            assertThat(zeile.fehler()).matches("unlesbar " + FEHLER_ID);
            assertThat(zeile.nachbearbeitet()).isTrue();
            assertThat(log.volltext()).contains(zeile.fehlerId()).doesNotContain("FC Beispiel");
        }
        assertThat(hilfe.zeile(gut).status()).isEqualTo("FERTIG");
    }

    @Test
    void unlesbareVerwaisteZeileHaeltDenAufraeumerNichtAuf() throws Exception {
        long kaputt = hilfe.rohEinreihen("urinet", TestHandler.OK, "{\"kunde\":\"FC Beispiel\"}");
        long gut = hilfe.einreihen("urinet", TestHandler.OK);
        for (long id : List.of(kaputt, gut)) {
            hilfe.aendern(id, "status = 'LAEUFT', lebenszeichen = now() - interval '1 hour'");
        }

        assertThat(aufraeumen.aufraeumen()).isEqualTo(1);

        assertThat(hilfe.zeile(kaputt).status()).isEqualTo("FEHLER");
        assertThat(hilfe.zeile(gut).status()).isEqualTo("OFFEN");
    }

    @Test
    void nutzlastIstImmerEinObjekt() {
        for (String json : List.of("[1]", "\"text\"", "5", "true")) {
            assertThatThrownBy(() -> hilfe.rohEinreihen("urinet", TestHandler.OK, json)).as(json)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
    }

    // Soll 5 und Kann 10: Absicherung des Anspruchs

    @Test
    void wiederGeholterAuftragGehoertNichtMehrDemAltenAnspruch() throws Exception {
        long id = hilfe.einreihen("urinet", TestHandler.OK);
        Auftrag alt = schlange.holen(Set.of(TestHandler.OK), 1).getFirst();
        // der Aufräumer hat freigegeben und ein anderer Worker hat den Auftrag wieder geholt
        hilfe.aendern(id, "versuche = 1, status = 'LAEUFT', lebenszeichen = now()");

        assertThat(schlange.lebenszeichen(alt)).isFalse();
        assertThat(schlange.abschliessen(alt)).isFalse();
        assertThat(schlange.fehlversuch(alt, "fehlgeschlagen fehler-id=" + UUID.randomUUID(),
                Optional.of(Duration.ofMinutes(2)))).isFalse();
        assertThat(schlange.fehlversuch(alt, "fehlgeschlagen fehler-id=" + UUID.randomUUID(), Optional.empty()))
                .isFalse();
        assertThat(schlange.freigeben(alt)).isFalse();
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("LAEUFT");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).isNull();
    }

    @Test
    void verwaistFreigebenPrueftDasLebenszeichenErneut() throws Exception {
        long id = hilfe.einreihen("urinet", TestHandler.OK);
        // frisch geholt: Lebenszeichen jetzt
        Auftrag geholt = schlange.holen(Set.of(TestHandler.OK), 1).getFirst();

        assertThat(schlange.verwaistFreigeben(geholt, Duration.ofSeconds(5), "verwaist fehler-id=" + UUID.randomUUID(),
                Optional.of(Duration.ofMinutes(2)))).isFalse();
        assertThat(hilfe.zeile(id).status()).isEqualTo("LAEUFT");

        hilfe.aendern(id, "lebenszeichen = now() - interval '1 minute'");
        assertThat(schlange.verwaistFreigeben(geholt, Duration.ofSeconds(5), "verwaist fehler-id=" + UUID.randomUUID(),
                Optional.of(Duration.ofMinutes(2)))).isTrue();
        assertThat(hilfe.zeile(id).status()).isEqualTo("OFFEN");
    }

    // Kann 7: Herunterfahren kostet keinen Versuch

    @Test
    void herunterfahrenGibtOhneVersuchFrei() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 1);
        long id = hilfe.einreihen("urinet", TestHandler.HAENGT);
        starten(worker, 1);
        Thread.sleep(300);

        worker.herunterfahren();
        warten();

        assertThat(TestHandler.UNTERBROCHEN).contains(id);
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isZero();
        assertThat(zeile.wartezeitSekunden()).isNull();
        hilfe.einreihen("urinet", TestHandler.OK);
        assertThat(worker.abholen()).isEmpty();
    }

    @Test
    void herunterfahrenLaesstEinenNichtEndendenHandlerAufLaeuft() throws Exception {
        AuftragArbeiter worker = new AuftragArbeiter(schlange, verzeichnis, kontext, 1, LEBENSZEICHEN,
                Duration.ofMillis(300));
        arbeiter.add(worker);
        long id = hilfe.einreihen("urinet", TestHandler.STUR);
        starten(worker, 1);
        Thread.sleep(300);

        worker.herunterfahren();

        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("LAEUFT");
        assertThat(zeile.versuche()).isZero();
    }

    // Kann 8: Abschluss einmal wiederholen

    @Test
    void abschlussWirdEinmalWiederholt() throws Exception {
        StoerbareSchlange stoerbar = new StoerbareSchlange(schlange);
        stoerbar.abschlussScheitert.set(1);
        long id = hilfe.einreihen("urinet", TestHandler.OK);
        starten(arbeiter(stoerbar, 1), 1);
        warten();

        assertThat(hilfe.zeile(id).status()).isEqualTo("FERTIG");
    }

    @Test
    void abschlussScheitertZweimalBleibtLaeuftMitFehlerId() throws Exception {
        StoerbareSchlange stoerbar = new StoerbareSchlange(schlange);
        stoerbar.abschlussScheitert.set(2);
        long id = hilfe.einreihen("urinet", TestHandler.OK);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            starten(arbeiter(stoerbar, 1), 1);
            warten();
            assertThat(log.volltext()).containsPattern("Fehler [0-9a-f-]{36}, Abschluss auch im zweiten Versuch nicht "
                    + "gespeichert");
        }
        assertThat(hilfe.zeile(id).status()).isEqualTo("LAEUFT");
    }

    private AuftragArbeiter arbeiter(AuftragSchlange mit, int parallel) {
        AuftragArbeiter neu = new AuftragArbeiter(mit, verzeichnis, kontext, parallel, LEBENSZEICHEN,
                Duration.ofSeconds(1));
        arbeiter.add(neu);
        return neu;
    }

    private void starten(AuftragArbeiter worker, int erwartet) {
        List<CompletableFuture<Void>> gestartet = worker.abholen();
        assertThat(gestartet).hasSize(erwartet);
        laeufe.addAll(gestartet);
    }

    private void warten() throws Exception {
        for (CompletableFuture<Void> lauf : laeufe) {
            lauf.get(30, TimeUnit.SECONDS);
        }
    }

    /** Leitet an die echte Schlange weiter und lässt Speichern auf Wunsch scheitern. */
    static final class StoerbareSchlange implements AuftragSchlange {

        private final AuftragSchlange echt;
        volatile boolean fehlversuchScheitert;
        final AtomicInteger abschlussScheitert = new AtomicInteger();

        StoerbareSchlange(AuftragSchlange echt) {
            this.echt = echt;
        }

        @Override
        public long einfuegen(MandantCode mandant, AuftragTyp typ, Nutzlast nutzlast) {
            return echt.einfuegen(mandant, typ, nutzlast);
        }

        @Override
        public List<Auftrag> holen(Set<AuftragTyp> typen, int anzahl) {
            return echt.holen(typen, anzahl);
        }

        @Override
        public boolean lebenszeichen(Auftrag auftrag) {
            return echt.lebenszeichen(auftrag);
        }

        @Override
        public boolean abschliessen(Auftrag auftrag) {
            if (abschlussScheitert.getAndDecrement() > 0) {
                throw new IllegalStateException("Abschluss gestört");
            }
            return echt.abschliessen(auftrag);
        }

        @Override
        public boolean fehlversuch(Auftrag auftrag, String fehler, Optional<Duration> wartezeit) {
            if (fehlversuchScheitert) {
                throw new IllegalStateException("Fehlversuch gestört");
            }
            return echt.fehlversuch(auftrag, fehler, wartezeit);
        }

        @Override
        public boolean freigeben(Auftrag auftrag) {
            return echt.freigeben(auftrag);
        }

        @Override
        public List<Auftrag> verwaiste(Map<AuftragTyp, Duration> zeitgrenzen) {
            return echt.verwaiste(zeitgrenzen);
        }

        @Override
        public boolean verwaistFreigeben(Auftrag auftrag, Duration zeitgrenze, String fehler,
                                         Optional<Duration> wartezeit) {
            return echt.verwaistFreigeben(auftrag, zeitgrenze, fehler, wartezeit);
        }

        @Override
        public List<Fehlgeschlagen> ohneNachbearbeitung(Map<AuftragTyp, Duration> zeitgrenzen) {
            return echt.ohneNachbearbeitung(zeitgrenzen);
        }

        @Override
        public boolean nachbearbeitet(Auftrag auftrag) {
            return echt.nachbearbeitet(auftrag);
        }
    }
}
