package ch.urinet.archiv.sicherheit.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API-Token {@code ua_<id>_<geheimnis>} (Req 2.5): {@code id} aus 12 Zeichen {@code [a-z0-9]}, {@code geheimnis} aus
 * 32 Byte, base64url ohne Padding (43 Zeichen), beides aus {@link SecureRandom}. Gespeichert wird nur der Hash des
 * Geheimnisses. {@link #toString()} verbirgt das Geheimnis, damit es nie über ein Log oder eine Meldung hinausgeht.
 */
public final class ApiToken {

    public static final String PRAEFIX = "ua_";
    static final int ID_LAENGE = 12;
    static final int GEHEIMNIS_BYTES = 32;
    private static final char[] ID_ZEICHEN = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final Pattern AUFBAU = Pattern.compile("ua_([a-z0-9]{12})_([A-Za-z0-9_-]{43})");
    private static final Pattern ID = Pattern.compile("[a-z0-9]{12}");

    private final String id;
    private final String geheimnis;

    private ApiToken(String id, String geheimnis) {
        this.id = id;
        this.geheimnis = geheimnis;
    }

    public static ApiToken neu(SecureRandom zufall) {
        Objects.requireNonNull(zufall, "zufall");
        char[] id = new char[ID_LAENGE];
        for (int i = 0; i < id.length; i++) {
            id[i] = ID_ZEICHEN[zufall.nextInt(ID_ZEICHEN.length)];
        }
        byte[] geheimnis = new byte[GEHEIMNIS_BYTES];
        zufall.nextBytes(geheimnis);
        return new ApiToken(new String(id), Base64.getUrlEncoder().withoutPadding().encodeToString(geheimnis));
    }

    /** Liest {@code ua_<id>_<geheimnis>}; leer bei jedem anderen Aufbau. */
    public static Optional<ApiToken> lesen(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher treffer = AUFBAU.matcher(text);
        return treffer.matches() ? Optional.of(new ApiToken(treffer.group(1), treffer.group(2))) : Optional.empty();
    }

    /** {@code true}, wenn {@code id} wie die ID eines Tokens aussieht (z. B. Pfad-Parameter beim Widerruf). */
    public static boolean gueltigeId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public String id() {
        return id;
    }

    public String geheimnis() {
        return geheimnis;
    }

    /** Das ganze Token, nur für die einmalige Ausgabe beim Anlegen. */
    public String text() {
        return PRAEFIX + id + "_" + geheimnis;
    }

    /** Principal der Token-Identität und Benutzer im Zugriffsprotokoll. */
    public static String principal(String id) {
        return "token:" + id;
    }

    @Override
    public String toString() {
        return PRAEFIX + id + "_***";
    }
}
