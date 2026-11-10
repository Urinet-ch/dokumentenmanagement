package ch.urinet.archiv.mandant.adapter.out.anmeldung;

import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Gültig ist nur genau ein Text, der ein gültiger Mandant-Code ist (Req 1.3). */
class AnmeldungMandantAdapterTest {

    @Test
    void einzelnerText() {
        assertThat(AnmeldungMandantAdapter.auswerten("urinet"))
                .isEqualTo(new MandantAusAnmeldung.Gueltig(new MandantCode("urinet")));
        assertThat(AnmeldungMandantAdapter.auswerten(Json.createValue("vhf-gsk")))
                .isEqualTo(new MandantAusAnmeldung.Gueltig(new MandantCode("vhf-gsk")));
    }

    @Test
    void fehlendLeerMehrwertigOderUngueltig() {
        for (Object wert : new Object[]{null, "", "URINET", "m_urinet; drop", List.of("urinet"),
                Json.createArrayBuilder().add("urinet").add("vhf-gsk").build(), Json.createValue(1)}) {
            assertThat(AnmeldungMandantAdapter.auswerten(wert)).as(String.valueOf(wert))
                    .isInstanceOf(MandantAusAnmeldung.Ungueltig.class);
        }
    }
}
