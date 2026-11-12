package ch.urinet.archiv.ki.adapter.out;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.smallrye.config.Expressions;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Konfigurationstest Aufgabe 1.11 (Req 16.20, 16.21, 16.22; ADR-012): reine Prüflogik ohne Quarkus-Start und ohne
 * aktives Profil {@code lokal}; Profilwerte aus {@code application.properties} per {@code SmallRyeConfigBuilder} (Quarkus-Variante).
 */
class KiBasisUrlPruefungTest {

    private static final String INFOMANIAK_URL = "https://api.infomaniak.com/2/ai/1/openai/v1";
    private static final List<String> PROD = List.of("prod");
    private static final List<String> TEST = List.of("test");
    private static final List<String> LOKAL = List.of("lokal", "dev");
    private static final OptionalInt OHNE_WIREMOCK = OptionalInt.empty();
    private static final OptionalInt WIREMOCK = OptionalInt.of(8089);

    // Req 16.21: abgelehnt, die Meldung nennt nur Host und Profil

    @Test
    void fremderHostBrichtAb() {
        assertAbgelehnt(PROD, "https://api.openai.com/v1", OHNE_WIREMOCK, "api.openai.com", "prod");
    }

    @Test
    void suffixTrickBrichtAb() {
        assertAbgelehnt(PROD, "https://api.infomaniak.com.example.org/v1", OHNE_WIREMOCK,
                "api.infomaniak.com.example.org", "prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://geheim@api.infomaniak.com/2/ai/1/openai/v1",
            "https://api.infomaniak.com@example.org/v1"})
    void benutzerangabeInDerUriBrichtAb(String url) {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(PROD, url, false, OHNE_WIREMOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("geheim")
                .hasMessageNotContaining("/v1");
    }

    @Test
    void benutzerangabeAuchImProfilLokalAbgelehnt() {
        assertAbgelehnt(LOKAL, "http://geheim@localhost:1234/v1", OHNE_WIREMOCK, "localhost", "lokal,dev");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:1234/v1", "http://127.0.0.1:1234/v1", "https://localhost/v1"})
    void localhostImProfilProdBrichtAb(String url) {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(PROD, url, false, OHNE_WIREMOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Profil prod.");
    }

    @Test
    void localhostMitPort1234ImProfilTestBrichtAb() {
        assertAbgelehnt(TEST, "http://localhost:1234/v1", WIREMOCK, "localhost", "test");
        assertAbgelehnt(TEST, "http://localhost:1234/v1", OptionalInt.of(1234), "localhost", "test");
    }

    @Test
    void localhostImProfilTestNurMitDemWireMockPort() {
        assertAbgelehnt(TEST, "http://localhost:8089/v1", OHNE_WIREMOCK, "localhost", "test");
        assertAbgelehnt(TEST, "http://localhost:9999/v1", WIREMOCK, "localhost", "test");
    }

    @Test
    void fremderHostInDerRerankerUrlBrichtAb() {
        SmallRyeConfig config = konfiguration("prod", Map.of("archiv.ki.reranker-url", "https://api.cohere.com/v2/rerank"));
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(config.getProfiles(), config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Host api.cohere.com,")
                .hasMessageNotContaining("/v2/rerank");
    }

    @Test
    void fremderHostInDerLangchainBasisUrlBrichtAb() {
        SmallRyeConfig config = konfiguration("prod",
                Map.of("quarkus.langchain4j.openai.base-url", "https://api.openai.com/v1"));
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(config.getProfiles(), config))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Host api.openai.com,");
    }

    // Aufgabe 3.2: benannte LangChain4j-Clients (quarkus.langchain4j.openai.<name>.*), auch mit Stub

    @Test
    void benannterClientOhneBasisUrlBrichtAbAuchMitStub() {
        SmallRyeConfig config = konfiguration("dev", Map.of());
        // ohne eigene base-url gilt für den benannten Client der Standard von LangChain4j
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefeClients(config.getProfiles(), config, INFOMANIAK_URL,
                Map.of("zweiter", "https://api.openai.com/v1/")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Host api.openai.com,");
    }

    @Test
    void benannterClientMitInfomaniakGelingt() {
        SmallRyeConfig config = konfiguration("prod", Map.of());
        assertThatCode(() -> KiBasisUrlPruefung.pruefeClients(config.getProfiles(), config, INFOMANIAK_URL,
                Map.of("zweiter", INFOMANIAK_URL))).doesNotThrowAnyException();
    }

    @Test
    void standardClientMitFremderUrlBrichtAb() {
        SmallRyeConfig config = konfiguration("prod", Map.of());
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefeClients(config.getProfiles(), config,
                "https://api.openai.com/v1/", Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void leereUrlOhneStubBrichtAb() {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(PROD, "", false, OHNE_WIREMOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void gesetzteFremdeUrlBrichtAuchMitStubAb() {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(List.of("dev"), "http://localhost:1234/v1", true,
                OHNE_WIREMOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Profil dev.");
    }

    // Req 16.22: lokal zusammen mit prod

    @Test
    void lokalZusammenMitProdBrichtAb() {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(List.of("lokal", "prod"), "http://localhost:1234/v1",
                false, OHNE_WIREMOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lokal,prod");
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(List.of("prod", "lokal"), INFOMANIAK_URL, false,
                OHNE_WIREMOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lokalImGepacktenArtefaktGiltAlsProdUndBrichtAb() {
        List<String> profile = KiBasisUrlPruefung.wirksameProfile(LOKAL, LaunchMode.NORMAL);
        assertThat(profile).contains("lokal", "prod");
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefeProfile(profile)).isInstanceOf(IllegalStateException.class);
        assertThat(KiBasisUrlPruefung.wirksameProfile(LOKAL, LaunchMode.DEVELOPMENT)).isEqualTo(LOKAL);
    }

    @Test
    void cephZusammenMitProdBrichtAb() {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefeProfile(List.of("prod", "ceph")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ceph");
        List<String> gepackt = KiBasisUrlPruefung.wirksameProfile(List.of("ceph"), LaunchMode.NORMAL);
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefeProfile(gepackt)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> KiBasisUrlPruefung.pruefeProfile(List.of("test", "ceph"))).doesNotThrowAnyException();
    }

    // zulässig

    @ParameterizedTest
    @ValueSource(strings = {INFOMANIAK_URL, "https://API.Infomaniak.COM/2/ai/1/cohere/v2/rerank",
            "HTTPS://api.infomaniak.com/2/ai/1/openai/v1"})
    void infomaniakImProfilProdGelingt(String url) {
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(PROD, url, false, OHNE_WIREMOCK)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:1234/v1", "http://127.0.0.1:1234/v1", "http://LOCALHOST:4321/v1",
            "hTTp://localhost:1234/v1"})
    void localhostImProfilLokalGelingt(String url) {
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(LOKAL, url, false, OHNE_WIREMOCK)).doesNotThrowAnyException();
    }

    @Test
    void localhostMitWireMockPortImProfilTestGelingt() {
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(TEST, "http://localhost:8089/v1", false, WIREMOCK))
                .doesNotThrowAnyException();
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(TEST, "http://127.0.0.1:8089/v1", false, WIREMOCK))
                .doesNotThrowAnyException();
    }

    @Test
    void devMitStubUndLeererBasisUrlGelingt() {
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(List.of("dev"), "", true, OHNE_WIREMOCK))
                .doesNotThrowAnyException();
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(List.of("dev"), null, true, OHNE_WIREMOCK))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "prod", "lokal"})
    void konfigurationDerProfileBestehtDiePruefung(String profil) {
        SmallRyeConfig config = konfiguration(profil, Map.of());
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(config.getProfiles(), config)).doesNotThrowAnyException();
    }

    // Req 16.20: Profil lokal aus application.properties

    @Test
    void profilLokalErbtVonDevUndZeigtAufLmStudio() {
        SmallRyeConfig config = konfiguration("lokal", Map.of());
        assertThat(config.getProfiles()).containsExactly("lokal", "dev");
        assertThat(config.getValue("archiv.ki.basis-url", String.class)).isEqualTo("http://localhost:1234/v1");
        assertThat(config.getValue("quarkus.langchain4j.openai.base-url", String.class))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(config.getValue("archiv.ki.chat-modell", String.class)).isEqualTo("archiv-lokal");
        assertThat(config.getValue("archiv.ki.erfassung-modell", String.class)).isEqualTo("archiv-lokal");
        assertThat(config.getValue("archiv.ki.stub", Boolean.class)).isFalse();
        assertThat(config.getValue("archiv.ocr.stub", Boolean.class)).isFalse();
        assertThat(config.getValue("archiv.ablage.stub", Boolean.class)).isTrue();
        assertThat(config.getValue("archiv.integration.stub", Boolean.class)).isTrue();
    }

    @Test
    void profilLokalHatEmbeddingUndRerankerAlsStub() {
        SmallRyeConfig config = konfiguration("lokal", Map.of());
        assertThat(config.getValue("archiv.ki.embedding-stub", Boolean.class)).isTrue();
        assertThat(config.getValue("archiv.ki.reranker-stub", Boolean.class)).isTrue();
        assertThat(config.getOptionalValue("archiv.ki.reranker-url", String.class)).isEmpty();
        assertThat(konfiguration("prod", Map.of()).getValue("archiv.ki.reranker-stub", Boolean.class)).isFalse();
        assertThat(konfiguration("test", Map.of()).getValue("archiv.ki.embedding-stub", Boolean.class)).isFalse();
    }

    @Test
    void profilLokalLiestKeinInfomaniakTokenUndHatKeineProduktId() {
        SmallRyeConfig config = konfiguration("lokal", Map.of());
        assertThat(config.getOptionalValue("archiv.ki.product-id", String.class)).isEmpty();
        // nur die in lokal wirksamen Namen (ohne %-Präfix); %prod.… darf auf /run/secrets zeigen
        Set<String> wirksam = new TreeSet<>();
        for (String name : config.getPropertyNames()) {
            wirksam.add(name.startsWith("%") ? name.substring(name.indexOf('.') + 1) : name);
        }
        assertThat(wirksam).contains("archiv.ki.basis-url");
        for (String name : wirksam) {
            String roh = Expressions.withoutExpansion(() -> config.getConfigValue(name).getRawValue());
            if (roh != null) {
                // klein geschrieben, sonst ginge ${INFOMANIAK_AI_TOKEN} durch
                assertThat(roh.toLowerCase(Locale.ROOT)).as(name)
                        .doesNotContain("infomaniak_ai_token").doesNotContain("/run/secrets");
            }
            String expandiert;
            try {
                expandiert = config.getConfigValue(name).getValue();
            } catch (RuntimeException nichtAufloesbar) {
                expandiert = null;
            }
            if (expandiert != null) {
                assertThat(expandiert.toLowerCase(Locale.ROOT)).as(name + " (expandiert)")
                        .doesNotContain("infomaniak_ai_token").doesNotContain("/run/secrets");
            }
        }
        assertThatCode(() -> KiBasisUrlPruefung.pruefe(config.getProfiles(), config)).doesNotThrowAnyException();
    }

    private static void assertAbgelehnt(List<String> profile, String url, OptionalInt wiremockPort, String host,
                                        String profilText) {
        assertThatThrownBy(() -> KiBasisUrlPruefung.pruefe(profile, url, false, wiremockPort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Host " + host + ",")
                .hasMessageContaining("Profil " + profilText + ".")
                .hasMessageNotContaining(url)
                .hasMessageNotContaining("/v1")
                .hasMessageNotContaining(":1234")
                .hasMessageNotContaining("geheim");
    }

    private static SmallRyeConfig konfiguration(String profil, Map<String, String> zusatz) {
        try {
            // Quarkus-Builder und quarkus.profile als Eigenschaft wie -Dquarkus.profile=…; nur so greift
            // quarkus.config.profile.parent (lokal erbt von dev), withProfile(…) übergeht das Elternprofil
            return ConfigUtils.emptyConfigBuilder()
                    .withSources(new PropertiesConfigSource(
                            KiBasisUrlPruefungTest.class.getClassLoader().getResource("application.properties")))
                    .withSources(new PropertiesConfigSource(zusatz, "zusatz", 500))
                    .withSources(new PropertiesConfigSource(Map.of("quarkus.profile", profil), "profil", 600))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
