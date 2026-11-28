package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.wissen.FesteWissensfreischaltung;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("start-4")
@TestProfile(LangChain4jKiAdapterWissenTimeoutTest.KurzeZeitgrenze.class)
@WithTestResource(value = KiWireMock.class, scope = TestResourceScope.GLOBAL)
class LangChain4jKiAdapterWissenTimeoutTest {

    @Inject
    KiPort ki;

    @ConfigProperty(name = "archiv.ki.timeout")
    Duration timeout;

    /**
     * Der Adapter prüft vor dem Aufruf die Freischaltung (Spec 04, Req 22.9); dieser Test gilt der Zeitüberschreitung,
     * darum ist Wissen hier fest eingeschaltet (das Probe-Register kennt keinen Mandanten).
     */
    @BeforeEach
    void wissenEin() {
        QuarkusMock.installMockForType(FesteWissensfreischaltung.ein(), Wissensfreischaltung.class);
    }

    @Test
    void modellzeitueberschreitungIstEinAusfallUndKeinSchemafehler() {
        assertThat(timeout).isEqualTo(Duration.ofSeconds(1));
        KiWireMock.zuruecksetzen();
        KiWireMock.wissenFolge(List.of("{}"), 2_000, 100, 50);

        KiPort.WissensAntwort antwort = ki.wissensIngest(new KiPort.WissensAnfrage(
                KiPort.WissensSchritt.KERNAUSSAGEN, "google/gemma-4-31B-it", "System", "Inhalt", 100));

        assertThat(antwort).isInstanceOf(KiPort.WissensAusfall.class);
        assertThat(KiWireMock.wissenAnfragen()).hasSize(1);
    }

    public static class KurzeZeitgrenze implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.ki.timeout", "PT1S");
        }
    }
}
