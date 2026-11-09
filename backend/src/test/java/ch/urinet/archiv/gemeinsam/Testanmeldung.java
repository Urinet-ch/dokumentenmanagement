package ch.urinet.archiv.gemeinsam;

import io.restassured.response.Response;
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
 * mit bekanntem Test-Secret über den Client {@code archiv-test}, den es nur im Test-Realm gibt.
 */
public final class Testanmeldung {

    static final String PASSWORT = "Test-Passwort-1";
    static final String OTP_SECRET = "urinet-archiv-test-otp-nur-lokal";

    // Keycloak lässt denselben TOTP-Code je Benutzer nur einmal zu; darum ein Token je Benutzer und Lauf.
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    private Testanmeldung() {
    }

    public static String token(String benutzer) {
        return TOKENS.computeIfAbsent(benutzer, b -> {
            Response antwort = anmelden(b, true);
            if (antwort.statusCode() != 200) {
                throw new IllegalStateException("Anmeldung fehlgeschlagen für " + b + ": " + antwort.statusCode());
            }
            return antwort.path("access_token");
        });
    }

    public static Response anmelden(String benutzer, boolean mitTotp) {
        var anfrage = given().formParam("grant_type", "password")
                .formParam("client_id", "archiv-test")
                .formParam("username", benutzer)
                .formParam("password", PASSWORT);
        if (mitTotp) {
            anfrage = anfrage.formParam("totp", totp(OTP_SECRET, System.currentTimeMillis() / 1000));
        }
        String server = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        return anfrage.when().post(server + "/protocol/openid-connect/token");
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
}
