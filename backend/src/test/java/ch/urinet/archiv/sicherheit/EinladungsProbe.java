package ch.urinet.archiv.sicherheit;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

final class EinladungsProbe {

    private EinladungsProbe() {
    }

    static void pruefen() {
        var config = ConfigProvider.getConfig();
        String oidcUrl = config.getValue("quarkus.oidc.auth-server-url", String.class);
        String basisUrl = oidcUrl.substring(0, oidcUrl.indexOf("/realms/"));
        int mailpitPort = config.getValue("archiv.entwicklung.mailpit-port", Integer.class);
        String admin = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", "admin").formParam("password", "admin")
                .post(basisUrl + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
        String adminUrl = basisUrl + "/admin/realms/urinet-archiv";
        String name = "einladung-" + UUID.randomUUID();
        String email = name + "@example.invalid";
        given().auth().oauth2(admin).contentType("application/json")
                .body(Map.of("username", name, "enabled", true, "email", email, "firstName", "Probe",
                        "lastName", "Einladung", "attributes", Map.of("mandant", List.of("urinet"))))
                .post(adminUrl + "/users").then().statusCode(201);
        String id = given().auth().oauth2(admin)
                .get(adminUrl + "/users?username={name}&exact=true", name)
                .then().statusCode(200).extract().path("[0].id");
        try {
            given().auth().oauth2(admin).get(adminUrl + "/users/{id}/credentials", id)
                    .then().statusCode(200).body("$", hasSize(0));
            given().auth().oauth2(admin).contentType("application/json")
                    .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"))
                    .put(adminUrl + "/users/{id}/execute-actions-email", id).then().statusCode(204);
            given().baseUri("http://localhost:" + mailpitPort).queryParam("query", "to:" + email)
                    .get("/api/v1/search").then().statusCode(200).body("total", greaterThanOrEqualTo(1));
        } finally {
            given().auth().oauth2(admin).delete(adminUrl + "/users/{id}", id).then().statusCode(204);
        }
    }
}
