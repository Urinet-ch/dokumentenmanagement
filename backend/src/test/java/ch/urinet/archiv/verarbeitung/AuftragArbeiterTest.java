package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.AuftragAufraeumen;
import ch.urinet.archiv.verarbeitung.application.AuftragHandlerVerzeichnis;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.Fehlgeschlagen;
import ch.urinet.archiv.verarbeitung.application.Verwaist;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nachbesserung Aufgabe 2.5 (Review PR #41): Platz und Auftrag bleiben belegt, solange der Handler läuft (Req 5.6,
 * 5.7); Nachbearbeitung nach dem letzten Fehlversuch ist dauerhaft (Req 5.4); eine unlesbare Zeile hält niemanden auf;
 * Absicherung des Anspruchs über {@code versuche}; Herunterfahren ohne Versuch. Zweite Nachbesserung: keine ID läuft in
 * derselben JVM doppelt, der Hook belegt einen Platz und wird mit Wartezeit und Obergrenze wiederholt, Handler sichern
 * ihren Anspruch in ihrer Transaktion. Jeder Test arbeitet mit einem eigenen
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

    @Inject
    AuftragService service;

    @Inject
    AgroalDataSource datenquelle;

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

    // Zweite Nachbesserung, Soll 1: dieselbe ID läuft in derselben JVM nie doppelt

    @Test
    void wiederGeholteIdLaeuftNichtNebenIhremHaengendenThread() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 2);
        AuftragAufraeumen aufraeumer = new AuftragAufraeumen(schlange, verzeichnis, worker);
        long id = hilfe.einreihen("urinet", TestHandler.STUR);
        starten(worker, 1);
        // hängend nach 2 × Zeitgrenze (1 s), danach keine Lebenszeichen: der Aufräumer gibt frei
        Thread.sleep(3_700);
        assertThat(aufraeumer.aufraeumen()).isEqualTo(1);
        hilfe.aendern(id, "nicht_vor = null");

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            // ein Platz ist frei, und der Auftrag ist fällig: geholt, aber nicht gestartet
            assertThat(worker.abholen()).isEmpty();
            assertThat(log.volltext()).contains("in dieser JVM läuft noch ein Thread für den Auftrag; nicht gestartet");
        }
        assertThat(TestHandler.STUR_GESTARTET).hasValue(1);
        assertThat(TestHandler.STUR_HOECHSTENS.get(id)).hasValue(1);
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("LAEUFT");
        assertThat(zeile.versuche()).isEqualTo(1);
        // der geparkte Anspruch belegt keinen Platz
        long ok = hilfe.einreihen("urinet", TestHandler.OK);
        List<CompletableFuture<Void>> okLauf = worker.abholen();
        assertThat(okLauf).hasSize(1);
        okLauf.getFirst().get(10, TimeUnit.SECONDS);
        assertThat(hilfe.zeile(ok).status()).isEqualTo("FERTIG");

        // endet der alte Thread, geht der geparkte Anspruch ohne Versuch zurück und läuft dann allein
        TestHandler.sturFreigeben();
        warten();
        zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        starten(worker, 1);
        warten();
        assertThat(hilfe.zeile(id).status()).isEqualTo("FERTIG");
        assertThat(TestHandler.STUR_GESTARTET).hasValue(2);
        assertThat(TestHandler.STUR_HOECHSTENS.get(id)).hasValue(1);
    }

    // Soll 1b: Handler schreiben nur, solange ihr Anspruch hält

    @Test
    void anspruchSichernSperrtDieZeileBisZumEndeDerTransaktion() throws Exception {
        long id = hilfe.einreihen("urinet", TestHandler.OK);
        Auftrag geholt = schlange.holen(Set.of(TestHandler.OK), 1).getFirst();

        String innen = QuarkusTransaction.requiringNew().call(() -> {
            service.anspruchSichern(geholt);
            return sperrenVonAussen(id);
        });
        // 55P03: lock_not_available, die Zeile war bis zum Ende der Transaktion gesperrt
        assertThat(innen).isEqualTo("55P03");
        assertThat(sperrenVonAussen(id)).isNull();

        hilfe.aendern(id, "versuche = 1");
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> service.anspruchSichern(geholt)))
                .isInstanceOf(AuftragService.AnspruchVerloren.class);
        assertThatThrownBy(() -> service.anspruchSichern(geholt)).isInstanceOf(IllegalStateException.class);
    }

    // Zweite Nachbesserung, Soll 2: der Hook belegt einen Platz, läuft nie doppelt und endet mit Obergrenze

    @Test
    void haengenderHookBelegtSeinenPlatzUndLaeuftNieDoppelt() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 2);
        AuftragAufraeumen aufraeumer = new AuftragAufraeumen(schlange, verzeichnis, worker);
        long id = hilfe.einreihen("urinet", TestHandler.HOOK_STUR);
        hilfe.aendern(id, "versuche = 2");
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            starten(worker, 1);
            // Zeitgrenze des Hooks 1 s: abgebrochen und als gescheitert gezählt, der Thread läuft weiter
            Thread.sleep(2_300);
            assertThat(log.volltext()).containsPattern("Fehler [0-9a-f-]{36}, Nachbearbeitung endet seit dem Abbruch "
                    + "nicht und gilt als hängend");
        }
        assertThat(TestHandler.HOOK_STUR_AUFRUFE).hasValue(1);
        assertThat(hilfe.wert(id, "nachbearbeitung_versuche")).isEqualTo(1);
        assertThat(hilfe.zeile(id).nachbearbeitet()).isFalse();

        // der Hook hält seinen Platz: von zwei fälligen Aufträgen startet nur einer
        long ok1 = hilfe.einreihen("urinet", TestHandler.OK);
        long ok2 = hilfe.einreihen("urinet", TestHandler.OK);
        List<CompletableFuture<Void>> okLauf = worker.abholen();
        assertThat(okLauf).hasSize(1);
        okLauf.getFirst().get(10, TimeUnit.SECONDS);
        assertThat(List.of(hilfe.zeile(ok1).status(), hilfe.zeile(ok2).status()))
                .containsExactlyInAnyOrder("FERTIG", "OFFEN");

        // Wartezeit abgelaufen, Platz frei: der Aufräumer startet den Hook trotzdem nicht neben dem alten
        hilfe.aendern(id, "beendet = now() - interval '1 hour', nachbearbeitung_nicht_vor = null");
        assertThat(aufraeumer.nachbearbeitungNachholen()).isZero();
        assertThat(TestHandler.HOOK_STUR_AUFRUFE).hasValue(1);
        assertThat(hilfe.wert(id, "nachbearbeitung_versuche")).isEqualTo(1);

        TestHandler.sturFreigeben();
        warten();
        assertThat(aufraeumer.nachbearbeitungNachholen()).isEqualTo(1);
        assertThat(TestHandler.HOOK_STUR_AUFRUFE).hasValue(2);
        assertThat(TestHandler.HOOK_STUR_HOECHSTENS).hasValue(1);
        assertThat(hilfe.zeile(id).nachbearbeitet()).isTrue();
    }

    @Test
    void aufraeumerHoltDenHookNurAufEinemFreienPlatzNach() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 1);
        AuftragAufraeumen aufraeumer = new AuftragAufraeumen(schlange, verzeichnis, worker);
        hilfe.einreihen("urinet", TestHandler.STUR);
        starten(worker, 1);
        long id = hilfe.einreihen("urinet", TestHandler.FEHLER);
        hilfe.aendern(id, "status = 'FEHLER', versuche = 3, fehler = 'fehlgeschlagen fehler-id=" + UUID.randomUUID()
                + "', beendet = now() - interval '1 hour'");

        assertThat(aufraeumer.nachbearbeitungNachholen()).isZero();
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(0);
        assertThat(hilfe.wert(id, "nachbearbeitung_versuche")).isEqualTo(0);

        TestHandler.sturFreigeben();
        warten();
        assertThat(aufraeumer.nachbearbeitungNachholen()).isEqualTo(1);
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(1);
        assertThat(hilfe.zeile(id).nachbearbeitet()).isTrue();
    }

    @Test
    void nachbearbeitungWirdNachFuenfFehlschlaegenAufgegeben() throws Exception {
        AuftragAufraeumen aufraeumer = new AuftragAufraeumen(schlange, verzeichnis, arbeiter(schlange, 1));
        long id = hilfe.einreihen("urinet", TestHandler.FEHLER);
        hilfe.aendern(id, "status = 'FEHLER', versuche = 3, fehler = 'fehlgeschlagen fehler-id=" + UUID.randomUUID()
                + "', beendet = now() - interval '1 hour', nachbearbeitung_versuche = 4");
        TestHandler.HOOK_SCHEITERT.set(true);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThat(aufraeumer.nachbearbeitungNachholen()).isZero();
            aufraeumer.nachbearbeitungNachholen();
            assertThat(log.volltext()).containsPattern("Fehler [0-9a-f-]{36}, Nachbearbeitung nach 5 Versuchen "
                    + "aufgegeben").doesNotContain(TestHandler.GEHEIM);
            assertThat(log.volltext().split("Nachbearbeitung nach 5 Versuchen aufgegeben", -1)).hasSize(2);
        }
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(1);
        assertThat(hilfe.wert(id, "nachbearbeitung_versuche")).isEqualTo(5);
        assertThat(hilfe.wert(id, "nachbearbeitung_nicht_vor")).isNull();
        assertThat(hilfe.zeile(id).nachbearbeitet()).isFalse();
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
        // Wartezeit nach dem ersten Fehlschlag: 2 Minuten
        assertThat(hilfe.wert(id, "nachbearbeitung_versuche")).isEqualTo(1);
        assertThat((Long) hilfe.wert(id, "extract(epoch from nachbearbeitung_nicht_vor - now())::bigint"))
                .isBetween(100L, 120L);
        aufraeumen.aufraeumen();
        assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(1);

        hilfe.aendern(id, "nachbearbeitung_nicht_vor = now() - interval '1 second'");
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
    void scheitertDasSpeichernEinerUnlesbarenZeileLaufenDieAnderenWeiter() throws Exception {
        long kaputt = hilfe.rohEinreihen("urinet", TestHandler.OK, "{\"kunde\":\"FC Beispiel\"}");
        long gut = hilfe.einreihen("urinet", TestHandler.OK);
        hilfe.ausfuehren("create or replace function public.test_unlesbar_stoeren() returns trigger language plpgsql "
                + "as $$ begin if new.fehler like 'unlesbar%' then raise exception 'gestoert'; end if; return new; "
                + "end $$");
        hilfe.ausfuehren("create trigger test_unlesbar_stoeren before update on public.auftrag for each row "
                + "execute function public.test_unlesbar_stoeren()");
        AuftragArbeiter worker = arbeiter(schlange, 2);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            laeufe.addAll(worker.abholen());
            warten();
            assertThat(log.volltext()).containsPattern("Auftrag " + kaputt + ": Fehler [0-9a-f-]{36}, Zeile nicht "
                    + "lesbar \\(.+\\), Status FEHLER nicht gespeichert \\(SQLState P0001\\), übersprungen")
                    .doesNotContain("FC Beispiel");
        } finally {
            hilfe.ausfuehren("drop trigger test_unlesbar_stoeren on public.auftrag");
            hilfe.ausfuehren("drop function public.test_unlesbar_stoeren()");
        }
        assertThat(hilfe.zeile(gut).status()).isEqualTo("FERTIG");
        assertThat(hilfe.zeile(kaputt).status()).isEqualTo("LAEUFT");
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

    // Dritte Nachbesserung, Soll 1: ein Handler, der den Interrupt schluckt, gilt beim Herunterfahren nicht als fertig

    @Test
    void herunterfahrenMitGeschlucktemInterruptGibtOhneVersuchFrei() throws Exception {
        AuftragArbeiter worker = arbeiter(schlange, 1);
        long id = hilfe.einreihen("urinet", TestHandler.SCHLUCKT);
        starten(worker, 1);
        Thread.sleep(300);

        worker.herunterfahren();
        warten();

        assertThat(TestHandler.UNTERBROCHEN).contains(id);
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isZero();
    }

    // Dritte Nachbesserung, Soll 2: verwaist auf FEHLER bei inaktivem Mandanten ruft den Hook erst nach der Aktivierung

    @Test
    void verwaistAufFehlerBeiInaktivemMandantenRuftDenHookErstNachDerAktivierung() throws Exception {
        long id = hilfe.einreihen("vhf-gsk", TestHandler.FEHLER);
        hilfe.aendern(id, "status = 'LAEUFT', versuche = 2, lebenszeichen = now() - interval '1 hour'");
        hilfe.ausfuehren("update public.mandant set aktiv = false where code = 'vhf-gsk'");
        try {
            assertThat(aufraeumen.aufraeumen()).isEqualTo(1);
            assertThat(hilfe.zeile(id).status()).isEqualTo("FEHLER");
            assertThat(TestHandler.NACH_LETZTEM_AUFRUFE).hasValue(0);
            assertThat(hilfe.zeile(id).nachbearbeitet()).isFalse();
            assertThat(hilfe.wert(id, "nachbearbeitung_versuche")).isEqualTo(0);
        } finally {
            hilfe.ausfuehren("update public.mandant set aktiv = true where code = 'vhf-gsk'");
        }

        hilfe.aendern(id, "beendet = now() - interval '1 hour'");
        aufraeumen.aufraeumen();
        assertThat(TestHandler.NACH_LETZTEM).containsEntry(id, new MandantCode("vhf-gsk"));
        assertThat(hilfe.zeile(id).nachbearbeitet()).isTrue();
    }

    // Dritte Nachbesserung, Kann 3: eine gesperrte Zeile hält Lebenszeichen und Aufräumer nicht fest

    @Test
    void gesperrteZeileHaeltLebenszeichenUndAufraeumerNichtFest() throws Exception {
        long gesperrt = hilfe.einreihen("urinet", TestHandler.OK);
        long frei = hilfe.einreihen("urinet", TestHandler.OK);
        for (long id : List.of(gesperrt, frei)) {
            hilfe.aendern(id, "status = 'LAEUFT', lebenszeichen = now() - interval '1 hour'");
        }
        Auftrag alt = new Auftrag(gesperrt, new MandantCode("urinet"), TestHandler.OK, Nutzlast.leer(), 0);
        try (Connection sperre = datenquelle.getConnection()) {
            sperre.setAutoCommit(false);
            try (Statement befehl = sperre.createStatement()) {
                befehl.execute("select 1 from public.auftrag where id = " + gesperrt + " for update");
            }
            try {
                CompletableFuture<Integer> aufgeraeumt = CompletableFuture.supplyAsync(aufraeumen::aufraeumen);
                assertThat(aufgeraeumt.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(hilfe.zeile(frei).status()).isEqualTo("OFFEN");

                CompletableFuture<Boolean> lebenszeichen =
                        CompletableFuture.supplyAsync(() -> schlange.lebenszeichen(alt));
                assertThatThrownBy(() -> lebenszeichen.get(15, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class).cause()
                        .hasMessageContaining("SQLState 57014");
            } finally {
                sperre.rollback();
            }
        }
        assertThat(hilfe.zeile(gesperrt).status()).isEqualTo("LAEUFT");
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

    /** Versucht die Zeile aus einem fremden Thread ohne Transaktion zu sperren; SQLState oder {@code null}. */
    private String sperrenVonAussen(long id) throws Exception {
        FutureTask<String> versuch = new FutureTask<>(() -> {
            try (Connection verbindung = datenquelle.getConnection(); Statement anweisung =
                    verbindung.createStatement()) {
                anweisung.execute("select 1 from public.auftrag where id = " + id + " for update nowait");
                return null;
            } catch (SQLException e) {
                return e.getSQLState();
            }
        });
        Thread.ofPlatform().start(versuch);
        return versuch.get(10, TimeUnit.SECONDS);
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
        public List<Verwaist> verwaiste(Map<AuftragTyp, Duration> zeitgrenzen) {
            return echt.verwaiste(zeitgrenzen);
        }

        @Override
        public boolean verwaistFreigeben(Auftrag auftrag, Duration zeitgrenze, String fehler,
                                         Optional<Duration> wartezeit) {
            return echt.verwaistFreigeben(auftrag, zeitgrenze, fehler, wartezeit);
        }

        @Override
        public boolean nachfolgerVorhanden(Auftrag vorgaenger, String schluessel) {
            return echt.nachfolgerVorhanden(vorgaenger, schluessel);
        }

        @Override
        public void typSperren(MandantCode mandant, AuftragTyp typ) {
            echt.typSperren(mandant, typ);
        }

        @Override
        public OptionalLong offen(MandantCode mandant, AuftragTyp typ, Optional<String> schluessel) {
            return echt.offen(mandant, typ, schluessel);
        }

        @Override
        public boolean offenMit(MandantCode mandant, AuftragTyp typ, String schluessel, String wert) {
            return echt.offenMit(mandant, typ, schluessel, wert);
        }

        @Override
        public List<Fehlgeschlagen> ohneNachbearbeitung(Map<AuftragTyp, Duration> zeitgrenzen) {
            return echt.ohneNachbearbeitung(zeitgrenzen);
        }

        @Override
        public boolean nachbearbeitet(Auftrag auftrag) {
            return echt.nachbearbeitet(auftrag);
        }

        @Override
        public boolean nachbearbeitungGescheitert(Auftrag auftrag, int bisher, Optional<Duration> wartezeit) {
            return echt.nachbearbeitungGescheitert(auftrag, bisher, wartezeit);
        }

        @Override
        public boolean anspruchSperren(Auftrag auftrag) {
            return echt.anspruchSperren(auftrag);
        }
    }
}
