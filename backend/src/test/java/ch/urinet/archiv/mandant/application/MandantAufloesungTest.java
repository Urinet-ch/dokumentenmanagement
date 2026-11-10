package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mandant nur aus der Anmeldung, Schema nur aus dem Register (Req 1.2, 1.3). */
class MandantAufloesungTest {

    private static final MandantVerzeichnis REGISTER =
            code -> Optional.ofNullable(Map.of("urinet", "m_urinet").get(code.wert()));

    private static MandantAufloesung mit(MandantAusAnmeldung.Ergebnis ergebnis) {
        MandantAusAnmeldung anmeldung = () -> ergebnis;
        return new MandantAufloesung(new MandantKontext(null, anmeldung), anmeldung, REGISTER);
    }

    @Test
    void schemaKommtAusDemRegister() {
        MandantVerzeichnis anderesSchema = code -> Optional.of("m_aus_register");
        MandantAusAnmeldung anmeldung = () -> new MandantAusAnmeldung.Gueltig(new MandantCode("urinet"));
        assertThat(new MandantAufloesung(new MandantKontext(null, anmeldung), anmeldung, anderesSchema).schema())
                .isEqualTo("m_aus_register");
        assertThat(mit(new MandantAusAnmeldung.Gueltig(new MandantCode("urinet"))).pruefen()).isEqualTo(new MandantCode("urinet"));
    }

    @Test
    void ohneAnmeldungKeinMandantenSchema() {
        assertThat(mit(new MandantAusAnmeldung.Anonym()).schema()).isEqualTo(MandantAufloesung.OHNE_MANDANT);
        assertThatThrownBy(() -> mit(new MandantAusAnmeldung.Anonym()).pruefen()).isInstanceOf(MandantVerweigert.class);
    }

    @Test
    void ungueltigUnbekanntOderInaktivWirdVerweigert() {
        assertThatThrownBy(() -> mit(new MandantAusAnmeldung.Ungueltig()).schema()).isInstanceOf(MandantVerweigert.class);
        // inaktive Mandanten liefert das Register nicht (select … where aktiv)
        assertThatThrownBy(() -> mit(new MandantAusAnmeldung.Gueltig(new MandantCode("vhf-gsk"))).schema())
                .isInstanceOf(MandantVerweigert.class);
        assertThatThrownBy(() -> mit(new MandantAusAnmeldung.Gueltig(new MandantCode("vhf-gsk"))).pruefen())
                .isInstanceOf(MandantVerweigert.class);
    }
}
