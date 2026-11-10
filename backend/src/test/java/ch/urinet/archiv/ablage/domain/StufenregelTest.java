package ch.urinet.archiv.ablage.domain;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stufen-Regel (Req 6.11, tasks.md 2.3): ab Stufe B nur COMPLIANCE ohne Höchstfristen und nicht der Präfix von A. */
class StufenregelTest {

    private static final Stufenregel.Ablageeinstellungen WIE_A = new Stufenregel.Ablageeinstellungen(
            Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)), Optional.of(Duration.ofDays(1)),
            Stufenregel.PRAEFIX_STUFE_A);

    @Test
    void ohneWertGiltStufeB() {
        assertThat(Stufe.aus(Optional.empty())).isEqualTo(Stufe.B);
        assertThat(Stufe.aus(Optional.of(" "))).isEqualTo(Stufe.B);
        assertThat(Stufe.aus(Optional.of("a"))).isEqualTo(Stufe.A);
        assertThatThrownBy(() -> Stufe.aus(Optional.of("C"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWerteAusApplicationPropertiesSindStufeBOhneVerstoss() throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(getClass().getClassLoader().getResource("application.properties")))
                .build();
        var einstellungen = new Stufenregel.Ablageeinstellungen(
                config.getValue("archiv.ablage.manifest-sperrmodus", Sperrmodus.class),
                config.getOptionalValue("archiv.ablage.manifest-max-sperrfrist", Duration.class),
                config.getOptionalValue("archiv.ablage.max-sperrfrist", Duration.class),
                config.getValue("archiv.ablage.bucket-praefix", String.class));
        Stufe stufe = Stufe.aus(config.getOptionalValue("archiv.stufe", String.class));

        assertThat(stufe).isEqualTo(Stufe.B);
        assertThat(Stufenregel.verstoesse(stufe, einstellungen)).isEmpty();
    }

    @Test
    void stufeBNenntJedenVerstoss() {
        assertThat(Stufenregel.verstoesse(Stufe.B, WIE_A)).containsExactly("archiv.ablage.manifest-sperrmodus",
                "archiv.ablage.manifest-max-sperrfrist", "archiv.ablage.max-sperrfrist", "archiv.ablage.bucket-praefix");
        assertThatThrownBy(() -> Stufenregel.pruefe(Stufe.B, WIE_A)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ablage ab Stufe B");
    }

    @Test
    void stufeADarfAbweichen() {
        assertThat(Stufenregel.verstoesse(Stufe.A, WIE_A)).isEmpty();
    }
}
