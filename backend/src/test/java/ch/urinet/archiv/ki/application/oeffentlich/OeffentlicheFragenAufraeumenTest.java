package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 03, Req 6.5: Aufbewahrung je Mandant, Standard 90 Tage, 1 bis 365; Unsinn bricht den Start ab. */
class OeffentlicheFragenAufraeumenTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");
    private static final Instant JETZT = Instant.parse("2071-06-30T12:00:00Z");

    private static OeffentlicheFragenAufraeumen mit(String standard, Map<String, String> eigene) {
        return new OeffentlicheFragenAufraeumen(null, null, standard, List.copyOf(eigene.keySet()),
                name -> Optional.ofNullable(eigene.get(name)));
    }

    @Test
    void standardGiltOhneEinstellungEinMandantKannAbweichen() {
        var a = mit("90", Map.of("archiv.mandant.vhf-gsk.chat-aufbewahrung-tage", "30"));
        assertThat(a.tage(URINET)).isEqualTo(90);
        assertThat(a.tage(VHF)).isEqualTo(30);
        assertThat(a.grenze(URINET, JETZT)).isEqualTo(Instant.parse("2071-04-01T12:00:00Z"));
        assertThat(a.grenze(VHF, JETZT)).isEqualTo(Instant.parse("2071-05-31T12:00:00Z"));
    }

    @Test
    void grenzenWerteSindErlaubt() {
        assertThat(mit("1", Map.of()).tage(URINET)).isEqualTo(1);
        assertThat(mit("365", Map.of("archiv.mandant.urinet.chat-aufbewahrung-tage", " 365 ")).tage(URINET)).isEqualTo(365);
    }

    @Test
    void unsinnBrichtDenStartAbOhneDenWertZuNennen() {
        for (String falsch : new String[]{"0", "-5", "366", "9999999999", "abc", "P90D", "", "3.5"}) {
            assertThatThrownBy(() -> mit(falsch, Map.of())).as("Standard " + falsch)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("archiv.webseite.chat.aufbewahrung-tage").hasMessageNotContaining("9999");
            assertThatThrownBy(() -> mit("90", Map.of("archiv.mandant.vhf-gsk.chat-aufbewahrung-tage", falsch)))
                    .as("Mandant " + falsch).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("archiv.mandant.vhf-gsk.chat-aufbewahrung-tage");
        }
    }

    @Test
    void andereSchluesselWerdenIgnoriert() {
        var a = mit("90", Map.of("archiv.mandant.vhf-gsk.eigene-arten", "x", "archiv.mandant.VHF.chat-aufbewahrung-tage", "1"));
        assertThat(a.tage(VHF)).isEqualTo(90);
    }
}
