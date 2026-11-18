package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OeffentlicheAbfragenTest {

    @Test
    void mindestrelevanzVerwirftSchwacheUndUnbewerteteTreffer() {
        Hybridtreffer unterSchwelle = treffer(null, 0.149);
        Hybridtreffer anSchwelle = treffer(null, 0.15);
        Hybridtreffer ohneBewertung = treffer(null, null);
        Hybridtreffer exakteDokumentnummer = treffer("B-2071-01", null);

        List<Hybridtreffer> belegte = OeffentlicheAbfragen.belegteTreffer(
                new Hybridergebnis(List.of(unterSchwelle, anSchwelle, ohneBewertung, exakteDokumentnummer), false),
                "B-2071-01", 0.15);

        assertThat(belegte).containsExactly(anSchwelle, exakteDokumentnummer);
    }

    @Test
    void exakteDokumentnummerBleibtAuchImVolltextRueckfallBeleg() {
        Hybridtreffer unbewertet = treffer(null, null);
        Hybridtreffer exakteDokumentnummer = treffer("B-2071-01", null);

        List<Hybridtreffer> belegte = OeffentlicheAbfragen.belegteTreffer(
                new Hybridergebnis(List.of(unbewertet, exakteDokumentnummer), true), "B-2071-01", 0.15);

        assertThat(belegte).containsExactly(exakteDokumentnummer);
    }

    private static Hybridtreffer treffer(String belegnummer, Double relevanz) {
        Suchtreffer dokument = new Suchtreffer(UUID.randomUUID(), null, null, null, Instant.EPOCH, null,
                null, List.of(), null, null, null, belegnummer, null, null, null, List.of(), null);
        return new Hybridtreffer(dokument, null, relevanz);
    }
}
