package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.benutzer.adapter.out.keycloak.KeycloakAdminAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("start-2")
@QuarkusTest
@TestProfile(KeycloakVerwaltungSpikeTest.DevService.class)
class KeycloakVerwaltungSpikeTest {

    private static final String REALM = "urinet-archiv";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static class DevService implements QuarkusTestProfile {
    }

    @Test
    void devServiceZeigtMandantensucheRollenmitgliederUndDienstkontoToken() throws IOException {
        String oidcUrl = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        String imageTag = ConfigProvider.getConfig().getValue("quarkus.keycloak.devservices.image-name", String.class);

        String issuer = given().get(oidcUrl + "/.well-known/openid-configuration")
                .then().statusCode(200).extract().path("issuer");
        assertThat(issuer).isEqualTo(oidcUrl);

        String basisUrl = oidcUrl.substring(0, oidcUrl.indexOf("/realms/"));
        String adminToken = given().formParam("grant_type", "password")
                .formParam("client_id", "admin-cli")
                .formParam("username", "admin")
                .formParam("password", "admin")
                .post(basisUrl + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
        String adminUrl = basisUrl + "/admin/realms/" + REALM;

        // Die Version wird am laufenden Server gemessen, nicht nur an der Konfiguration.
        String serverVersion = given().auth().oauth2(adminToken).get(basisUrl + "/admin/serverinfo")
                .then().statusCode(200).extract().path("systemInfo.version");
        assertThat(serverVersion).isEqualTo("26.8.2");
        assertThat(imageTag).endsWith(":" + serverVersion);

        List<Map<String, Object>> urinetBenutzer = OBJECT_MAPPER.readValue(
                adminGet(adminUrl + "/users?q=mandant:urinet&max=50", adminToken).asByteArray(),
                new TypeReference<>() {});
        assertThat(urinetBenutzer).extracting(user -> user.get("username"))
                .contains("inhaber-urinet")
                .doesNotContain("inhaber-vhf-gsk");
        assertThat(urinetBenutzer).allSatisfy(user -> {
            assertThat(user.get("attributes")).isInstanceOf(Map.class);
            Map<?, ?> attribute = (Map<?, ?>) user.get("attributes");
            assertThat(attribute.get("mandant")).isEqualTo(List.of("urinet"));
        });

        List<Map<String, Object>> inhaber = OBJECT_MAPPER.readValue(
                adminGet(adminUrl + "/roles/inhaber/users?max=50", adminToken).asByteArray(),
                new TypeReference<>() {});
        assertThat(inhaber).extracting(user -> user.get("username"))
                .contains("inhaber-urinet", "inhaber-vhf-gsk");

        pruefeDienstkonto(adminUrl, basisUrl, adminToken);
    }

    private static void pruefeDienstkonto(String adminUrl, String basisUrl, String adminToken) throws IOException {
        String clientId = null;
        String clientIdName = "keycloak-verwaltung-spike-" + UUID.randomUUID();
        String clientSecret = UUID.randomUUID().toString();
        try {
            given().auth().oauth2(adminToken).contentType("application/json")
                    .body(Map.of("clientId", clientIdName,
                            "enabled", true,
                            "protocol", "openid-connect",
                            "publicClient", false,
                            "serviceAccountsEnabled", true,
                            "standardFlowEnabled", false,
                            "directAccessGrantsEnabled", false,
                            "secret", clientSecret))
                    .post(adminUrl + "/clients")
                    .then().statusCode(201);
            clientId = adminGet(adminUrl + "/clients?clientId={client}", adminToken, clientIdName)
                    .path("[0].id");
            String serviceAccountId = adminGet(adminUrl + "/clients/{id}/service-account-user", adminToken, clientId)
                    .path("id");

            String realmManagementId = adminGet(adminUrl + "/clients?clientId=realm-management", adminToken)
                    .path("[0].id");
            List<Map<String, Object>> rollen = new ArrayList<>();
            for (String name : List.of("query-users", "view-users", "manage-users")) {
                rollen.add(OBJECT_MAPPER.readValue(
                        adminGet(adminUrl + "/clients/{client}/roles/{role}", adminToken,
                                realmManagementId, name).asByteArray(),
                        new TypeReference<>() {}));
            }
            given().auth().oauth2(adminToken).contentType("application/json").body(rollen)
                    .post(adminUrl + "/users/{user}/role-mappings/clients/{client}", serviceAccountId,
                            realmManagementId)
                    .then().statusCode(204);

            // Ursache des früheren HTTP 401 (Befund 19.1): Keycloak 26.8 verlangt für die Admin-API den Scope
            // kc-admin-api, der die Audience <Basis>/admin/realms/<Realm> mitbringt. Ohne ihn gilt das Token nicht.
            String ohneScope = dienstkontoToken(basisUrl, clientIdName, clientSecret);
            assertThat(given().auth().oauth2(ohneScope).get(adminUrl + "/users?max=1").statusCode()).isEqualTo(401);

            String adminApiScopeId = adminGet(adminUrl + "/client-scopes", adminToken)
                    .jsonPath().getString("find { it.name == 'kc-admin-api' }.id");
            given().auth().oauth2(adminToken)
                    .put(adminUrl + "/clients/{client}/default-client-scopes/{scope}", clientId, adminApiScopeId)
                    .then().statusCode(204);
            String serviceToken = dienstkontoToken(basisUrl, clientIdName, clientSecret);
            List<String> tokenRoles = new ArrayList<>();
            var serviceClaims = OBJECT_MAPPER.readTree(
                    Base64.getUrlDecoder().decode(serviceToken.split("\\.")[1]));
            serviceClaims.path("resource_access").path("realm-management").path("roles")
                    .forEach(role -> tokenRoles.add(role.asText()));
            assertThat(serviceClaims.path("iss").asText()).isEqualTo(basisUrl + "/realms/" + REALM);
            assertThat(tokenRoles).contains("query-users", "view-users", "manage-users", "query-groups");
            assertThat(serviceClaims.path("aud").toString()).contains("realm-management").contains(adminUrl);
            // Mit dem Scope funktioniert der Aufruf der Admin-API mit dem Dienstkonto-Token (Requirement 5.2).
            given().auth().oauth2(serviceToken).get(adminUrl + "/users?max=1").then().statusCode(200);
            var adapter = new KeycloakAdminAdapter(OBJECT_MAPPER, Optional.of(basisUrl), REALM,
                    clientIdName, Optional.of(clientSecret), "archiv-pwa", Optional.empty(),
                    Duration.ofSeconds(2), Duration.ofSeconds(5));
            assertThat(adapter.benutzerSuchen("vhf-gsk", "inhaber", 0, 1))
                    .extracting(benutzer -> benutzer.benutzername())
                    .containsExactly("inhaber-vhf-gsk");
            assertThat(adapter.inhaberZaehlen("vhf-gsk")).isEqualTo(1);
        } finally {
            // Kein Assert im finally: ein Fehler beim Aufräumen darf den ursprünglichen Testfehler nicht überdecken.
            try {
                String id = clientId != null ? clientId
                        : given().auth().oauth2(adminToken).get(adminUrl + "/clients?clientId={client}", clientIdName)
                                .path("[0].id");
                if (id != null) {
                    int status = given().auth().oauth2(adminToken).delete(adminUrl + "/clients/{id}", id).statusCode();
                    if (status != 204) {
                        System.err.println("Aufräumen Spike-Client " + clientIdName + ": Status " + status);
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("Aufräumen Spike-Client " + clientIdName + " fehlgeschlagen: " + e);
            }
        }
    }

    private static String dienstkontoToken(String basisUrl, String clientIdName, String clientSecret) {
        return given().formParam("grant_type", "client_credentials")
                .formParam("client_id", clientIdName)
                .formParam("client_secret", clientSecret)
                .post(basisUrl + "/realms/" + REALM + "/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
    }

    private static Response adminGet(String url, String adminToken, Object... pathParameters) {
        Response response = given().auth().oauth2(adminToken).get(url, pathParameters);
        assertThat(response.statusCode()).as("GET %s", url).isEqualTo(200);
        return response;
    }
}
