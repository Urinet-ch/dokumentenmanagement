package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.adapter.out.keycloak.IdentitaetsdienstHealthCheck;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@Tag("start-4")
@TestProfile(VerwaltungCacheHttpTest.Isoliert.class)
class SicherheitsHeaderTest {

    @Inject
    IdentitaetsDienst identitaet;

    @Test
    void webAppAntwortVerhindertEinbettungInFrames() {
        given().get("/").then()
                .header("Content-Security-Policy", is("frame-ancestors 'none'"))
                .header("X-Frame-Options", is("DENY"));
    }

    @Test
    void ausfallDesIdentitaetsdienstesBeeintraechtigtReadinessNicht() {
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        IdentitaetsDienst nichtErreichbar = new IdentitaetsDienstStub() {
            @Override
            public void verbindungPruefen() {
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
            }
        };
        QuarkusMock.installMockForType(nichtErreichbar, IdentitaetsDienst.class);
        try {
            given().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
            var detailHealth = new IdentitaetsdienstHealthCheck(nichtErreichbar).call();
            assertThat(detailHealth.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }
}
