package ch.urinet.archiv.sicherheit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Realm-Export für Stufe A und B enthält keine Benutzer und keine Secrets (design.md, Sicherheit); der Test-Realm
 * ist der Export plus Testanteile (Benutzer, Clients {@code archiv-test*}, localhost für {@code archiv-pwa}).
 */
class RealmExportTest {

    private static final Path KEYCLOAK = Path.of("../deploy/keycloak");
    /** Felder der Keycloak-Darstellung, die Benutzer oder Geheimnisse tragen (Realm, Clients, SMTP, Schlüssel, IdP, LDAP). */
    private static final Set<String> HEIKLE_FELDER = Set.of("users", "federatedUsers", "credentials", "secret",
            "secretData", "password", "privateKey", "clientSecret", "bindCredential");
    private static final String LOKAL = "http://localhost:8080";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exportOhneBenutzerUndOhneSecrets() throws IOException {
        JsonNode export = JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile());
        assertThat(heikleFelder(export)).as("Benutzer oder Secrets im Realm-Export").isEmpty();
        for (JsonNode client : export.path("clients")) {
            boolean ohneSecret = client.path("publicClient").asBoolean() || client.path("bearerOnly").asBoolean();
            assertThat(ohneSecret).as(client.path("clientId").asText()).isTrue();
        }
        assertThat(export.toString()).doesNotContain("localhost");
    }

    @Test
    void testRealmIstExportPlusTestanteile() throws IOException {
        ObjectNode test = (ObjectNode) JSON.readTree(KEYCLOAK.resolve("realm-test.json").toFile());
        assertThat(test.path("users")).isNotEmpty();
        test.remove("users");
        ArrayNode clients = (ArrayNode) test.path("clients");
        for (int i = clients.size() - 1; i >= 0; i--) {
            JsonNode client = clients.get(i);
            if (client.path("clientId").asText().startsWith("archiv-test")) {
                clients.remove(i);
            } else if ("archiv-pwa".equals(client.path("clientId").asText())) {
                ohneLokal((ArrayNode) client.path("redirectUris"));
                ohneLokal((ArrayNode) client.path("webOrigins"));
                ObjectNode attribute = (ObjectNode) client.path("attributes");
                attribute.put("post.logout.redirect.uris",
                        attribute.path("post.logout.redirect.uris").asText().replace("##" + LOKAL + "/*", ""));
            }
        }
        assertThat(test).isEqualTo(JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile()));
    }

    @Test
    void erkenntHeikleFelderUndUebergehtHarmlose() {
        JsonNode probe = JSON.valueToTree(Map.of(
                "users", List.of(),
                "smtpServer", Map.of("host", "mail.example.invalid", "password", "x"),
                "clients", List.of(Map.of("clientId", "c", "secret", "x")),
                "components", Map.of("keys", List.of(Map.of("config", Map.of("privateKey", List.of("x"))))),
                "identityProviders", List.of(Map.of("config", Map.of("clientSecret", "x"))),
                "resetPasswordAllowed", false,
                "passwordPolicy", "length(12)",
                "requiredCredentials", List.of("password"),
                "otpPolicyType", "totp"));
        assertThat(heikleFelder(probe)).containsExactlyInAnyOrder("$.users", "$.smtpServer.password",
                "$.clients[0].secret", "$.components.keys[0].config.privateKey", "$.identityProviders[0].config.clientSecret");
    }

    private static List<String> heikleFelder(JsonNode wurzel) {
        List<String> funde = new ArrayList<>();
        durchsuchen(wurzel, "$", funde);
        return funde;
    }

    private static void durchsuchen(JsonNode knoten, String pfad, List<String> funde) {
        if (knoten.isObject()) {
            knoten.properties().forEach(e -> {
                String unterpfad = pfad + "." + e.getKey();
                if (HEIKLE_FELDER.contains(e.getKey())) {
                    funde.add(unterpfad);
                }
                durchsuchen(e.getValue(), unterpfad, funde);
            });
        } else if (knoten.isArray()) {
            for (int i = 0; i < knoten.size(); i++) {
                durchsuchen(knoten.get(i), pfad + "[" + i + "]", funde);
            }
        }
    }

    private static void ohneLokal(ArrayNode werte) {
        for (int i = werte.size() - 1; i >= 0; i--) {
            if (werte.get(i).asText().startsWith(LOKAL)) {
                werte.remove(i);
            }
        }
    }
}
