package ch.urinet.archiv.benutzer.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamenssperreHashTest {
    @Test
    void normalisiertUndBerechnetStabilenHmac() {
        var hash = new NamenssperreHash(new byte[32]);
        assertThat(HexFormat.of().formatHex(hash.hash("Probe.Name")))
                .isEqualTo("64e90a8f999270cd4c96d40a083a244b5040b3d9e4da783fbbd6fedf984e6584");
        assertThat(hash.hash("PROBE.NAME")).isEqualTo(hash.hash("probe.name"));
        assertThat(hash.hash("anderer.name")).isNotEqualTo(hash.hash("probe.name"));
    }

    @Test
    void verlangt32ByteSalz() {
        assertThatThrownBy(() -> new NamenssperreHash(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
