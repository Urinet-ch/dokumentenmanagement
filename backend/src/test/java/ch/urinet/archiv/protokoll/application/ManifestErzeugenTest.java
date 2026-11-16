package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.application.ManifestDaten.Fristen;
import ch.urinet.archiv.protokoll.adapter.out.json.JacksonJsonLeser;
import ch.urinet.archiv.protokoll.domain.Jcs;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sperre des Manifests (Req 15.4, 10.9) und letzter zu erzeugender Tag (Req 15.6, 15.7). */
class ManifestErzeugenTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 11, 12);
    private static final LocalDate DATUM = HEUTE.minusDays(1);
    private static final MonthDay KALENDERJAHR = MonthDay.of(12, 31);
    private static final LocalDate PROTOKOLLFRIST = LocalDate.of(2036, 12, 31);

    private static Optional<LocalDate> sperre(Fristen fristen) {
        return ManifestErzeugen.sperre(fristen, HEUTE, DATUM, KALENDERJAHR);
    }

    @Test
    void dieSperreReichtBisZumSpaetestenAufbewahrenBis() {
        assertThat(sperre(new Fristen(3, false, Optional.of(LocalDate.of(2037, 12, 31)))))
                .contains(LocalDate.of(2037, 12, 31));
    }

    @Test
    void einUnbefristetesDokumentVerlangtMindestensZehnJahre() {
        assertThat(sperre(new Fristen(2, true, Optional.of(LocalDate.of(2040, 1, 1)))))
                .contains(LocalDate.of(2040, 1, 1));
        LocalDate silvester = LocalDate.of(2026, 12, 31);
        assertThat(ManifestErzeugen.sperre(new Fristen(1, true, Optional.empty()), silvester.plusDays(1), silvester,
                KALENDERJAHR)).contains(LocalDate.of(2037, 1, 1));
    }

    @Test
    void dieSperreReichtMindestensBisZurAufbewahrungDerProtokolle() {
        assertThat(sperre(new Fristen(0, false, Optional.empty()))).contains(PROTOKOLLFRIST);
        assertThat(sperre(new Fristen(1, false, Optional.of(LocalDate.of(2026, 1, 1))))).contains(PROTOKOLLFRIST);
        assertThat(sperre(new Fristen(1, false, Optional.of(HEUTE.plusDays(5))))).contains(PROTOKOLLFRIST);
        assertThat(sperre(new Fristen(2, true, Optional.of(LocalDate.of(2030, 1, 1))))).contains(PROTOKOLLFRIST);
    }

    @Test
    void dieProtokollfristFolgtDemGeschaeftsjahresendeDesMandanten() {
        MonthDay juni = MonthDay.of(6, 30);

        assertThat(ManifestErzeugen.protokollfrist(LocalDate.of(2026, 6, 30), juni)).isEqualTo(LocalDate.of(2036, 6, 30));
        assertThat(ManifestErzeugen.protokollfrist(LocalDate.of(2026, 7, 1), juni)).isEqualTo(LocalDate.of(2037, 6, 30));
        assertThat(ManifestErzeugen.protokollfrist(LocalDate.of(2026, 12, 31), KALENDERJAHR)).isEqualTo(PROTOKOLLFRIST);
    }

    @Test
    void derZieltagIstDerVortagSobaldDerNachlaufVorbeiIst() {
        Instant knappNachMitternacht = Instant.parse("2026-11-11T23:10:00Z"); // 00:10 in Zürich
        Instant nachDemNachlauf = Instant.parse("2026-11-11T23:20:00Z");

        assertThat(ManifestErzeugen.zielTag(Nutzlast.leer(), knappNachMitternacht)).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(ManifestErzeugen.zielTag(Nutzlast.leer(), nachDemNachlauf)).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void bisTagBegrenztDenZieltagNurNachUnten() {
        Instant jetzt = Instant.parse("2026-11-12T10:00:00Z");
        Nutzlast frueher = new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, LocalDate.of(2026, 1, 5).toEpochDay()));
        Nutzlast spaeter = new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, LocalDate.of(2026, 12, 1).toEpochDay()));

        assertThat(ManifestErzeugen.zielTag(frueher, jetzt)).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ManifestErzeugen.zielTag(spaeter, jetzt)).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void einAbgelegtesManifestWirdNurBeiGleichemInhaltWiederverwendet() {
        ManifestInhalt abgelegt = inhalt(List.of(eintrag("a")));
        byte[] json = abgelegt.kanonisch();
        JsonLeser leser = new JacksonJsonLeser();

        assertThat(ManifestErzeugen.wiederverwenden(leser, DATUM, json, inhalt(List.of(eintrag("a")))).kanonisch())
                .isEqualTo(json);
        assertThatThrownBy(() -> ManifestErzeugen.wiederverwenden(leser, DATUM, json,
                inhalt(List.of(eintrag("a"), eintrag("b")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("verspätet festgeschriebene Zeile");
        byte[] nichtKanonisch = (" " + new String(json, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ManifestErzeugen.wiederverwenden(leser, DATUM, nichtKanonisch, abgelegt))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nicht kanonisch");
    }

    @Test
    void einAbgelegtesManifestWirdAuchBeiAbweichenderSicherungslisteWiederverwendet() {
        ManifestInhalt abgelegt = inhalt(List.of(eintrag("a")));
        ManifestInhalt neu = inhalt(List.of(eintrag("a")), List.of("b".repeat(64)));

        ManifestInhalt wiederverwendet = ManifestErzeugen.wiederverwenden(new JacksonJsonLeser(), DATUM,
                abgelegt.kanonisch(), neu);

        assertThat(wiederverwendet.sicherung()).isEmpty();
    }

    @Test
    void fehlendeOderLeereSicherungslisteWarntNurMitAnzahlen() {
        ManifestInhalt abgelegt = inhalt(List.of(eintrag("a")));
        String hash = "c".repeat(64);
        ManifestInhalt neu = inhalt(List.of(eintrag("a")), List.of(hash));
        Map<String, Object> ohneSammlung = new JacksonJsonLeser().objekt(abgelegt.kanonisch());
        ohneSammlung.remove("sicherung");
        List<byte[]> varianten = List.of(abgelegt.kanonisch(), Jcs.utf8(ohneSammlung));
        Logger logger = Logger.getLogger(ManifestErzeugen.class.getName());
        Warnungen warnungen = new Warnungen();
        logger.addHandler(warnungen);
        try {
            for (byte[] variante : varianten) {
                ManifestErzeugen.wiederverwenden(new JacksonJsonLeser(), DATUM, variante, neu);
            }
        } finally {
            logger.removeHandler(warnungen);
        }

        assertThat(warnungen.meldungen).hasSize(2).allSatisfy(meldung -> assertThat(meldung)
                .contains("abgelegt=0", "neu=1").doesNotContain(hash, "original"));
    }

    private static ManifestInhalt inhalt(List<ManifestEintrag> eintraege) {
        return inhalt(eintraege, List.of());
    }

    private static ManifestInhalt inhalt(List<ManifestEintrag> eintraege, List<String> sicherungen) {
        ManifestFenster fenster = ManifestFenster.fuer(DATUM, null);
        return new ManifestInhalt("probe", DATUM, null, fenster.bis(), null, null, eintraege, new TreeMap<>(),
                sicherungen);
    }

    private static ManifestEintrag eintrag(String zeichen) {
        return new ManifestEintrag(UUID.nameUUIDFromBytes(zeichen.getBytes(StandardCharsets.UTF_8)), "original", 1,
                zeichen.repeat(64), "v1");
    }

    @Test
    void dieZeitstempelUrsacheNenntDieFesteMeldungAberKeineFremdenMeldungen() {
        var ursache = new ZeitstempelPort.ZeitstempelFehler("Zeitstempel nicht erhalten oder ungültig",
                new java.io.IOException("https://tsa.invalid/endpoint 0123456789abcdef antwortinhalt"));

        String text = ch.urinet.archiv.gemeinsam.domain.Ursachen.ketteMitMeldung(ursache);

        assertThat(text).contains("ZeitstempelPort$ZeitstempelFehler").contains("<- java.io.IOException")
                .contains("[meldung=\"Zeitstempel nicht erhalten oder ungültig\"]")
                .doesNotContain("tsa.invalid").doesNotContain("0123456789abcdef").doesNotContain("antwortinhalt");
    }

    @Test
    void keinZeitstempelTraegtDenErstenDienstfehlerAlsUrsacheUndDieUebrigenAlsSuppressed() {
        var erster = new ZeitstempelPort.ZeitstempelFehler("Zeitstempel von a nicht erhalten oder ungültig", null);
        var zweiter = new java.util.concurrent.TimeoutException();

        IllegalStateException e = ManifestErzeugen.keinZeitstempel(DATUM, List.of("a", "b"), List.of(erster, zweiter));

        assertThat(e).hasMessageContaining("kein Zeitstempel von [a, b]").hasCause(erster);
        assertThat(e.getSuppressed()).containsExactly(zweiter);
        assertThat(ManifestErzeugen.keinZeitstempel(DATUM, List.of("a"), List.of()).getCause()).isNull();
    }

    private static final class Warnungen extends Handler {
        private final List<String> meldungen = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                meldungen.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
