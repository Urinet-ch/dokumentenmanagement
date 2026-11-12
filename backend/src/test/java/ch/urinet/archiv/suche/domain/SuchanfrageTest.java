package ch.urinet.archiv.suche.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Grenzen und Sortierung der Suchanfrage (Req 14.2, 14.3; Aufgabe 4.5). */
class SuchanfrageTest {

    private static Suchanfrage begriff(String begriff) {
        return new Suchanfrage(begriff, null, null, null, null, null, null, null, null, null, null, null, null, 0, 20);
    }

    private static Suchanfrage art(String art) {
        return new Suchanfrage(null, art, null, null, null, null, null, null, null, null, null, null, null, 0, 20);
    }

    private static Suchanfrage datum(LocalDate von, LocalDate bis) {
        return new Suchanfrage(null, null, null, von, bis, null, null, null, null, null, null, null, null, 0, 20);
    }

    private static Suchanfrage betrag(String von, String bis) {
        return new Suchanfrage(null, null, null, null, null, von == null ? null : new BigDecimal(von),
                bis == null ? null : new BigDecimal(bis), null, null, null, null, null, null, 0, 20);
    }

    private static Suchanfrage tags(List<String> tags) {
        return new Suchanfrage(null, null, null, null, null, null, null, null, tags, null, null, null, null, 0, 20);
    }

    private static Suchanfrage belegnummer(String belegnummer) {
        return new Suchanfrage(null, null, null, null, null, null, null, null, null, null, null, belegnummer, null, 0,
                20);
    }

    private static Suchanfrage seite(int seite, int groesse) {
        return new Suchanfrage(null, null, null, null, null, null, null, null, null, null, null, null, null, seite,
                groesse);
    }

    private static Suchanfrage sortiert(String begriff, Sortierung sortierung) {
        return new Suchanfrage(begriff, null, null, null, null, null, null, null, null, null, null, null, sortierung,
                0, 20);
    }

    @Test
    void leererBegriffZaehltAlsKeinerUndWirdGetrimmt() {
        assertThat(begriff("   ").begriff()).isNull();
        assertThat(begriff("  Rechnung ").begriff()).isEqualTo("Rechnung");
    }

    @Test
    void begriffHoechstens200ZeichenOhneSteuerzeichen() {
        assertThatCode(() -> begriff("a".repeat(200))).doesNotThrowAnyException();
        assertThatThrownBy(() -> begriff("a".repeat(201))).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> begriff("Rech\u0000nung")).isInstanceOf(FachFehler.class);
    }

    @Test
    void artNurAlsCode() {
        assertThatCode(() -> art("AUSGANGSRECHNUNG")).doesNotThrowAnyException();
        for (String falsch : List.of("ausgangsrechnung", "A", "X' or 1=1", "A".repeat(41))) {
            assertThatThrownBy(() -> art(falsch)).as(falsch).isInstanceOf(FachFehler.class);
        }
    }

    @Test
    void zeitraumUndBetragVonNichtNachBis() {
        LocalDate tag = LocalDate.of(2026, 3, 1);
        assertThatCode(() -> datum(tag, tag)).doesNotThrowAnyException();
        assertThatThrownBy(() -> datum(tag.plusDays(1), tag)).isInstanceOf(FachFehler.class);
        assertThatCode(() -> betrag("450.00", "450")).doesNotThrowAnyException();
        assertThatThrownBy(() -> betrag("2", "1")).isInstanceOf(FachFehler.class);
    }

    @Test
    void betragPasstInNumeric12Komma2() {
        assertThatCode(() -> betrag("-9999999999.99", "9999999999.99")).doesNotThrowAnyException();
        assertThatCode(() -> betrag("1.500", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> betrag("10000000000", null)).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> betrag(null, "0.005")).isInstanceOf(FachFehler.class);
    }

    @Test
    void hoechstensZehnTagsOhneLeereUndSteuerzeichen() {
        assertThatCode(() -> tags(Collections.nCopies(10, "t"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> tags(Collections.nCopies(11, "t"))).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> tags(List.of(" "))).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> tags(List.of("a".repeat(101)))).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> tags(List.of("a\nb"))).isInstanceOf(FachFehler.class);
    }

    @Test
    void belegnummerNichtLeerUndHoechstens50Zeichen() {
        assertThatCode(() -> belegnummer("O-2026-005")).doesNotThrowAnyException();
        assertThatThrownBy(() -> belegnummer(" ")).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> belegnummer("1".repeat(51))).isInstanceOf(FachFehler.class);
    }

    @Test
    void seiteUndGroesseBegrenzt() {
        assertThatCode(() -> seite(9_999, 100)).doesNotThrowAnyException();
        assertThatCode(() -> seite(0, 1)).doesNotThrowAnyException();
        for (int[] falsch : new int[][] {{-1, 20}, {10_000, 20}, {0, 0}, {0, 101}}) {
            assertThatThrownBy(() -> seite(falsch[0], falsch[1])).isInstanceOf(FachFehler.class);
        }
    }

    @Test
    void ohneBegriffImmerNachDatumSonstStandardRelevanz() {
        assertThat(sortiert(null, null).wirksameSortierung()).isEqualTo(Sortierung.DATUM);
        assertThat(sortiert(null, Sortierung.RELEVANZ).wirksameSortierung()).isEqualTo(Sortierung.DATUM);
        assertThat(sortiert("Rechnung", null).wirksameSortierung()).isEqualTo(Sortierung.RELEVANZ);
        assertThat(sortiert("Rechnung", Sortierung.DATUM).wirksameSortierung()).isEqualTo(Sortierung.DATUM);
    }
}
