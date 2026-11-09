package ch.urinet.archiv.spike.golden;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nur manuell (-Dgolden=true), nie in der CI: echter Lauf auf dem Abstimmungsteil gegen Infomaniak.
 * Ohne Token-Datei oder Golden-Daten übersprungen. Hostprüfung wie GoldenLauf (Req 16.22).
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "golden", matches = "true")
class GoldenDatensatzIT {

    @Inject
    GoldenLaufDienst dienst;

    @ConfigProperty(name = "archiv.ki.token-datei")
    Path tokenDatei;

    @Test
    void abstimmungsteil() throws Exception {
        assumeTrue(Files.isReadable(tokenDatei), "Token-Datei fehlt");
        assumeTrue(Files.isRegularFile(GoldenLaufDienst.goldenDir().resolve("synthetisch/erwartet.json")),
                "ARCHIV_GOLDEN_DIR ohne synthetische Belege");
        int limit = Integer.getInteger("golden.limit", 0);
        GoldenLaufDienst.Ausgang a = dienst.lauf(Modus.ABSTIMMEN, limit, false);
        assertThat(a.ergebnisse()).isNotEmpty().allMatch(r -> "abstimmen".equals(r.erwartung().teil()));
        assertThat(a.ordner().resolve("kennzahlen.md")).exists();
    }
}
