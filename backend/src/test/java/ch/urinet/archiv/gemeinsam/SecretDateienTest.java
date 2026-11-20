package ch.urinet.archiv.gemeinsam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Profil {@code prod} liest die Secrets als Dateien (tech.md Regel 7, design.md «Secrets und Konfiguration»): Statt
 * {@code /run/secrets} zeigt {@code smallrye.config.source.file.locations} auf Attrappen unter
 * {@code src/test/resources/secrets-test/}. Ohne {@code smallrye-config-source-file-system} startet das nicht.
 */
@Tag("start-1")
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
        return Map.ofEntries(
                Map.entry("smallrye.config.source.file.locations",
                        Path.of("src/test/resources/secrets-test", verzeichnis).toAbsolutePath().toString()),
                Map.entry("quarkus.datasource.devservices.enabled", "false"),
                Map.entry("quarkus.hibernate-orm.active", "false"),
                Map.entry("archiv.db.url-pflicht", "false"),
                Map.entry("quarkus.oidc.tenant-enabled", "false"),
                Map.entry("quarkus.quinoa", "false"),
                Map.entry("quarkus.compose.devservices.enabled", "false"),
                // /var/lib/archiv/arbeit gibt es nur im Image
                Map.entry("archiv.arbeit.verzeichnis", Path.of("target", "arbeit-prod").toAbsolutePath().toString()),
                // wie compose.yml; ohne Dienst bricht TsaStartpruefung den Start ab
                Map.entry("archiv.tsa.dienste", "sgpki,freetsa"),
                Map.entry("archiv.manifest.beim-start", "false"),
                Map.entry("archiv.verwaltung.keycloak.basis-url", "http://localhost:8080"));
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
        assertThat(config.getValue("infomaniak_ai_token", String.class).strip()).isEqualTo("attrappe-ki-token");
        assertThat(config.getValue("archiv.manifest.schluessel", String.class))
                .isEqualTo("attrappe-manifest-schluessel");
        assertThat(config.getValue("archiv.verwaltung.keycloak.client-secret", String.class))
                .isEqualTo("attrappe-keycloak-client");
    }

    /**
     * Aufgabe 5.6: Mit leerer Datei archiv_smtp_passwort (wie im Deploy ohne Secret) und ohne Empfänger startet prod,
     * auch mit SmtpPasswortPruefung; Benachrichtigungen bleiben im Log.
     */
    @Test
    void ohneSmtpPasswortUndEmpfaengerGestartet() {
        var config = ConfigProvider.getConfig();
        assertThat(config.getValue("quarkus.mailer.host", String.class)).isEqualTo("asmtp.mail.hostpoint.ch");
        assertThat(config.getOptionalValue("quarkus.mailer.password", String.class)).isEmpty();
        assertThat(config.getOptionalValue("archiv.benachrichtigung.empfaenger.urinet", String.class)).isEmpty();
    }
}
