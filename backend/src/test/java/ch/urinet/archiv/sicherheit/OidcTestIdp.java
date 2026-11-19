package ch.urinet.archiv.sicherheit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Lokaler OpenID-Connect-Test-IdP für die Keycloak-Broker-Szenarien. */
final class OidcTestIdp implements AutoCloseable {

    /** {@code zusatz}: feindliche Zusatzclaims (z. B. mandant, roles), die Keycloak nie übernehmen darf. */
    record Identitaet(String sub, String email, Map<String, Object> zusatz) {
        Identitaet(String sub, String email) {
            this(sub, email, Map.of());
        }
    }

    private record Pending(Identitaet identitaet, String nonce) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_ID = "urinet-test-idp";
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final KeyPair schluessel;
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Identitaet> accessTokens = new ConcurrentHashMap<>();
    private final AtomicInteger tokenAnfragen = new AtomicInteger();
    private final AtomicInteger benutzerinfoAnfragen = new AtomicInteger();
    private volatile boolean tokenCodeAkzeptiert;
    private volatile Identitaet identitaet;

    OidcTestIdp() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            schluessel = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OIDC-Testschlüssel konnte nicht erstellt werden", e);
        }
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.setExecutor(executor);
        server.createContext("/authorize", this::autorisieren);
        server.createContext("/token", this::token);
        server.createContext("/userinfo", this::benutzerinfo);
        server.createContext("/jwks", this::jwks);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String basisUrl() {
        return "http://host.testcontainers.internal:" + port();
    }

    void identitaetSetzen(Identitaet wert) {
        identitaet = wert;
    }

    int tokenAnfragen() {
        return tokenAnfragen.get();
    }

    String diagnose() {
        return "Tokenanfragen=" + tokenAnfragen.get() + ", Code akzeptiert=" + tokenCodeAkzeptiert
                + ", Benutzerinfoanfragen=" + benutzerinfoAnfragen.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void autorisieren(HttpExchange austausch) throws IOException {
        Map<String, String> parameter = parameter(austausch.getRequestURI().getRawQuery());
        String umleitung = parameter.get("redirect_uri");
        String zustand = parameter.get("state");
        String nonce = parameter.get("nonce");
        URI rueckruf = umleitung == null ? null : URI.create(umleitung);
        if (rueckruf == null || !"http".equals(rueckruf.getScheme()) || !"localhost".equals(rueckruf.getHost())
                || !"/realms/urinet-archiv/broker/archiv-test-oidc/endpoint".equals(rueckruf.getPath())
                || zustand == null || nonce == null || identitaet == null) {
            antwort(austausch, 400, "Fehlende OIDC-Anfrageparameter");
            return;
        }
        String code = "code-" + UUID.randomUUID();
        codes.put(code, new Pending(identitaet, nonce));
        String trenner = umleitung.contains("?") ? "&" : "?";
        austausch.getResponseHeaders().set("Location",
                umleitung + trenner + "code=" + encodieren(code) + "&state=" + encodieren(zustand));
        austausch.sendResponseHeaders(302, -1);
        austausch.close();
    }

    private void token(HttpExchange austausch) throws IOException {
        tokenAnfragen.incrementAndGet();
        Map<String, String> parameter = parameter(new String(austausch.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        Pending pending = codes.remove(parameter.get("code"));
        if (pending == null) {
            antwort(austausch, 400, "Ungültiger OIDC-Testcode");
            return;
        }
        tokenCodeAkzeptiert = true;
        String accessToken = "synthetisches-oidc-token-" + UUID.randomUUID();
        accessTokens.put(accessToken, pending.identitaet());
        long jetzt = System.currentTimeMillis() / 1000;
        antwortJson(austausch, 200, Map.of(
                "access_token", accessToken,
                "id_token", idToken(pending, jetzt),
                "token_type", "Bearer",
                "expires_in", 60,
                "scope", "openid email profile"));
    }

    private void benutzerinfo(HttpExchange austausch) throws IOException {
        benutzerinfoAnfragen.incrementAndGet();
        String authorization = austausch.getRequestHeaders().getFirst("Authorization");
        Identitaet aktuelleIdentitaet = authorization == null || !authorization.startsWith("Bearer ")
                ? null : accessTokens.get(authorization.substring("Bearer ".length()));
        if (aktuelleIdentitaet == null) {
            antwort(austausch, 401, "Ungültiges OIDC-Testtoken");
            return;
        }
        Map<String, Object> inhalt = new java.util.LinkedHashMap<>(aktuelleIdentitaet.zusatz());
        inhalt.put("sub", aktuelleIdentitaet.sub());
        inhalt.put("email", aktuelleIdentitaet.email());
        inhalt.put("email_verified", true);
        inhalt.put("preferred_username", aktuelleIdentitaet.email());
        inhalt.put("name", "Synthetischer OIDC-Testbenutzer");
        antwortJson(austausch, 200, inhalt);
    }

    private void jwks(HttpExchange austausch) throws IOException {
        RSAPublicKey publicKey = (RSAPublicKey) schluessel.getPublic();
        antwortJson(austausch, 200, Map.of("keys", java.util.List.of(Map.of(
                "kid", KEY_ID,
                "kty", "RSA",
                "alg", "RS256",
                "use", "sig",
                "n", unsignedBase64(publicKey.getModulus().toByteArray()),
                "e", unsignedBase64(publicKey.getPublicExponent().toByteArray())))));
    }

    private String idToken(Pending pending, long jetzt) {
        try {
            String header = base64(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", KEY_ID)));
            Map<String, Object> claims = new java.util.LinkedHashMap<>(pending.identitaet().zusatz());
            claims.put("iss", basisUrl());
            claims.put("sub", pending.identitaet().sub());
            claims.put("aud", "archiv-test-oidc");
            claims.put("iat", jetzt);
            claims.put("exp", jetzt + 60);
            claims.put("nonce", pending.nonce());
            claims.put("email", pending.identitaet().email());
            claims.put("email_verified", true);
            String inhalt = base64(JSON.writeValueAsBytes(claims));
            String signiert = header + "." + inhalt;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(schluessel.getPrivate());
            signature.update(signiert.getBytes(StandardCharsets.US_ASCII));
            return signiert + "." + base64(signature.sign());
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("OIDC-Testtoken konnte nicht erstellt werden", e);
        }
    }

    private static String base64(byte[] wert) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(wert);
    }

    private static String unsignedBase64(byte[] wert) {
        int start = wert.length > 1 && wert[0] == 0 ? 1 : 0;
        return base64(Arrays.copyOfRange(wert, start, wert.length));
    }

    private static Map<String, String> parameter(String kodiert) {
        if (kodiert == null || kodiert.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(kodiert.split("&"))
                .map(paar -> paar.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        paar -> decodieren(paar[0]),
                        paar -> paar.length > 1 ? decodieren(paar[1]) : "",
                        (links, rechts) -> rechts));
    }

    private static String decodieren(String wert) {
        return URLDecoder.decode(wert, StandardCharsets.UTF_8);
    }

    private static String encodieren(String wert) {
        return java.net.URLEncoder.encode(wert, StandardCharsets.UTF_8);
    }

    private static void antwortJson(HttpExchange austausch, int status, Object inhalt) throws IOException {
        austausch.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = JSON.writeValueAsBytes(inhalt);
        austausch.sendResponseHeaders(status, bytes.length);
        austausch.getResponseBody().write(bytes);
        austausch.close();
    }

    private static void antwort(HttpExchange austausch, int status, String inhalt) throws IOException {
        byte[] bytes = inhalt.getBytes(StandardCharsets.UTF_8);
        austausch.sendResponseHeaders(status, bytes.length);
        austausch.getResponseBody().write(bytes);
        austausch.close();
    }
}
