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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Realm-Export für Stufe A und B enthält keine Benutzer und keine Secrets (design.md, Sicherheit); der Test-Realm
 * ist der Export plus Testbenutzer und Test-Client, sonst nichts.
 */
class RealmExportTest {

    private static final Path KEYCLOAK = Path.of("../deploy/keycloak");
    private static final Pattern HEIKEL = Pattern.compile("(?i).*(secret|password|passwort|credential|privatekey|users).*");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exportOhneBenutzerUndOhneSecrets() throws IOException {
        JsonNode export = JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile());
        List<String> funde = new ArrayList<>();
        durchsuchen(export, "$", funde);
        assertThat(funde).as("Benutzer oder Secrets im Realm-Export").isEmpty();
        for (JsonNode client : export.path("clients")) {
            assertThat(client.path("publicClient").asBoolean()).as(client.path("clientId").asText()).isTrue();
        }
    }

    @Test
    void testRealmIstExportPlusTestanteile() throws IOException {
        ObjectNode test = (ObjectNode) JSON.readTree(KEYCLOAK.resolve("realm-test.json").toFile());
        assertThat(test.path("users")).isNotEmpty();
        test.remove("users");
        ArrayNode clients = (ArrayNode) test.path("clients");
        for (int i = clients.size() - 1; i >= 0; i--) {
            if ("archiv-test".equals(clients.get(i).path("clientId").asText())) {
                clients.remove(i);
            }
        }
        assertThat(test).isEqualTo(JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile()));
    }

    @Test
    void erkenntSecretsUndBenutzer() {
        List<String> funde = new ArrayList<>();
        durchsuchen(JSON.valueToTree(Map.of("users", List.of(), "resetPasswordAllowed", false,
                "clients", List.of(Map.of("secret", "x")))), "$", funde);
        assertThat(funde).containsExactlyInAnyOrder("$.users", "$.clients[0].secret");
    }

    /** Heikle Schlüssel zählen, ausser sie tragen nur einen Wahrheitswert (z. B. {@code resetPasswordAllowed}). */
    private static void durchsuchen(JsonNode knoten, String pfad, List<String> funde) {
        if (knoten.isObject()) {
            knoten.properties().forEach(e -> {
                String unterpfad = pfad + "." + e.getKey();
                if (HEIKEL.matcher(e.getKey()).matches() && !e.getValue().isBoolean()) {
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
}
