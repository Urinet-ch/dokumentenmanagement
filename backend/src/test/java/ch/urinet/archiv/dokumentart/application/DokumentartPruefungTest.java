package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Seed-Frist gilt nur im Seed-Weg (Review PR #81, S3): {@link DokumentartPruefung#pruefen} (API) prüft mit
 * {@code muster-zeitgrenze}, {@link DokumentartPruefung#seedPruefen} mit {@code seed-zeitgrenze}. Wer die Methode
 * aufrufen darf, sichert {@code ArchitekturTest.seedFristNurImSeed}.
 */
class DokumentartPruefungTest {

    private static final Dokumentart ART = new Dokumentart("SPENDE", "Spende", null, List.of(), List.of(),
            Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.NORMAL, new Aufbewahrungsregel(10, AufbewahrungAb.GESCHAEFTSJAHR),
            false, true);

    private final List<String> aufrufe = new ArrayList<>();
    private final DokumentartPruefung pruefung = new DokumentartPruefung(new ZusatzSchemaPruefer() {
        @Override
        public Set<String> schemaPruefen(String schemaJson) {
            aufrufe.add("schemaPruefen");
            return Set.of();
        }

        @Override
        public Set<String> seedSchemaPruefen(String schemaJson) {
            aufrufe.add("seedSchemaPruefen");
            return Set.of();
        }

        @Override
        public void wertePruefen(String schemaJson, String werteJson) {
            aufrufe.add("wertePruefen");
        }
    });

    @Test
    void apiPrueftMitDerFristDerApi() {
        pruefung.pruefen(ART);
        assertThat(aufrufe).containsExactly("schemaPruefen");
    }

    @Test
    void seedPrueftMitDerSeedFrist() {
        pruefung.seedPruefen(ART);
        assertThat(aufrufe).containsExactly("seedSchemaPruefen");
    }
}
