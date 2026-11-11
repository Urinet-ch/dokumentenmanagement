package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** In {@code prod} bricht ein eingeschalteter Dev-Stub den Start ab (Req 16.13, Startprüfung der Port-Auswahl). */
@QuarkusMainTest
@TestProfile(PortAuswahlStartTest.ProdMitAblageStub.class)
class PortAuswahlStartTest {

    public static class ProdMitAblageStub implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> werte = new HashMap<>(SecretDateienTest.prodOhneDienste("vollstaendig"));
            werte.put("archiv.ablage.stub", "true");
            return werte;
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-stub"}, exitCode = 3)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen", "Port-Auswahl in prod abgelehnt",
                "AblagePort: Dev-Stub");
    }
}
