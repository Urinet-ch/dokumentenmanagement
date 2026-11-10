package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                 "vertraulichkeit": "NORMAL", "gesetzliche_pflicht": false}]}""");
        assertThat(KlassenpfadDokumentartSeed.vorlagen(gueltig, "probe")).extracting(DokumentartVorlage::code)
                .containsExactly("A");
        for (String inhalt : List.of(
                "{\"dokumentarten\": [{\"code\": \"A\", \"name\": \"A\", \"vertraulichkeit\": \"NORMAL\", \"farbe\": \"rot\"}]}",
                "{\"arten\": []}",
                "{\"dokumentarten\": []}",
                "{\"dokumentarten\": [{\"name\": \"ohne Code\", \"vertraulichkeit\": \"NORMAL\"}]}")) {
            assertThatThrownBy(() -> KlassenpfadDokumentartSeed.vorlagen(json.readTree(inhalt), "probe")).as(inhalt)
                    .isInstanceOfSatisfying(MandantFehler.class,
                            e -> assertThat(e.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG));
        }
    }
}
