package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 02, Req 3.6 (Aufgabe 7.1c): fehlende Nummern einer Folge nach dem Belegnummer-Muster. */
class NummernfolgeTest {

    private static final Pattern MUSTER = Pattern.compile("^(?<jahr>\\d{4})-(?<nr>\\d{3})$");

    @Test
    void fehlendeNummernZwischenUndAbEins() {
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(MUSTER, 2026,
                List.of("2026-002", "2026-003", "2026-006", "2026-004"), 100);
        assertThat(e.fehlend()).containsExactly("2026-001", "2026-005");
        assertThat(e.fehlendGesamt()).isEqualTo(2);
        assertThat(e.vorhanden()).isEqualTo(4);
        assertThat(e.hoechste()).isEqualTo("2026-006");
    }

    @Test
    void lueckenloseFolgeHatKeineLuecke() {
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(MUSTER, 2026, List.of("2026-001", "2026-002"), 100);
        assertThat(e.fehlend()).isEmpty();
        assertThat(e.fehlendGesamt()).isZero();
    }

    @Test
    void altbestandAusserhalbDesMustersUndAndereJahreWerdenIgnoriert() {
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(MUSTER, 2026,
                List.of("R0001", "2026-1", "2026-0003", "A-2026-004", "2025-009", "2026-001", "2026-003", "", " 2026-005"),
                100);
        assertThat(e.vorhanden()).isEqualTo(2);
        assertThat(e.fehlend()).containsExactly("2026-002");
        assertThat(e.hoechste()).isEqualTo("2026-003");
    }

    @Test
    void ohneBelegnummernKeineReihe() {
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(MUSTER, 2026, List.of("R0001", "2025-001"), 100);
        assertThat(e.vorhanden()).isZero();
        assertThat(e.hoechste()).isNull();
        assertThat(e.fehlend()).isEmpty();
        assertThat(e.fehlendGesamt()).isZero();
    }

    @Test
    void musterOhneJahrZaehltAlleUndFormatBleibtErhalten() {
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(Pattern.compile("^RG/(?<nr>\\d{2})/X$"), 2026,
                List.of("RG/01/X", "RG/04/X"), 100);
        assertThat(e.fehlend()).containsExactly("RG/02/X", "RG/03/X");
    }

    @Test
    void listeIstBegrenztUndGesamtzahlStimmt() {
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(MUSTER, 2026, List.of("2026-999"), 5);
        assertThat(e.fehlend()).hasSize(5).startsWith("2026-001", "2026-002");
        assertThat(e.fehlendGesamt()).isEqualTo(998);
    }

    @Test
    void ueberlangeZiffernUndBelegnummernGeltenAlsAusserhalbDesMusters() {
        Pattern weit = Pattern.compile("^(?<nr>\\d+)$");
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(weit, null, List.of("1", "3", "9999999999", "9".repeat(80)), 10);
        assertThat(e.vorhanden()).isEqualTo(2);
        assertThat(e.fehlend()).containsExactly("2");
    }
}
