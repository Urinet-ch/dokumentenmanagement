package ch.urinet.archiv.gemeinsam;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Minimaler Keycloak für Tests der OIDC-Bereitschaft (Aufgabe 1.9, PR #42 P42-1): liefert nur Discovery und Schlüssel
 * eines Realms. Als Testressource startet er vor der Anwendung (Neustart der VPS, Keycloak schon da); mit dem Argument
 * {@code realm} bedient er einen anderen Realm als den konfigurierten (Keycloak falsch konfiguriert).
 */
public final class FakeKeycloak implements QuarkusTestResourceLifecycleManager {

    public static final String REALM = "/realms/urinet-archiv";
    /** Schlüssel samt privatem Teil für den Test; über eine System-Property, weil Ressource und Test in
     *  verschiedenen Classloadern laufen. */
    static final String SCHLUESSEL = "archiv.test.oidc-schluessel";

    private String realm = REALM;
    private HttpServer server;

    @Override
    public void init(Map<String, String> argumente) {
        realm = argumente.getOrDefault("realm", REALM);
    }

    @Override
    public Map<String, String> start() {
        try {
            RsaJsonWebKey schluessel = neuerSchluessel();
            System.setProperty(SCHLUESSEL, schluessel.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE));
            int port = freierPort();
            String issuer = issuer(port);
            server = starten(port, realm, issuer(port, realm), schluessel);
            return konfiguration(issuer);
        } catch (IOException | JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(SCHLUESSEL);
    }

    /** Overrides für einen Test ohne Dev-Services-Keycloak gegen {@code issuer}. */
    static Map<String, String> konfiguration(String issuer) {
        return Map.of(
                "quarkus.keycloak.devservices.enabled", "false",
                "quarkus.oidc.auth-server-url", issuer,
                // die Adresse des Fake-Keycloak darf nicht in die OpenAPI-Datei des Clients (CI «OpenAPI aktuell»)
                "quarkus.smallrye-openapi.store-schema-directory", "target/openapi-oidc-test");
    }

    static String issuer(int port) {
        return issuer(port, REALM);
    }

    private static String issuer(int port, String realm) {
        return "http://127.0.0.1:" + port + realm;
    }

    static int freierPort() throws IOException {
        try (ServerSocket frei = new ServerSocket(0)) {
            return frei.getLocalPort();
        }
    }

    static RsaJsonWebKey neuerSchluessel() throws JoseException {
        RsaJsonWebKey schluessel = RsaJwkGenerator.generateJwk(2048);
        schluessel.setKeyId("k1");
        schluessel.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
        return schluessel;
    }

    static RsaJsonWebKey schluesselDerRessource() throws JoseException {
        return (RsaJsonWebKey) JsonWebKey.Factory.newJwk(System.getProperty(SCHLUESSEL));
    }

    static HttpServer starten(int port, String realm, String issuer, RsaJsonWebKey schluessel) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        String konfiguration = """
                {"issuer":"%1$s","jwks_uri":"%1$s/protocol/openid-connect/certs",\
                "token_endpoint":"%1$s/protocol/openid-connect/token",\
                "authorization_endpoint":"%1$s/protocol/openid-connect/auth"}""".formatted(issuer);
        server.createContext(realm + "/.well-known/openid-configuration", ex -> json(ex, konfiguration));
        server.createContext(realm + "/protocol/openid-connect/certs",
                ex -> json(ex, "{\"keys\":[" + schluessel.toJson() + "]}"));
        server.start();
        return server;
    }

    private static void json(HttpExchange austausch, String inhalt) throws IOException {
        byte[] daten = inhalt.getBytes(StandardCharsets.UTF_8);
        austausch.getResponseHeaders().add("Content-Type", "application/json");
        austausch.sendResponseHeaders(200, daten.length);
        try (OutputStream aus = austausch.getResponseBody()) {
            aus.write(daten);
        }
    }

    /** Gültiges Token für {@code issuer}, angemeldet als {@code mitarbeiter} (ohne Rolle {@code inhaber}). */
    static String signiertesJwt(RsaJsonWebKey schluessel, String issuer) throws JoseException {
        JwtClaims inhalt = new JwtClaims();
        inhalt.setIssuer(issuer);
        inhalt.setAudience("archiv-api");
        inhalt.setSubject("fake-keycloak");
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
    static String unsigniertesJwt() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String kopf = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String inhalt = b64.encodeToString(
                "{\"sub\":\"x\",\"aud\":\"archiv-api\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));
        return kopf + "." + inhalt + "." + b64.encodeToString(new byte[32]);
    }
}
