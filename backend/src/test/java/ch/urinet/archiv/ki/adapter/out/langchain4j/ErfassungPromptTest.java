package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadDokumentartSeed;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot gegen den Spike-Stand (Gate 0, {@code docs/spikes/golden-ergebnis.md}): Prompt v6, Antwortschema, Reihenfolge
 * der Art-Codes und Bildweg ergeben dieselbe Kennung wie im Gate-Lauf. Ändert sich die Kennung, hat sich Prompt, Schema
 * oder Bildweg geändert; das braucht einen neuen Golden-Lauf (tasks.md 3.2, design.md «KI»). Synthetische Werte wie im
 * Gate-Lauf (Mandantenname aus {@code tools/golden_synth}, Arten aus der Seed-Tabelle in design.md).
 */
class ErfassungPromptTest {

    static final String GATE_KENNUNG = "v6-7a39224f2f5e";
    /** Name des Mandanten im Gate-Lauf; das Archiv liest ihn aus {@code public.mandant.name}. */
    static final String MANDANT = "UriNet Aschwanden";
    static final Set<String> EIGENE = Set.of("AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG");
    static final List<ArtEintrag> SEED = List.of(
            new ArtEintrag("AUSGANGSRECHNUNG", "Ausgangsrechnung", "INTERN"),
            new ArtEintrag("OFFERTE", "Offerte", "NORMAL"),
            new ArtEintrag("LIEFERANTENBELEG", "Lieferantenbeleg", "INTERN"),
            new ArtEintrag("QUITTUNG", "Quittung / Spesen", "INTERN"),
            new ArtEintrag("LOHNABRECHNUNG", "Lohnabrechnung", "PERSONAL"),
            new ArtEintrag("VERTRAG", "Vertrag", "PERSONAL"),
            new ArtEintrag("VERSICHERUNG", "Versicherung", "INTERN"),
            new ArtEintrag("BEHOERDE", "Behörde", "INTERN"),
            new ArtEintrag("BANK", "Bank", "INTERN"),
            new ArtEintrag("PROJEKTDOKUMENT", "Projektdokument", "NORMAL"),
            new ArtEintrag("KORRESPONDENZ", "Korrespondenz", "INTERN"));

    @Test
    void kennungEntsprichtDemGateLauf() {
        String system = ErfassungPrompt.system(MANDANT, ErfassungPrompt.artListe(SEED, EIGENE, MANDANT));

        String kennung = ErfassungPrompt.hash(system, LangChain4jKiAdapter.schema(), LangChain4jKiAdapter.codes(),
                ErfassungPrompt.BILDWEG);

        assertThat(kennung).isEqualTo(GATE_KENNUNG).isEqualTo(ErfassungPrompt.GATE_KENNUNG);
    }

    /**
     * Produktpfad (Review PR #93): Arten aus dem Seed des Mandanten {@code urinet}, gefiltert und geordnet wie in
     * {@code MetadatenExtraktion.arten()}, eigene Arten aus {@code application.properties}, Antwortschema und Codes aus
     * dem Adapter. Weicht der Text ab, warnt das Archiv bei jedem Aufruf; hier bricht der Build.
     */
    @Test
    void produktpfadMitSeedUrinetErgibtDieGateKennung() throws Exception {
        List<ArtEintrag> aktiv = new KlassenpfadDokumentartSeed(new ObjectMapper()).lesen(new MandantCode("urinet"))
                .stream()
                .filter(DokumentartVorlage::aktiv)
                .map(v -> new ArtEintrag(v.code(), v.name(), v.vertraulichkeit()))
                .toList();
        List<ArtEintrag> arten = ErfassungPrompt.schemaArten(aktiv, LangChain4jKiAdapter.codes());
        Properties properties = new Properties();
        try (var ein = getClass().getResourceAsStream("/application.properties")) {
            properties.load(new InputStreamReader(ein, StandardCharsets.UTF_8));
        }
        Set<String> eigene = Set.of(properties.getProperty("archiv.mandant.urinet.eigene-arten").split(","));

        String system = ErfassungPrompt.system(MANDANT, ErfassungPrompt.artListe(arten, eigene, MANDANT));

        assertThat(ErfassungPrompt.hash(system, LangChain4jKiAdapter.schema(), LangChain4jKiAdapter.codes(),
                ErfassungPrompt.BILDWEG)).isEqualTo(ErfassungPrompt.GATE_KENNUNG);
    }

    @Test
    void eigeneArtenStehenZuletzt() {
        List<String> zeilen = ErfassungPrompt.artListe(SEED, EIGENE, MANDANT).lines().toList();

        assertThat(zeilen).hasSize(11);
        assertThat(zeilen.getFirst()).startsWith("- LIEFERANTENBELEG: Lieferantenbeleg");
        assertThat(zeilen.subList(8, 11)).allSatisfy(z -> assertThat(z).endsWith("(stellt der Mandant selbst aus)"));
    }
}
