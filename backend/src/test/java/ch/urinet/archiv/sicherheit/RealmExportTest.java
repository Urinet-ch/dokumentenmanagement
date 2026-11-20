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
 * Der Realm-Export enthält nur das Dienstkonto und festgelegte Secret-Platzhalter; der Test-Realm ist der Export
 * plus Testanteile (Benutzer, Clients {@code archiv-test*}, localhost für {@code archiv-pwa}).
 */
class RealmExportTest {

    private static final Path KEYCLOAK = Path.of("../deploy/keycloak");
    /** Felder der Keycloak-Darstellung, die Benutzer oder Geheimnisse tragen (Realm, Clients, SMTP, Schlüssel, IdP, LDAP). */
    private static final Set<String> HEIKLE_FELDER = Set.of("users", "federatedUsers", "credentials", "secret",
            "secretData", "password", "privateKey", "clientSecret", "bindCredential", "registrationAccessToken",
            "keystorePassword", "keyPassword", "client.secret.rotated", "authTokenClientSecret",
            "saml.signing.private.key", "saml.encryption.private.key");
    private static final String LOKAL = "http://localhost:8080";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exportOhnePersonenUndOhneSecrets() throws IOException {
        JsonNode export = JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile());
        JsonNode google = export.path("identityProviders").path(0);
        assertThat(export.path("identityProviders").size()).isEqualTo(1);
        assertThat(google.path("alias").asText()).isEqualTo("google");
        assertThat(google.path("providerId").asText()).isEqualTo("google");
        assertThat(google.path("enabled").asBoolean()).isFalse();
        assertThat(google.path("trustEmail").asBoolean()).isFalse();
        assertThat(google.path("storeToken").asBoolean()).isFalse();
        assertThat(google.path("config").path("syncMode").asText()).isEqualTo("IMPORT");
        assertThat(google.path("firstBrokerLoginFlowAlias").asText()).isEqualTo("google-nur-verknuepft");
        assertThat(google.path("postBrokerLoginFlowAlias").asText()).isEqualTo("google-totp-pflicht");
        assertThat(google.path("config").path("clientId").asText()).isEqualTo("${GOOGLE_CLIENT_ID}");
        assertThat(google.path("config").path("clientSecret").asText()).isEqualTo("${GOOGLE_CLIENT_SECRET}");
        assertThat(google.path("mappers").isMissingNode()).isTrue();

        JsonNode verwaltung = client(export, "archiv-verwaltung");
        assertThat(verwaltung.path("secret").asText()).isEqualTo("${KEYCLOAK_VERWALTUNG_SECRET}");
        assertThat(verwaltung.path("publicClient").asBoolean(true)).isFalse();
        assertThat(verwaltung.path("bearerOnly").asBoolean(true)).isFalse();
        assertThat(verwaltung.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(verwaltung.path("standardFlowEnabled").asBoolean(true)).isFalse();
        assertThat(verwaltung.path("implicitFlowEnabled").asBoolean(true)).isFalse();
        assertThat(verwaltung.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(verwaltung.path("defaultClientScopes")).extracting(JsonNode::asText).contains("kc-admin-api", "roles");
        assertThat(export.path("users")).hasSize(1);
        JsonNode dienstkonto = export.path("users").get(0);
        assertThat(dienstkonto.path("serviceAccountClientId").asText()).isEqualTo("archiv-verwaltung");
        assertThat(dienstkonto.path("username").asText()).isEqualTo("service-account-archiv-verwaltung");
        assertThat(dienstkonto.path("clientRoles").size()).isEqualTo(1);
        assertThat(dienstkonto.path("clientRoles").path("realm-management")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("query-users", "view-users", "manage-users");
        assertThat(dienstkonto.has("realmRoles")).isFalse();
        ObjectNode bereinigt = export.deepCopy();
        bereinigt.remove("identityProviders");
        ((ObjectNode) client(bereinigt, "archiv-verwaltung")).remove("secret");
        ((ObjectNode) bereinigt.path("users").get(0)).remove("clientRoles");
        ArrayNode konten = (ArrayNode) bereinigt.remove("users");
        assertThat(heikleFelder(konten)).isEmpty();
        assertThat(heikleFelder(bereinigt)).as("Personen oder Secrets im Realm-Export").isEmpty();
        for (JsonNode client : export.path("clients")) {
            boolean ohneSecret = client.path("publicClient").asBoolean() || client.path("bearerOnly").asBoolean()
                    || "archiv-verwaltung".equals(client.path("clientId").asText());
            assertThat(ohneSecret).as(client.path("clientId").asText()).isTrue();
        }
        assertThat(export.toString()).doesNotContain("localhost");
        assertThat(export.path("eventsEnabled").asBoolean()).isTrue();
        assertThat(export.path("adminEventsEnabled").asBoolean()).isTrue();
        assertThat(export.path("adminEventsDetailsEnabled").asBoolean()).isFalse();
        assertThat(export.path("eventsExpiration").asInt()).isEqualTo(400 * 24 * 60 * 60);
        assertThat(export.path("accessTokenLifespan").asInt()).isEqualTo(300);
        assertThat(export.path("actionTokenGeneratedByAdminLifespan").asInt()).isEqualTo(86400);
        assertThat(export.path("passwordPolicy").asText()).isEqualTo("length(12) and notUsername and notEmail");
        List<JsonNode> mapper = new ArrayList<>();
        client(export, "archiv-pwa").path("protocolMappers").forEach(k -> {
            if ("auth_time".equals(k.path("name").asText())) {
                mapper.add(k);
            }
        });
        assertThat(mapper).hasSize(1);
        assertThat(mapper.getFirst().path("protocolMapper").asText()).isEqualTo("oidc-usersessionmodel-note-mapper");
        assertThat(mapper.getFirst().path("config").path("user.session.note").asText()).isEqualTo("AUTH_TIME");
        assertThat(mapper.getFirst().path("config").path("claim.name").asText()).isEqualTo("auth_time");
        assertThat(mapper.getFirst().path("config").path("jsonType.label").asText()).isEqualTo("long");
        assertThat(mapper.getFirst().path("config").path("access.token.claim").asText()).isEqualTo("true");
        assertThat(export.path("enabledEventTypes"))
                .extracting(JsonNode::asText)
                .contains("LOGIN_ERROR", "IDENTITY_PROVIDER_LOGIN", "IDENTITY_PROVIDER_LOGIN_ERROR",
                        "IDENTITY_PROVIDER_FIRST_LOGIN_ERROR", "FEDERATED_IDENTITY_LINK",
                        "REMOVE_FEDERATED_IDENTITY");
        assertThat(export.path("authenticationFlows").findValuesAsText("alias"))
                .contains("google-nur-verknuepft", "google-totp-pflicht");
    }

    /**
     * Das Benutzerverzeichnis (Req 10.10) übernimmt den Benutzernamen als Name, weil nur der Admin ihn ändern darf
     * (Review PR #88, S4); Vor- und Nachname darf der Benutzer selbst ändern.
     */
    @Test
    void benutzernameAendertNurDerAdmin() throws IOException {
        JsonNode export = JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile());
        assertThat(export.path("editUsernameAllowed").asBoolean(true)).isFalse();
        List<JsonNode> profile = export.findValues("kc.user.profile.config");
        assertThat(profile).hasSize(1);
        JsonNode attribute = JSON.readTree(profile.getFirst().get(0).asText()).path("attributes");
        List<String> bearbeiten = new ArrayList<>();
        for (JsonNode attribut : attribute) {
            if ("username".equals(attribut.path("name").asText())) {
                attribut.path("permissions").path("edit").forEach(r -> bearbeiten.add(r.asText()));
            }
        }
        assertThat(bearbeiten).containsExactly("admin");
    }

    @Test
    void testRealmIstExportPlusTestanteile() throws IOException {
        ObjectNode test = (ObjectNode) JSON.readTree(KEYCLOAK.resolve("realm-test.json").toFile());
        assertThat(test.path("users")).isNotEmpty();
        assertThat(test.path("smtpServer").path("host").asText()).isEqualTo("mailpit");
        assertThat(test.path("smtpServer").path("port").asText()).isEqualTo("1025");
        test.remove("smtpServer");
        ObjectNode verwaltung = (ObjectNode) client(test, "archiv-verwaltung");
        assertThat(verwaltung.path("secret").asText()).isEqualTo(ExportKeycloak.VERWALTUNG_TESTWERT);
        verwaltung.put("secret", "${KEYCLOAK_VERWALTUNG_SECRET}");
        ArrayNode benutzer = (ArrayNode) test.path("users");
        for (int i = benutzer.size() - 1; i >= 0; i--) {
            if (!"archiv-verwaltung".equals(benutzer.get(i).path("serviceAccountClientId").asText())) {
                benutzer.remove(i);
            }
        }
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
        ArrayNode identityProviders = (ArrayNode) test.path("identityProviders");
        for (int i = identityProviders.size() - 1; i >= 0; i--) {
            if ("archiv-test-oidc".equals(identityProviders.get(i).path("alias").asText())) {
                identityProviders.remove(i);
            }
        }
        assertThat(test).isEqualTo(JSON.readTree(KEYCLOAK.resolve("realm-export.json").toFile()));
    }

    @Test
    void erkenntHeikleFelderUndUebergehtHarmlose() {
        JsonNode probe = JSON.valueToTree(Map.of(
                "users", List.of(),
                "smtpServer", Map.of("host", "mail.example.invalid", "password", "x", "authTokenClientSecret", "x"),
                "clients", List.of(Map.of("clientId", "c", "secret", "x", "registrationAccessToken", "x",
                        "attributes", Map.of("client.secret.rotated", "x", "saml.signing.private.key", "x",
                                "saml.encryption.private.key", "x"))),
                "components", Map.of("keys", List.of(Map.of("config", Map.of("privateKey", List.of("x"),
                        "keystorePassword", List.of("x"), "keyPassword", List.of("x"))))),
                "identityProviders", List.of(Map.of("config", Map.of("clientSecret", "x"))),
                "resetPasswordAllowed", false,
                "passwordPolicy", "length(12)",
                "requiredCredentials", List.of("password"),
                "otpPolicyType", "totp"));
        assertThat(heikleFelder(probe)).containsExactlyInAnyOrder("$.users", "$.smtpServer.password",
                "$.smtpServer.authTokenClientSecret",
                "$.clients[0].secret", "$.clients[0].registrationAccessToken",
                "$.clients[0].attributes.client.secret.rotated", "$.clients[0].attributes.saml.signing.private.key",
                "$.clients[0].attributes.saml.encryption.private.key",
                "$.components.keys[0].config.privateKey", "$.components.keys[0].config.keystorePassword",
                "$.components.keys[0].config.keyPassword", "$.identityProviders[0].config.clientSecret");
    }

    private static List<String> heikleFelder(JsonNode wurzel) {
        List<String> funde = new ArrayList<>();
        durchsuchen(wurzel, "$", funde);
        return funde;
    }

    private static JsonNode client(JsonNode realm, String clientId) {
        List<JsonNode> treffer = new ArrayList<>();
        realm.path("clients").forEach(client -> {
            if (clientId.equals(client.path("clientId").asText())) {
                treffer.add(client);
            }
        });
        assertThat(treffer).hasSize(1);
        return treffer.getFirst();
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
