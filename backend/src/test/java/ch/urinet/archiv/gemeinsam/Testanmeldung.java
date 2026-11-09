package ch.urinet.archiv.gemeinsam;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;

/**
 * Tokens der Testbenutzer aus {@code deploy/keycloak/realm-test.json} (nur Entwicklung und Tests): Passwort und TOTP
 * mit bekanntem Test-Secret über die Clients {@code archiv-test*}, die es nur im Test-Realm gibt.
 */
public final class Testanmeldung {

    public static final String PASSWORT = "Test-Passwort-1";
    public static final String OTP_SECRET = "urinet-archiv-test-otp-nur-lokal";

    private record Token(String wert, long gueltigBis) {
    }

    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    // Keycloak nimmt denselben TOTP-Code je Benutzer nur einmal an.
    private static final Map<String, Long> LETZTER_SCHRITT = new ConcurrentHashMap<>();

    private Testanmeldung() {
    }

    /** Token über {@code archiv-test}; aus dem Cache, solange es noch mindestens 30 s gilt. */
    public static String token(String benutzer) {
        return token("archiv-test", benutzer);
    }

    public static String token(String client, String benutzer) {
        String schluessel = client + "/" + benutzer;
        Token token = TOKENS.get(schluessel);
        if (token == null || token.gueltigBis() < sekunden() + 30) {
            Response antwort = anmelden(server(), client, benutzer, PASSWORT, OTP_SECRET);
            if (antwort.statusCode() != 200) {
                throw new IllegalStateException("Anmeldung fehlgeschlagen für " + benutzer + ": " + antwort.statusCode());
            }
            token = new Token(antwort.path("access_token"), sekunden() + antwort.<Integer>path("expires_in"));
            TOKENS.put(schluessel, token);
        }
        return token.wert();
    }

    public static Response anmelden(String benutzer, boolean mitTotp) {
        return anmelden(server(), "archiv-test", benutzer, PASSWORT, mitTotp ? OTP_SECRET : null);
    }

    /** Password Grant; mit {@code otpSecret} zusätzlich der aktuelle TOTP-Code (wartet, falls er schon benutzt ist). */
    public static Response anmelden(String realmUrl, String client, String benutzer, String passwort, String otpSecret) {
        RequestSpecification anfrage = given().formParam("grant_type", "password")
                .formParam("client_id", client)
                .formParam("username", benutzer)
                .formParam("password", passwort);
        if (otpSecret != null) {
            anfrage = anfrage.formParam("totp", totp(otpSecret, freierSchritt(realmUrl + "/" + benutzer) * 30));
        }
        return anfrage.when().post(realmUrl + "/protocol/openid-connect/token");
    }

    /** RFC 6238 mit HmacSHA1, 30 s, 6 Stellen; Keycloak verwendet die UTF-8-Bytes des Secrets als Schlüssel. */
    static String totp(String secret, long sekunden) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(sekunden / 30).array());
            int versatz = hash[hash.length - 1] & 0x0f;
            int wert = (ByteBuffer.wrap(hash, versatz, 4).getInt() & 0x7fffffff) % 1_000_000;
            return String.format("%06d", wert);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized long freierSchritt(String benutzer) {
        long schritt = sekunden() / 30;
        if (Long.valueOf(schritt).equals(LETZTER_SCHRITT.get(benutzer))) {
            try {
                Thread.sleep((schritt + 1) * 30_000 - System.currentTimeMillis() + 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            schritt = sekunden() / 30;
        }
        LETZTER_SCHRITT.put(benutzer, schritt);
        return schritt;
    }

    private static long sekunden() {
        return System.currentTimeMillis() / 1000;
    }

    private static String server() {
        return ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
    }
}
