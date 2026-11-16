package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
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

    private static final MandantCode URINET = new MandantCode("urinet");

    @Inject
    MandantRegister register;

    @Inject
    MandantProvisionierung provisionierung;

    /** Der Test legt den Mandanten selbst an, falls kein anderer Test ihn schon angelegt hat (Einzelstart). */
    @BeforeEach
    void mandantSicherstellen() {
        if (!register.existiert(URINET)) {
            provisionierung.anlegen(URINET.wert(), null, null);
        }
    }

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
