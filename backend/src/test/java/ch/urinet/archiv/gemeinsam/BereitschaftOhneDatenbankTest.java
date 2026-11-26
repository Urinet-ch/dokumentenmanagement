package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/** Readiness meldet DOWN, solange die Datenbank nicht erreichbar ist (Aufgabe 1.2). */
@Tag("start-1")
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

    @Test
    @EnabledIfSystemProperty(named = "archiv.qa.db-log", matches = "true")
    void authentisierteSucheBeiNichtErreichbarerDatenbankOhneDetails() {
        String token = Testanmeldung.token("inhaber-urinet");
        String marker = "SYNTHETISCHER_INHALT_7F42";
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            var antwort = given().auth().oauth2(token).queryParam("q", marker)
                    .when().get("/api/v1/dokumente");
            assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
            String text = antwort.asString();
            assertThat(text.contains(marker) || text.contains(token)).as("Antwort enthält Eingabe oder Token").isFalse();
            assertThat(text).doesNotContain("Exception", "\tat ", "Caused by:");
            String protokoll = log.volltext();
            assertThat(protokoll.contains(marker) || protokoll.contains(token))
                    .as("Log enthält Eingabe oder Token").isFalse();
            assertThat(protokoll.contains("\tat ") || protokoll.contains("Caused by:"))
                    .as("Log enthält einen Stacktrace").isFalse();
        }
    }
}
