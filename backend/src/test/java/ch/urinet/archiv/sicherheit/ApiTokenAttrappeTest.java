package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.application.ApiTokenPruefung;
import ch.urinet.archiv.sicherheit.application.GeheimnisHasher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Attrappe für unbekannte Token-IDs hat dieselben Argon2-Parameter wie ein echter Hash (Req 2.5, Timing). */
@QuarkusTest
class ApiTokenAttrappeTest {

    @Inject
    ApiTokenPruefung pruefung;

    @Inject
    GeheimnisHasher hasher;

    @Test
    void attrappeHatDieParameterDesHashers() {
        String attrappe = pruefung.attrappeBerechnen();
        String echt = hasher.hashen("probe");

        // $argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>: alles vor Salt und Hash muss gleich sein
        assertThat(parameter(attrappe)).isEqualTo(parameter(echt)).startsWith("$argon2id$v=19$m=");
        assertThat(attrappe.split("\\$")).hasSameSizeAs(echt.split("\\$"));
        assertThat(attrappe).as("beim Start berechnet, danach unverändert").isSameAs(pruefung.attrappeBerechnen());
    }

    private static String parameter(String phc) {
        return phc.substring(0, phc.lastIndexOf('$', phc.lastIndexOf('$') - 1));
    }
}
