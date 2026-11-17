package ch.urinet.archiv;

import ch.urinet.archiv.integration.adapter.in.kommando.AlteLinksKommando;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlteLinksKommandoTest {

    @Test
    void standardIstTrockenlaufUndArchivMainErkenntDasKommando() {
        List<String> argumente = List.of("importieren", "links.csv", "--mandant", "urinet");

        assertThat(AlteLinksKommando.lesen(argumente)).hasValueSatisfying(aufruf -> {
            assertThat(aufruf.datei()).isEqualTo(Path.of("links.csv"));
            assertThat(aufruf.mandant().wert()).isEqualTo("urinet");
            assertThat(aufruf.ausfuehren()).isFalse();
            assertThat(aufruf.anlass()).isEmpty();
        });
        assertThat(ArchivMain.aufrufPruefen("alte-links", "importieren", "links.csv", "--mandant", "urinet"))
                .isEmpty();
    }

    @Test
    void ausfuehrenErfordertWerUndGrundUndOptionenDuerfenNichtDoppeltSein() {
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "urinet",
                "--ausfuehren"))).isEmpty();
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "urinet",
                "--ausfuehren", "--wer", "Fabian"))).isEmpty();
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "urinet",
                "--ausfuehren", "--wer", "Fabian", "--grund", "Test", "--dry-run"))).isEmpty();
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "urinet",
                "--wer", "Fabian", "--grund", "Test"))).isEmpty();
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "urinet",
                "--mandant", "vhf-gsk"))).isEmpty();
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "urinet",
                "--ausfuehren", "--wer", "Fabian", "--grund", "Test"))).hasValueSatisfying(aufruf -> {
            assertThat(aufruf.ausfuehren()).isTrue();
            assertThat(aufruf.anlass()).hasValueSatisfying(anlass -> {
                assertThat(anlass.wer()).isEqualTo("Fabian");
                assertThat(anlass.grund()).isEqualTo("Test");
            });
        });
    }

    @Test
    void falscheMandantenOderCsvSpaltenWerdenVorDemStartAbgelehnt() {
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv"))).isEmpty();
        assertThat(AlteLinksKommando.lesen(List.of("importieren", "links.csv", "--mandant", "Ungültig"))).isEmpty();
        assertThat(ArchivMain.aufrufPruefen("alte-links", "falsch", "links.csv", "--mandant", "urinet"))
                .isPresent();
    }
}
