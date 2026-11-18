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
        assertThat(properties.getProperty("archiv.wissen.budget-tokens")).isEqualTo("300000");
        assertThat(properties.getProperty("archiv.wissen.monatsbudget-tokens")).isEqualTo("1000000");
        assertThat(properties.containsKey("archiv.wissen.basis-url")).isFalse();
    }

    @Test
    void leeresModellWirdBeimStartAbgelehnt() {
        assertThatIllegalStateException().isThrownBy(() -> new WissenKonfiguration("", 30_000, 15, 60_000,
                20_000, 12_000, 300_000, 1_000_000));
    }
}
