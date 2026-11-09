package ch.urinet.archiv.spike.golden;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 16.22: Golden-Lauf nur per https gegen api.infomaniak.com. */
class HostpruefungTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.infomaniak.com/2/ai/113354/openai/v1",
            "https://API.Infomaniak.com/2/ai/113354/openai/v1",
            "https://api.infomaniak.com:443/2/ai/113354/openai/v1"})
    void zulaessig(String url) {
        assertThat(Hostpruefung.zulaessig(url)).isTrue();
        Hostpruefung.pruefe(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:1234/v1",
            "https://localhost/v1",
            "http://127.0.0.1:1234/v1",
            "http://api.infomaniak.com/2/ai/113354/openai/v1",
            "https://api.infomaniak.com.example.org/2/ai/113354/openai/v1",
            "https://user@api.infomaniak.com/2/ai/113354/openai/v1",
            "https://user:pw@api.infomaniak.com/2/ai/113354/openai/v1",
            "https://xapi.infomaniak.com/v1",
            "https://infomaniak.com/v1",
            "ftp://api.infomaniak.com/v1",
            "",
            "kein url"})
    void abgelehnt(String url) {
        assertThat(Hostpruefung.zulaessig(url)).isFalse();
        assertThatThrownBy(() -> Hostpruefung.pruefe(url)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("113354");
    }

    @org.junit.jupiter.api.Test
    void nullWirdAbgelehnt() {
        assertThat(Hostpruefung.zulaessig(null)).isFalse();
    }
}
