package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import ch.urinet.archiv.sicherheit.application.GeheimnisHasher;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Argon2id mit Bouncy Castle ({@link Argon2BytesGenerator}), keine eigene Krypto (Req 2.5). Parameter nach OWASP
 * (Password Storage Cheat Sheet, erste Empfehlung): m = 19 MiB, t = 2, p = 1; Salt 16 Byte je Token, Hash 32 Byte.
 * Gespeichert als PHC-String {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>} (base64 ohne Padding), damit ein
 * späterer Wechsel der Parameter alte Hashes weiter prüfen kann. Verglichen wird mit {@link MessageDigest#isEqual}.
 */
@ApplicationScoped
public class Argon2GeheimnisHasher implements GeheimnisHasher {

    static final int SPEICHER_KIB = 19 * 1024;
    static final int DURCHLAEUFE = 2;
    static final int PARALLEL = 1;
    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;
    static final int SPEICHER_MAX_KIB = 256 * 1024;
    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\$([A-Za-z0-9+/]{16,})\\$([A-Za-z0-9+/]{16,})");
    private static final Base64.Encoder KODIEREN = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DEKODIEREN = Base64.getDecoder();

    private final SecureRandom zufall = new SecureRandom();

    @Override
    public String hashen(String geheimnis) {
        byte[] salt = new byte[SALT_BYTES];
        zufall.nextBytes(salt);
        byte[] hash = rechnen(geheimnis, salt, SPEICHER_KIB, DURCHLAEUFE, PARALLEL, HASH_BYTES);
        return "$argon2id$v=19$m=" + SPEICHER_KIB + ",t=" + DURCHLAEUFE + ",p=" + PARALLEL + "$"
                + KODIEREN.encodeToString(salt) + "$" + KODIEREN.encodeToString(hash);
    }

    @Override
    public boolean pruefen(String geheimnis, String gespeichert) {
        Matcher teile = gespeichert == null ? null : PHC.matcher(gespeichert);
        if (teile == null || !teile.matches()) {
            return false;
        }
        try {
            int speicher = Integer.parseInt(teile.group(1));
            int durchlaeufe = Integer.parseInt(teile.group(2));
            int parallel = Integer.parseInt(teile.group(3));
            byte[] salt = DEKODIEREN.decode(teile.group(4));
            byte[] erwartet = DEKODIEREN.decode(teile.group(5));
            // nur plausible Parameter, damit ein verfälschter Eintrag keinen Speicher erschöpft
            if (parallel < 1 || speicher < 8 * parallel || speicher > SPEICHER_MAX_KIB || durchlaeufe < 1
                    || erwartet.length < SALT_BYTES) {
                return false;
            }
            return MessageDigest.isEqual(erwartet,
                    rechnen(geheimnis, salt, speicher, durchlaeufe, parallel, erwartet.length));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] rechnen(String geheimnis, byte[] salt, int speicher, int durchlaeufe, int parallel,
                                  int laenge) {
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(speicher).withIterations(durchlaeufe).withParallelism(parallel)
                .build());
        byte[] ergebnis = new byte[laenge];
        generator.generateBytes(geheimnis.getBytes(StandardCharsets.UTF_8), ergebnis);
        return ergebnis;
    }
}
