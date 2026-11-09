package ch.urinet.archiv.gemeinsam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/** Ohne aktive Datasource (z. B. ohne JDBC-URL) meldet die Readiness DOWN statt UP (Aufgabe 1.2). */
@QuarkusTest
@TestProfile(BereitschaftOhneDatasourceTest.OhneDatasource.class)
class BereitschaftOhneDatasourceTest {

    public static class OhneDatasource implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.active", "false");
        }
    }

    @Test
    void readinessDownOhneDatasource() {
        given().when().get("/q/health/ready")
                .then().statusCode(503)
                .body(containsString("Datenbank konfiguriert"));
    }
}
