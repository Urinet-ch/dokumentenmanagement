package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@Tag("start-4")
@TestProfile(VerwaltungCacheHttpTest.Isoliert.class)
class SicherheitsHeaderTest {

    @Inject
    IdentitaetsDienst identitaet;
    @Inject
    MandantProvisionierung provisionierung;
    @Inject
    MandantRegister register;

    @Test
    void webAppAntwortVerhindertEinbettungInFrames() {
        given().get("/").then()
                .header("Content-Security-Policy", is("frame-ancestors 'none'"))
                .header("X-Frame-Options", is("DENY"));
        given().get("/q/health/ready").then().statusCode(200)
                .header("Content-Security-Policy", is("frame-ancestors 'none'"))
                .header("X-Frame-Options", is("DENY"));
        given().get("/api/v1/benutzer").then().statusCode(401)
                .header("Content-Security-Policy", is("frame-ancestors 'none'"))
                .header("X-Frame-Options", is("DENY"));
    }

    @Test
    void ausfallDesIdentitaetsdienstesBeeintraechtigtReadinessNicht() {
        if (!register.existiert(new MandantCode("urinet"))) {
            provisionierung.anlegen("urinet", null, null);
        }
        String token = Testanmeldung.token("inhaber-urinet");
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        IdentitaetsDienst nichtErreichbar = new IdentitaetsDienstStub() {
            @Override
            public synchronized List<IdentitaetsDienst.Benutzer> benutzerSuchen(
                    String mandant, String suche, int seite, int groesse) {
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
            }

            @Override
            public synchronized IdentitaetsDienst.Benutzer benutzerLesen(String mandant, String id) {
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
            }
        };
        QuarkusMock.installMockForType(nichtErreichbar, IdentitaetsDienst.class);
        try {
            given().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
            given().auth().oauth2(token).get("/api/v1/rollen").then().statusCode(200)
                    .header("Content-Security-Policy", is("frame-ancestors 'none'"))
                    .header("X-Frame-Options", is("DENY"));
            for (String pfad : List.of("/api/v1/benutzer",
                    "/api/v1/benutzer/11111111-1111-4111-8111-111111111111/rechte")) {
                given().auth().oauth2(token).get(pfad).then().statusCode(503)
                        .header("Retry-After", is("1"))
                        .header("Content-Security-Policy", is("frame-ancestors 'none'"))
                        .header("X-Frame-Options", is("DENY"))
                        .body("fehlercode", containsString("IDENTITAETSDIENST_NICHT_ERREICHBAR"));
            }
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }
}
