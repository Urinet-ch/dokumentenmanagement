package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.dokumentart.adapter.out.schema.NetworkntZusatzSchemaPruefer;
import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.konfiguration.adapter.out.persistence.PanacheKonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.adapter.out.schema.JsonKonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Art;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Aufbewahrung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Kennungen;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.SeedPruefung;
import ch.urinet.archiv.mandant.application.WissensquelleSeed;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KonfigurationWissensquelleSeedTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ZusatzSchemaPruefer zusatzSchema =
            new NetworkntZusatzSchemaPruefer(Duration.ofSeconds(2), Duration.ofSeconds(2));
    private final WissensquelleSeed wissensquelleSeed = new KlassenpfadWissensquelleSeed(json);
    private final KonfigurationSeed seed = new KlassenpfadKonfigurationSeed(
            new JsonKonfigurationsdateiCodec(json, zusatzSchema, new PanacheKonfigurationVersionsRepository(json)),
            wissensquelleSeed, zusatzSchema);

    @Test
    void gemeinsamerSeedEnthaeltDieVorgabenOhneMandantenspezifischeFelder() throws Exception {
        DokumentartVorlage art = wissensquelleSeed.lesen();

        assertThat(art.code()).isEqualTo("WISSENSQUELLE");
        assertThat(art.name()).isEqualTo("Wissensquelle");
        assertThat(art.gruppen()).containsExactly("frei");
        assertThat(art.pflichtfelder()).containsExactly("titel", "datum");
        assertThat(art.vertraulichkeit()).isEqualTo("NORMAL");
        assertThat(art.aufbewahrungJahre()).isNull();
        assertThat(art.gesetzlichePflicht()).isFalse();
        assertThat(art.kiVorschlag()).isFalse();

        var schema = json.readTree(art.zusatzSchemaJson());
        assertThat(schema.path("properties").size()).isEqualTo(1);
        assertThat(schema.path("properties").has("herausgeber")).isTrue();
        assertThat(schema.path("properties").path("herausgeber").path("type").asText()).isEqualTo("string");
        assertThat(schema.has("zugriff")).isFalse();
        assertThat(schema.has("veroeffentlicht")).isFalse();
    }

    @Test
    void vollstaendigerMandantSeedWirdUmFehlendeWissensquelleErgaenzt() {
        Konfiguration urinet = seed.lesen(new MandantCode("urinet"));

        assertThat(urinet.arten()).hasSize(12).extracting(Art::code).contains("WISSENSQUELLE", "AUSGANGSRECHNUNG");
        assertThat(urinet.einstellungen().geschaeftsjahrEnde()).isEqualTo("12-31");
        assertThat(urinet.regeln()).hasSize(3);
        assertThat(urinet.arten()).filteredOn(art -> art.code().equals("WISSENSQUELLE")).singleElement()
                .satisfies(art -> {
                    assertThat(art.gruppen()).containsExactly("frei");
                    assertThat(art.kiVorschlag()).isFalse();
                    assertThat(art.zusatzSchema()).contains("herausgeber");
                });
    }

    @Test
    void vorhandeneWissensquelleAusMandantenSeedWirdNichtUeberschrieben() {
        Konfiguration vhf = seed.lesen(new MandantCode("vhf-gsk"));

        assertThat(vhf.arten()).hasSize(2).extracting(Art::code)
                .contains("PROBE_VHF_BULLETIN", "WISSENSQUELLE");
        assertThat(vhf.arten()).filteredOn(art -> art.code().equals("WISSENSQUELLE")).singleElement()
                .satisfies(art -> {
                    assertThat(art.gruppen()).containsExactly("frei");
                    assertThat(art.kiVorschlag()).isFalse();
                    assertThat(art.zusatzSchema()).contains("zugriff", "veroeffentlicht", "herausgeber");
                });
    }

    @Test
    void mandantenSeedMitAbweichenderWissensquelleWirdAbgelehnt() {
        List<DokumentartVorlage> abweichend = List.of(
                wissensquelle("Mit KI", List.of("titel", "datum"), null, true),
                wissensquelle("Mit Frist", List.of("titel", "datum"), 10, false),
                wissensquelle("Ohne Datum", List.of("titel"), null, false));

        for (DokumentartVorlage art : abweichend) {
            assertThatThrownBy(() -> new SeedPruefung(null).pruefen(new MandantCode("mf-wq"), List.of(art)))
                    .isInstanceOfSatisfying(MandantFehler.class,
                            f -> assertThat(f.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG));
        }
    }

    @Test
    void konfiguratorErgaenztNurWennWissensquelleFehlt() {
        Art vorhandene = new Art("WISSENSQUELLE", "Mandanteneigener Name", null, List.of("frei"),
                List.of("titel", "datum", "partner"), Konfiguration.OHNE_ZUSATZ, "NORMAL",
                new Aufbewahrung(null, null), false, true);
        Konfiguration konfiguration = new Konfiguration(1,
                new Einstellungen("12-31", new Kennungen(null, List.of())), List.of(vorhandene));

        Konfiguration ergebnis = KlassenpfadKonfigurationSeed.mitWissensquelleWennFehlt(
                konfiguration, wissensquelleSeed, zusatzSchema);

        assertThat(ergebnis.arten()).containsExactly(vorhandene);
    }

    private static DokumentartVorlage wissensquelle(String name, List<String> pflichtfelder, Integer jahre,
                                                    boolean kiVorschlag) {
        return new DokumentartVorlage("WISSENSQUELLE", name, null, List.of("frei"), pflichtfelder, null, "NORMAL",
                jahre, jahre == null ? null : "BELEGDATUM", jahre != null, true, false, List.of(), null, null,
                kiVorschlag, List.of(), null, 0, null);
    }
}
