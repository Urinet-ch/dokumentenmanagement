package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KlassenpfadDokumentartSeedTest {

    private final ObjectMapper json = new ObjectMapper();
    private final KlassenpfadDokumentartSeed seed = new KlassenpfadDokumentartSeed(json);

    @Test
    void urinetSeedOhneEntwurfsfelder() {
        List<DokumentartVorlage> arten = seed.lesen(new MandantCode("urinet"));
        assertThat(arten).hasSize(11);
        DokumentartVorlage projekt = arten.stream().filter(a -> a.code().equals("PROJEKTDOKUMENT")).findFirst().orElseThrow();
        assertThat(projekt.aufbewahrungJahre()).isNull();
        assertThat(projekt.aufbewahrungAb()).isNull();
        assertThat(projekt.gesetzlichePflicht()).isFalse();
        DokumentartVorlage offerte = arten.stream().filter(a -> a.code().equals("OFFERTE")).findFirst().orElseThrow();
        assertThat(offerte.kiHinweise()).isEmpty();
    }

    @Test
    void fehlenderSeedBrichtAb() {
        assertThatThrownBy(() -> seed.lesen(new MandantCode("gibt-es-nicht")))
                .isInstanceOfSatisfying(MandantFehler.class, e -> assertThat(e.grund()).isEqualTo(MandantFehler.Grund.SEED_FEHLT));
    }

    @Test
    void unbekanntesFeldBrichtAbUnterstrichFaelltWeg() throws Exception {
        var gueltig = json.readTree("""
                {"_fragen": ["?"], "dokumentarten": [{"code": "A", "name": "A", "_notiz": "x",
                 "vertraulichkeit": "NORMAL", "aufbewahrung_jahre": null, "gesetzliche_pflicht": false}]}""");
        assertThat(KlassenpfadDokumentartSeed.vorlagen(gueltig, "probe")).extracting(DokumentartVorlage::code)
                .containsExactly("A");
    }

    static Stream<String> ungueltigeSeeds() {
        return Stream.of(
                "{\"dokumentarten\": [{\"code\": \"A\", \"name\": \"A\", \"vertraulichkeit\": \"NORMAL\", \"farbe\": \"rot\"}]}",
                "{\"arten\": []}",
                "{\"dokumentarten\": []}",
                "{\"dokumentarten\": [{\"name\": \"ohne Code\", \"vertraulichkeit\": \"NORMAL\"}]}",
                art("\"aufbewahrung_jahre\": 10"),
                art("\"aufbewahrung_jahre\": 10, \"gesetzliche_pflicht\": \"ja\""),
                art("\"aufbewahrung_jahre\": 10, \"gesetzliche_pflicht\": null"),
                art("\"gesetzliche_pflicht\": true"),
                art("\"aufbewahrung_jahre\": \"10\", \"gesetzliche_pflicht\": true"),
                art("\"aufbewahrung_jahre\": 1.5, \"gesetzliche_pflicht\": true"));
    }

    @ParameterizedTest
    @MethodSource("ungueltigeSeeds")
    void ungueltigerSeedBrichtAb(String inhalt) {
        assertSeedUngueltig(inhalt);
    }

    static Stream<String> falschTypisierteOptionaleFelder() {
        String rest = ", \"aufbewahrung_jahre\": null, \"gesetzliche_pflicht\": false";
        return Stream.of(
                art("\"gruppen\": \"finanz\"" + rest),
                art("\"pflichtfelder\": \"titel\"" + rest),
                art("\"aufbewahrung_ab\": 7" + rest),
                art("\"aufbewahrung_ab\": \"\"" + rest),
                art("\"aufbewahrung_ab\": \"  \"" + rest),
                art("\"aktiv\": \"ungültig\"" + rest),
                // ausdrückliches null gilt nicht als «fehlt»
                art("\"gruppen\": null" + rest),
                art("\"pflichtfelder\": null" + rest),
                art("\"aktiv\": null" + rest));
    }

    @ParameterizedTest
    @MethodSource("falschTypisierteOptionaleFelder")
    void falschTypisierteOptionaleSeedFelderBringenAb(String inhalt) {
        assertSeedUngueltig(inhalt);
    }

    private void assertSeedUngueltig(String inhalt) {
        assertThatThrownBy(() -> KlassenpfadDokumentartSeed.vorlagen(json.readTree(inhalt), "probe")).as(inhalt)
                .isInstanceOfSatisfying(MandantFehler.class,
                        e -> assertThat(e.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG));
    }

    private static String art(String felder) {
        return "{\"dokumentarten\": [{\"code\": \"A\", \"name\": \"A\", \"vertraulichkeit\": \"NORMAL\", " + felder + "}]}";
    }
}
