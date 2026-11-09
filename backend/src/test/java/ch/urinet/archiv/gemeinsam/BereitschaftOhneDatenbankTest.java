package ch.urinet.archiv.gemeinsam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/** Readiness meldet DOWN, solange die Datenbank nicht erreichbar ist (Aufgabe 1.2). */
@QuarkusTest
@TestProfile(BereitschaftOhneDatenbankTest.OhneDatenbank.class)
class BereitschaftOhneDatenbankTest {

    public static class OhneDatenbank implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.devservices.enabled", "false",
                    "quarkus.datasource.jdbc.url", "jdbc:postgresql://127.0.0.1:1/archiv",
                    "quarkus.datasource.username", "archiv",
                    // nur hier: ohne Datenbank starten, damit die Readiness geprüft werden kann
                    "quarkus.hibernate-orm.schema-management.strategy", "none",
                    "quarkus.liquibase.migrate-at-start", "false");
        }
    }

    @Test
    void readinessDownOhneDatenbank() {
        given().when().get("/q/health/ready")
                .then().statusCode(503)
                .body(containsString("DOWN"));
    }

    @Test
    void livenessBleibtUp() {
        given().when().get("/q/health/live").then().statusCode(200);
    }
}
