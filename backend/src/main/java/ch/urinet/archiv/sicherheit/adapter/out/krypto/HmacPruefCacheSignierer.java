package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import ch.urinet.archiv.sicherheit.application.PruefCacheSignierer;
import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

@ApplicationScoped
public class HmacPruefCacheSignierer implements PruefCacheSignierer {

    private static final String ALGORITHMUS = "HmacSHA256";
    private static final SecureRandom ZUFALL = new SecureRandom();
    private final byte[] schluessel = new byte[32];

    public HmacPruefCacheSignierer() {
        ZUFALL.nextBytes(schluessel);
    }

    @Override
    public byte[] signieren(String geheimnis) {
        try {
            Mac mac = Mac.getInstance(ALGORITHMUS);
            mac.init(new SecretKeySpec(schluessel, ALGORITHMUS));
            return mac.doFinal(geheimnis.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC für Webseiten-Prüf-Cache nicht verfügbar", e);
        }
    }
}
