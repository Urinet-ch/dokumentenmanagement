package ch.urinet.archiv.mandant.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandantVerwaltungsrollenTest {

    @Test
    void standardrollenUndMandantenspezifischeRollenWerdenAbgebildet() {
        assertThat(MandantVerwaltungsrollen.STANDARD.werte())
                .containsExactly("inhaber", "mitarbeiter", "treuhaender");
        assertThat(new MandantVerwaltungsrollen(List.of("geschaeftsstelle")).alsArray())
                .containsExactly("geschaeftsstelle");
    }

    @Test
    void leereDoppelteUndUnbekannteRollenWerdenAbgelehnt() {
        for (List<String> rollen : List.of(
                List.<String>of(),
                List.of("inhaber", "inhaber"),
                List.of("unbekannt"),
                Arrays.asList((String) null))) {
            assertThatThrownBy(() -> new MandantVerwaltungsrollen(rollen))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
