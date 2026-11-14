package ch.urinet.archiv.suche.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** RRF der Hybridsuche (Spec 02, Req 2.2; design.md Schritt 3): Formel, Ränge ab 1, Gleichstand, Auswahl. */
class RrfTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Test
    void formelMitKSechzigUndRangAbEins() {
        assertThat(Rrf.K).isEqualTo(60);
        assertThat(Rrf.wert(1, null)).isCloseTo(1.0 / 61, within(1e-15));
        assertThat(Rrf.wert(null, 3)).isCloseTo(1.0 / 63, within(1e-15));
        assertThat(Rrf.wert(2, 5)).isCloseTo(1.0 / 62 + 1.0 / 65, within(1e-15));
        assertThat(Rrf.wert(null, null)).isZero();
    }

    @Test
    void inBeidenListenSchlaegtEinenSpitzenplatzInNurEinerListe() {
        // A: Volltext 1; B: Volltext 2 und Vektor 1; C: nur Vektor 2
        List<UUID> geordnet = Rrf.ordnen(List.of(A, B), List.of(B, C));
        assertThat(geordnet).containsExactly(B, A, C);
    }

    @Test
    void nurInEinerListeZaehltDerRangDort() {
        assertThat(Rrf.ordnen(List.of(), List.of(C, A, B))).containsExactly(C, A, B);
        assertThat(Rrf.ordnen(List.of(B, A), List.of())).containsExactly(B, A);
    }

    @Test
    void gleichstandEntscheidetErstDerVolltextRangDannDieId() {
        // A: Volltext 1, D: Vektor 1 -> gleicher Wert 1/61; A hat einen Volltext-Rang
        assertThat(Rrf.ordnen(List.of(A), List.of(D))).containsExactly(A, D);
        // A (Volltext 1, Vektor 3) und B (Volltext 3, Vektor 1) haben denselben Wert
        List<UUID> geordnet = Rrf.ordnen(List.of(A, C, B), List.of(B, D, A));
        assertThat(Rrf.wert(1, 3)).isEqualTo(Rrf.wert(3, 1));
        assertThat(geordnet).containsExactly(A, B, C, D);
        assertThat(Rrf.ordnen(List.of(A, B), List.of(B, A))).containsExactly(A, B);
        assertThat(Rrf.ordnen(List.of(B, A), List.of(A, B))).containsExactly(B, A);
    }

    @Test
    void ergebnisIstDeterministisch() {
        List<UUID> volltext = IntStream.range(0, 50).mapToObj(i -> new UUID(1, i)).toList();
        List<UUID> vektor = IntStream.range(0, 50).mapToObj(i -> new UUID(1, 49 - i)).toList();
        assertThat(Rrf.ordnen(volltext, vektor)).isEqualTo(Rrf.ordnen(volltext, vektor))
                .hasSize(50).startsWith(new UUID(1, 0), new UUID(1, 49));
    }

    @Test
    void auswahlNimmtDieBestenDreissigOhneDieAusgenommenen() {
        List<UUID> volltext = IntStream.range(0, 50).mapToObj(i -> new UUID(2, i)).toList();
        List<UUID> vektor = IntStream.range(0, 50).mapToObj(i -> new UUID(3, i)).toList();
        List<UUID> geordnet = Rrf.ordnen(volltext, vektor);
        assertThat(geordnet).hasSize(100);
        List<UUID> auswahl = Rrf.auswahl(geordnet, Set.of(new UUID(2, 0)), 30);
        assertThat(auswahl).hasSize(30).doesNotContain(new UUID(2, 0))
                .containsExactlyElementsOf(geordnet.subList(1, 31));
        assertThat(Rrf.auswahl(List.of(A, B), Set.of(), 30)).containsExactly(A, B);
        assertThat(Rrf.auswahl(List.of(A, B), Set.of(), 0)).isEmpty();
    }
}
