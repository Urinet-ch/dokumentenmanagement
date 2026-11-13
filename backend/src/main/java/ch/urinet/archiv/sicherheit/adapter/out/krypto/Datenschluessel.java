package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Datenschlüssel {@code archiv_data_key} für Zugangsdaten in der Datenbank (Spec 02, design.md «Verschluesselung»):
 * AES-256-GCM mit zufälligem 96-Bit-IV je Wert und 128-Bit-Tag. Ein Chiffrat ist {@code IV || Chiffrat mit Tag}. Der
 * {@code bezug} (z. B. Tabelle, Spalte und Mandant) geht als zusätzliche Daten in den Tag ein: Ein Chiffrat lässt sich
 * nur mit demselben Bezug entschlüsseln, also nicht in eine andere Spalte oder einen anderen Mandanten kopieren.
 * Die Datei enthält 32 zufällige Byte in Base64 (z. B. aus {@code openssl rand -base64 32}).
 */
public final class Datenschluessel {

    public static final int SCHLUESSEL_BYTES = 32;
    static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String VERFAHREN = "AES/GCM/NoPadding";
    private static final SecureRandom ZUFALL = new SecureRandom();

    private final SecretKeySpec schluessel;

    private Datenschluessel(byte[] roh) {
        this.schluessel = new SecretKeySpec(roh, "AES");
    }

    /** Liest den Schlüssel aus dem Inhalt der Secret-Datei; die Meldung eines Fehlers nennt nie den Inhalt. */
    public static Datenschluessel aus(String base64) {
        Objects.requireNonNull(base64, "base64");
        byte[] roh;
        try {
            roh = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Datenschlüssel ist kein Base64");
        }
        try {
            if (roh.length != SCHLUESSEL_BYTES) {
                throw new IllegalArgumentException("Datenschlüssel hat nicht " + SCHLUESSEL_BYTES + " Byte");
            }
            return new Datenschluessel(roh);
        } finally {
            Arrays.fill(roh, (byte) 0);
        }
    }

    public byte[] verschluesseln(byte[] klartext, String bezug) {
        Objects.requireNonNull(klartext, "klartext");
        byte[] iv = new byte[IV_BYTES];
        ZUFALL.nextBytes(iv);
        try {
            Cipher verfahren = Cipher.getInstance(VERFAHREN);
            verfahren.init(Cipher.ENCRYPT_MODE, schluessel, new GCMParameterSpec(TAG_BITS, iv));
            verfahren.updateAAD(bezug(bezug));
            byte[] chiffrat = verfahren.doFinal(klartext);
            byte[] ergebnis = Arrays.copyOf(iv, IV_BYTES + chiffrat.length);
            System.arraycopy(chiffrat, 0, ergebnis, IV_BYTES, chiffrat.length);
            return ergebnis;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM nicht verfügbar", e);
        }
    }

    /** @throws IllegalArgumentException wenn Chiffrat, Schlüssel oder Bezug nicht zusammenpassen */
    public byte[] entschluesseln(byte[] chiffrat, String bezug) {
        Objects.requireNonNull(chiffrat, "chiffrat");
        if (chiffrat.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("Chiffrat zu kurz");
        }
        try {
            Cipher verfahren = Cipher.getInstance(VERFAHREN);
            verfahren.init(Cipher.DECRYPT_MODE, schluessel, new GCMParameterSpec(TAG_BITS, chiffrat, 0, IV_BYTES));
            verfahren.updateAAD(bezug(bezug));
            return verfahren.doFinal(chiffrat, IV_BYTES, chiffrat.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Chiffrat passt nicht zu Schlüssel und Bezug");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM nicht verfügbar", e);
        }
    }

    private static byte[] bezug(String bezug) {
        return Objects.requireNonNull(bezug, "bezug").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Datenschluessel[***]";
    }
}
