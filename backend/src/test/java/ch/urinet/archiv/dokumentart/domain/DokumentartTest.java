package ch.urinet.archiv.dokumentart.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invarianten einer Dokumentart (Req 9.1). */
class DokumentartTest {

    @Test
    void gueltigeArt() {
        Dokumentart art = art("SPENDE", List.of("finanz", "frei"), List.of("titel", "betrag", "zusatz.zweck"));
        assertThat(art.zusatzPflichtfelder()).containsExactly("zweck");
        assertThat(art.name()).isEqualTo("Spende");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "spende", "1ART", "ART-X", "AÖ", "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNO"})
    void ungueltigerCode(String code) {
        assertUngueltig(() -> art(code, List.of(), List.of("titel")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "unbekannt|titel",
            "finanz,finanz|titel",
            "''|betrag",
            "finanz|gueltig_bis",
            "finanz|zusatz.zweck",
            "frei|zusatz.Zweck",
            "frei|titel,titel",
            "frei|unbekannt"})
    void ungueltigeGruppenOderPflichtfelder(String gruppen, String pflichtfelder) {
        assertUngueltig(() -> art("SPENDE", liste(gruppen), liste(pflichtfelder)));
    }

    @Test
    void regelBrauchtJahreUndAbZusammen() {
        assertUngueltig(() -> new Aufbewahrungsregel(10, null));
        assertUngueltig(() -> new Aufbewahrungsregel(null, AufbewahrungAb.GESCHAEFTSJAHR));
        assertUngueltig(() -> new Aufbewahrungsregel(0, AufbewahrungAb.GESCHAEFTSJAHR));
        assertUngueltig(() -> new Aufbewahrungsregel(101, AufbewahrungAb.GESCHAEFTSJAHR));
    }

    @Test
    void nameOhneSteuerzeichenUndBeschreibungBegrenzt() {
        assertUngueltig(() -> new Dokumentart("SPENDE", "Spen\u0000de", null, List.of(), List.of(),
                Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.NORMAL, Aufbewahrungsregel.UNBEFRISTET, false, true));
        assertUngueltig(() -> new Dokumentart("SPENDE", "Spende", "x".repeat(1001), List.of(), List.of(),
                Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.NORMAL, Aufbewahrungsregel.UNBEFRISTET, false, true));
        assertUngueltig(() -> new Dokumentart("SPENDE", "  ", null, List.of(), List.of(),
                Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.NORMAL, Aufbewahrungsregel.UNBEFRISTET, false, true));
    }

    private static Dokumentart art(String code, List<String> gruppen, List<String> pflichtfelder) {
        return new Dokumentart(code, " Spende ", null, gruppen, pflichtfelder, Dokumentart.OHNE_ZUSATZ,
                Vertraulichkeit.NORMAL, new Aufbewahrungsregel(10, AufbewahrungAb.GESCHAEFTSJAHR), false, true);
    }

    private static List<String> liste(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(","));
    }

    private static void assertUngueltig(org.assertj.core.api.ThrowableAssert.ThrowingCallable aufruf) {
        assertThatThrownBy(aufruf).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }
}
