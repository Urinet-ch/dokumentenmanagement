package ch.urinet.archiv.spike.golden;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

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

    static final String INFOMANIAK = "https://api.infomaniak.com/2/ai/113354/openai/v1";

    @org.junit.jupiter.api.Test
    void abweichendeClientUrlWirdAbgelehnt() {
        Map<String, String> konfiguration = Map.of(
                "archiv.ki.basis-url", INFOMANIAK,
                "quarkus.langchain4j.openai.base-url", "http://localhost:1234/v1");
        assertThatThrownBy(() -> Hostpruefung.pruefeKonfiguration(k -> Optional.ofNullable(konfiguration.get(k))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarkus.langchain4j.openai.base-url").hasMessageContaining("localhost");
    }

    @org.junit.jupiter.api.Test
    void abweichendeArchivUrlWirdAbgelehnt() {
        Map<String, String> konfiguration = Map.of(
                "archiv.ki.basis-url", "https://api.infomaniak.com.example.org/v1",
                "quarkus.langchain4j.openai.base-url", INFOMANIAK);
        assertThatThrownBy(() -> Hostpruefung.pruefeKonfiguration(k -> Optional.ofNullable(konfiguration.get(k))))
                .hasMessageContaining("archiv.ki.basis-url");
    }

    @org.junit.jupiter.api.Test
    void fehlendeClientUrlWirdAbgelehnt() {
        Map<String, String> konfiguration = Map.of("archiv.ki.basis-url", INFOMANIAK);
        assertThatThrownBy(() -> Hostpruefung.pruefeKonfiguration(k -> Optional.ofNullable(konfiguration.get(k))))
                .hasMessageContaining("quarkus.langchain4j.openai.base-url");
    }

    @org.junit.jupiter.api.Test
    void beideUrlsBeiInfomaniakSindZulaessig() {
        Hostpruefung.pruefeKonfiguration(k -> Optional.of(INFOMANIAK));
    }
}
