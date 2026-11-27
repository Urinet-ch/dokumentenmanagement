package ch.urinet.archiv.wissen.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class WissenKonfigurationTest {

    @Test
    void startwerteUndModellbindungSindKonfiguriert() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = WissenKonfigurationTest.class.getResourceAsStream("/application.properties")) {
            assertThat(stream).isNotNull();
            properties.load(stream);
        }

        assertThat(properties.getProperty("archiv.wissen.modell")).isEqualTo("${archiv.ki.chat-modell}");
        assertThat(properties.getProperty("archiv.wissen.budget-tokens")).isEqualTo("600000");
        assertThat(properties.getProperty("archiv.wissen.monatsbudget-tokens")).isEqualTo("1000000");
        assertThat(properties.getProperty("archiv.wissen.auszug-mindest-zeichen")).isEqualTo("20");
        assertThat(properties.getProperty("archiv.wissen.aussage-ueberlappung")).isEqualTo("0.5");
        assertThat(properties.getProperty("archiv.wissen.randzeilen-mindest-anteil")).isEqualTo("0.3");
        assertThat(properties.getProperty("archiv.wissen.tabellen-gruppierung")).isEqualTo("true");
        assertThat(properties.getProperty("archiv.wissen.tabellen-max-gruppen")).isEqualTo("1");
        assertThat(properties.containsKey("archiv.wissen.basis-url")).isFalse();
    }

    @Test
    void leeresModellWirdBeimStartAbgelehnt() {
        assertThatIllegalStateException().isThrownBy(() -> new WissenKonfiguration("", 30_000, 15, 60_000,
                20_000, 12_000, 600_000, 1_000_000));
    }

    @Test
    void tabellengruppenSindBegrenzt() {
        assertThatIllegalStateException().isThrownBy(() -> new WissenKonfiguration("test-modell", 30_000, 15,
                60_000, 20_000, 12_000, 600_000, 1_000_000, 20, 0.5, 0.3, true, 41));
    }
}
