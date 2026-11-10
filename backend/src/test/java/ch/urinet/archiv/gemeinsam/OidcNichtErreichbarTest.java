package ch.urinet.archiv.gemeinsam;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Keycloak nicht erreichbar (Aufgabe 1.9, Review PR #42 M1): z. B. nach einem Neustart der VPS, wenn Docker {@code api}
 * vor {@code kc} startet ({@code depends_on} gilt dann nicht). Die API antwortet 401 statt 500 (Review PR #37), Liveness
 * und Readiness bleiben UP (Readiness nur Datenbank, design.md «Teststrategie»), damit Traefik weiter an die API leitet.
 * Kommt Keycloak später, nimmt die API gültige Tokens ohne Neustart an.
 */
@QuarkusTest
@TestProfile(OidcNichtErreichbarTest.OhneKeycloak.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OidcNichtErreichbarTest {

    private static final String REALM = "/realms/urinet-archiv";
    private static HttpServer keycloak;

    /** Fester Port für den spät startenden Fake-Keycloak; über eine System-Property, weil Profil und Test in
     *  verschiedenen Classloadern laufen. */
    static int port() {
        return Integer.parseInt(System.getProperties().computeIfAbsent("archiv.test.oidc-port", k -> {
            try (ServerSocket frei = new ServerSocket(0)) {
                return String.valueOf(frei.getLocalPort());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toString());
    }

    private static String issuer() {
        return "http://127.0.0.1:" + port() + REALM;
    }

    public static class OhneKeycloak implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.keycloak.devservices.enabled", "false",
                    "quarkus.oidc.auth-server-url", issuer(),
                    // die Adresse des Fake-Keycloak darf nicht in die OpenAPI-Datei des Clients (CI «OpenAPI aktuell»)
                    "quarkus.smallrye-openapi.store-schema-directory", "target/openapi-oidc-test");
        }
    }

    @AfterAll
    static void keycloakStoppen() {
        if (keycloak != null) {
            keycloak.stop(0);
        }
    }

    @Test
    @Order(1)
    void apiMitJwtAntwortet401() {
        given().header("Authorization", "Bearer " + unsigniertesJwt())
                .when().get("/api/v1/api-tokens")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    @Test
    @Order(2)
    void livenessBleibtUp() {
        given().when().get("/q/health/live").then().statusCode(200);
    }

    @Test
    @Order(3)
    void readinessBleibtUpOhneKeycloak() {
        given().when().get("/q/health/ready").then().statusCode(200)
                .body(not(containsString("OIDC")));
    }

    @Test
    @Order(4)
    void nachSpaetemStartVonKeycloakGeltenTokensOhneNeustart() throws Exception {
        RsaJsonWebKey schluessel = RsaJwkGenerator.generateJwk(2048);
        schluessel.setKeyId("k1");
        schluessel.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
        keycloakStarten(schluessel);
        String token = signiertesJwt(schluessel);

        // angemeldet, aber ohne Rolle inhaber: 403 statt 401
        Instant frist = Instant.now().plus(Duration.ofSeconds(30));
        int status;
        do {
            status = given().header("Authorization", "Bearer " + token)
                    .when().get("/api/v1/api-tokens").then().extract().statusCode();
            if (status == 403) {
                break;
            }
            Thread.sleep(500);
        } while (Instant.now().isBefore(frist));
        assertThat(status).as("Token nach spätem Start von Keycloak angenommen").isEqualTo(403);
        given().when().get("/q/health/ready").then().statusCode(200);
    }

    private static void keycloakStarten(RsaJsonWebKey schluessel) throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", port()), 0);
        String konfiguration = """
                {"issuer":"%1$s","jwks_uri":"%1$s/protocol/openid-connect/certs",\
                "token_endpoint":"%1$s/protocol/openid-connect/token",\
                "authorization_endpoint":"%1$s/protocol/openid-connect/auth"}""".formatted(issuer());
        keycloak.createContext(REALM + "/.well-known/openid-configuration", ex -> json(ex, konfiguration));
        keycloak.createContext(REALM + "/protocol/openid-connect/certs",
                ex -> json(ex, "{\"keys\":[" + schluessel.toJson() + "]}"));
        keycloak.start();
    }

    private static void json(HttpExchange austausch, String inhalt) throws IOException {
        byte[] daten = inhalt.getBytes(StandardCharsets.UTF_8);
        austausch.getResponseHeaders().add("Content-Type", "application/json");
        austausch.sendResponseHeaders(200, daten.length);
        try (OutputStream aus = austausch.getResponseBody()) {
            aus.write(daten);
        }
    }

    private static String signiertesJwt(RsaJsonWebKey schluessel) throws Exception {
        JwtClaims inhalt = new JwtClaims();
        inhalt.setIssuer(issuer());
        inhalt.setAudience("archiv-api");
        inhalt.setSubject("spaeter-keycloak");
        inhalt.setIssuedAtToNow();
        inhalt.setExpirationTimeMinutesInTheFuture(5);
        inhalt.setClaim("mandant", "urinet");
        inhalt.setClaim("realm_access", Map.of("roles", List.of("mitarbeiter")));
        JsonWebSignature signatur = new JsonWebSignature();
        signatur.setPayload(inhalt.toJson());
        signatur.setKey(schluessel.getPrivateKey());
        signatur.setKeyIdHeaderValue(schluessel.getKeyId());
        signatur.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return signatur.getCompactSerialization();
    }

    /** Formal gültiges, unsigniertes JWT; geprüft werden kann es ohne Keycloak ohnehin nicht. */
    private static String unsigniertesJwt() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String kopf = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String inhalt = b64.encodeToString(
                "{\"sub\":\"x\",\"aud\":\"archiv-api\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));
        return kopf + "." + inhalt + "." + b64.encodeToString(new byte[32]);
    }
}
