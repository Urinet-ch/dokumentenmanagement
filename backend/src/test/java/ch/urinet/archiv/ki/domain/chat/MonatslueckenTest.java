package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.MonthDay;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 02, Req 3.6 (Aufgabe 7.1c): Monate ohne Dokument ab dem ersten Beleg bis zum letzten abgeschlossenen. */
class MonatslueckenTest {

    private static final Monatsluecken.Spanne JAHR_2026 = Monatsluecken.monateDes(MonthDay.of(12, 31), 2026).orElseThrow();

    @Test
    void geschaeftsjahrMitUndOhneKalenderjahr() {
        assertThat(JAHR_2026).isEqualTo(new Monatsluecken.Spanne(YearMonth.of(2026, 1), YearMonth.of(2026, 12)));
        assertThat(Monatsluecken.monateDes(MonthDay.of(6, 30), 2027).orElseThrow())
                .isEqualTo(new Monatsluecken.Spanne(YearMonth.of(2026, 7), YearMonth.of(2027, 6)));
        assertThat(Monatsluecken.monateDes(MonthDay.of(2, 29), 2027).orElseThrow().letzter())
                .as("28.02.2027 ist das Monatsende").isEqualTo(YearMonth.of(2027, 2));
    }

    @Test
    void angebrocheneMonateAmRandZaehlenNicht() {
        assertThat(Monatsluecken.monateDes(MonthDay.of(3, 15), 2027).orElseThrow())
                .isEqualTo(new Monatsluecken.Spanne(YearMonth.of(2026, 4), YearMonth.of(2027, 2)));
    }

    @Test
    void monateOhneBelegSeitDemErstenBeleg() {
        Monatsluecken.Ergebnis e = Monatsluecken.pruefen(
                Set.of(YearMonth.of(2026, 3), YearMonth.of(2026, 4), YearMonth.of(2026, 6)), JAHR_2026,
                YearMonth.of(2026, 8));
        assertThat(e.erster()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(e.fehlend()).containsExactly(YearMonth.of(2026, 5), YearMonth.of(2026, 7), YearMonth.of(2026, 8));
    }

    @Test
    void vorDemErstenBelegKeineLuecke() {
        Monatsluecken.Ergebnis e = Monatsluecken.pruefen(Set.of(YearMonth.of(2026, 11)), JAHR_2026,
                YearMonth.of(2026, 12));
        assertThat(e.fehlend()).containsExactly(YearMonth.of(2026, 12));
    }

    @Test
    void ersterBelegImVorjahrZaehltFuerDasGanzeJahr() {
        Monatsluecken.Ergebnis e = Monatsluecken.pruefen(Set.of(YearMonth.of(2025, 12), YearMonth.of(2026, 2)),
                JAHR_2026, YearMonth.of(2026, 3));
        assertThat(e.erster()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(e.fehlend()).containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 3));
    }

    @Test
    void nurAbgeschlosseneMonate() {
        Monatsluecken.Ergebnis laufend = Monatsluecken.pruefen(Set.of(YearMonth.of(2026, 1)), JAHR_2026,
                YearMonth.of(2026, 1));
        assertThat(laufend.fehlend()).isEmpty();
        Monatsluecken.Ergebnis zukunft = Monatsluecken.pruefen(Set.of(YearMonth.of(2026, 1)),
                Monatsluecken.monateDes(MonthDay.of(12, 31), 2027).orElseThrow(), YearMonth.of(2026, 12));
        assertThat(zukunft.fehlend()).as("Geschäftsjahr, das noch nicht begonnen hat").isEmpty();
    }

    @Test
    void ohneDokumentKeinErsterMonat() {
        Monatsluecken.Ergebnis e = Monatsluecken.pruefen(Set.of(), JAHR_2026, YearMonth.of(2026, 12));
        assertThat(e.erster()).isNull();
        assertThat(e.fehlend()).isEmpty();
    }

    @Test
    void abVorDemErstenDokumentLegtDenBeginnFest() {
        Monatsluecken.Ergebnis e = Monatsluecken.pruefen(Set.of(YearMonth.of(2026, 4)), JAHR_2026,
                YearMonth.of(2026, 5), YearMonth.of(2026, 2));
        assertThat(e.fehlend()).containsExactly(YearMonth.of(2026, 2), YearMonth.of(2026, 3), YearMonth.of(2026, 5));
    }
}
