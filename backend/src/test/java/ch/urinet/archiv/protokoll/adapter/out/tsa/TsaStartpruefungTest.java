package ch.urinet.archiv.protokoll.adapter.out.tsa;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startprüfung der Zeitstempeldienste (Req 15.3, 15.11; Review PR #87). */
class TsaStartpruefungTest {

    private static final List<String> PROD = List.of("prod");

    @Test
    void ohneDienstStartetProdNicht() {
        assertThatThrownBy(() -> TsaStartpruefung.beimStart(config(Map.of()), PROD, "api,worker"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Kein Zeitstempeldienst konfiguriert");
    }

    @Test
    void einUnvollstaendigerDienstBrichtDenStartAb() {
        Map<String, String> werte = new HashMap<>(dienst("haupt"));
        werte.remove("archiv.tsa.dienst.haupt.ocsp-url");
        werte.put("archiv.tsa.dienste", "haupt");
        assertThatThrownBy(() -> TsaStartpruefung.beimStart(config(werte), PROD, "api"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ocsp-url oder");
    }

    @Test
    void nurEinDienstWarntBeiJedemStart() {
        Map<String, String> werte = new HashMap<>(dienst("haupt"));
        werte.put("archiv.tsa.dienste", "haupt");
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            TsaStartpruefung.beimStart(config(werte), PROD, "api,worker");
            assertThat(log.volltext()).contains("Nur ein Zeitstempeldienst konfiguriert (haupt)");
        }
    }

    @Test
    void zweiDiensteStartenOhneWarnung() {
        Map<String, String> werte = new HashMap<>(dienst("haupt"));
        werte.putAll(dienst("zweit"));
        werte.put("archiv.tsa.dienste", "haupt,zweit");
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            TsaStartpruefung.beimStart(config(werte), PROD, "api,worker");
            assertThat(log.volltext()).doesNotContain("Nur ein Zeitstempeldienst");
        }
        assertThat(TsaStartpruefung.pruefen(config(werte))).containsExactly("haupt", "zweit");
    }

    @Test
    void kommandoUndAndereProfileSindAusgenommen() {
        Config leer = config(Map.of());
        assertThatCode(() -> TsaStartpruefung.beimStart(leer, PROD, "kommando")).doesNotThrowAnyException();
        assertThatCode(() -> TsaStartpruefung.beimStart(leer, List.of("dev"), "api,worker"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TsaStartpruefung.beimStart(leer, PROD, "kommando,worker"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, String> dienst(String kennung) {
        String p = "archiv.tsa.dienst." + kennung + ".";
        return Map.of(p + "url", "https://tsa.example/" + kennung, p + "policies", "1.2.3.4.1",
                p + "ca", "tsa/freetsa/cacert.pem", p + "ocsp-url", "https://ocsp.example/" + kennung);
    }

    private static Config config(Map<String, String> werte) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(werte, "test", 100)).build();
    }
}
