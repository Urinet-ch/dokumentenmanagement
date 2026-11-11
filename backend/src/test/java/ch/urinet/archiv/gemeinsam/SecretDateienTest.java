package ch.urinet.archiv.gemeinsam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Profil {@code prod} liest die Secrets als Dateien (tech.md Regel 7, design.md «Secrets und Konfiguration»): Statt
 * {@code /run/secrets} zeigt {@code smallrye.config.source.file.locations} auf Attrappen unter
 * {@code src/test/resources/secrets-test/}. Ohne {@code smallrye-config-source-file-system} startet das nicht.
 */
@QuarkusTest
@TestProfile(SecretDateienTest.ProdMitSecretDateien.class)
public class SecretDateienTest {

    public static class ProdMitSecretDateien implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return prodOhneDienste("vollstaendig");
        }
    }

    /** Profil {@code prod} ohne Datenbank, Keycloak und PWA; nur die Secret-Dateien kommen aus {@code verzeichnis}. */
    public static Map<String, String> prodOhneDienste(String verzeichnis) {
        return Map.of(
                "smallrye.config.source.file.locations",
                Path.of("src/test/resources/secrets-test", verzeichnis).toAbsolutePath().toString(),
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.hibernate-orm.active", "false",
                "archiv.db.url-pflicht", "false",
                "quarkus.oidc.tenant-enabled", "false",
                "quarkus.quinoa", "false",
                "quarkus.compose.devservices.enabled", "false",
                // /var/lib/archiv/arbeit gibt es nur im Image
                "archiv.arbeit.verzeichnis", Path.of("target", "arbeit-prod").toAbsolutePath().toString());
    }

    @Test
    void secretsAusDateien() {
        var config = ConfigProvider.getConfig();
        assertThat(config.getValue("quarkus.s3.aws.credentials.static-provider.access-key-id", String.class))
                .isEqualTo("attrappe-schluessel-id");
        assertThat(config.getValue("quarkus.s3.aws.credentials.static-provider.secret-access-key", String.class))
                .isEqualTo("attrappe-geheimnis");
        assertThat(config.getValue("quarkus.datasource.password", String.class)).isEqualTo("attrappe-app");
        assertThat(config.getValue("quarkus.liquibase.password", String.class)).isEqualTo("attrappe-owner");
    }
}
