package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benutzer-ID = Claim {@code sub} (Keycloak-ID), nie der Benutzername (design.md «Sicherheit»): {@code erstellt_von}
 * und {@code person_benutzer} steuern die Sichtbarkeit von {@code PERSONAL}, ein Benutzername kann neu vergeben werden.
 */
@QuarkusTest
class BenutzerIdTest {

    @Test
    void benutzerIdIstDerClaimSub() throws Exception {
        String token = Testanmeldung.token("mitarbeiter-urinet");
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));

        String id = given().auth().oauth2(token).when().get("/api/v1/probe/ich").then().statusCode(200)
                .extract().path("id");

        assertThat(claims.path("sub").asText()).isNotBlank();
        assertThat(id).isEqualTo(claims.path("sub").asText()).isNotEqualTo("mitarbeiter-urinet");
    }
}
