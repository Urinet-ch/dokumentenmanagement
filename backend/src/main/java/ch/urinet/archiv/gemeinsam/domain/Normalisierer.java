package ch.urinet.archiv.gemeinsam.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Normalisierung nach design.md «Normalisierung» (Partner-Abgleich Req 7.4, CSV-Import Req 13, Gate-Vergleich Req 0.7).
 * Jede Methode ist rein und liefert für {@code null} den leeren Text.
 */
public final class Normalisierer {

    private static final Set<String> RECHTSFORMEN = Set.of("ag", "gmbh", "sa", "sarl", "sagl", "kg", "klg", "verein",
            "genossenschaft", "stiftung", "ltd", "inc");
    private static final Pattern AKZENTE = Pattern.compile("\\p{M}+");
    private static final Pattern SATZZEICHEN = Pattern.compile("[\\p{P}\\p{S}]");
    private static final Pattern LEERRAUM = Pattern.compile("\\s+");
    private static final Pattern NICHT_ZIFFER = Pattern.compile("[^0-9]");
    private static final Pattern UID_TRENNER = Pattern.compile("[\\s.\\-]");
    private static final Pattern UID_ZUSATZ = Pattern.compile("(MWST|TVA|IVA|HR)$");

    private Normalisierer() {
    }

    /**
     * NFKC, klein, Umlaute und ß ausgeschrieben, übrige Akzente entfernt, {@code &} → «und», Satzzeichen → Leerzeichen,
     * Rechtsformen als ganzes Wort entfernt, Leerraum zusammengefasst und getrimmt.
     */
    public static String name(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("&", " und ");
        s = AKZENTE.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        s = SATZZEICHEN.matcher(s).replaceAll(" ");
        return Arrays.stream(LEERRAUM.split(s.strip()))
                .filter(wort -> !wort.isEmpty() && !RECHTSFORMEN.contains(wort))
                .collect(Collectors.joining(" "));
    }

    /** Name einer Person aus Vorname und Name (Profil «21.CRM», design.md «Kontakt und Importe»). */
    public static String personName(String vorname, String name) {
        return name((vorname == null ? "" : vorname) + " " + (name == null ? "" : name));
    }

    /** Nur Ziffern. */
    public static String plz(String plz) {
        return plz == null ? "" : NICHT_ZIFFER.matcher(plz).replaceAll("");
    }

    /** Klein und getrimmt. */
    public static String email(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Ohne Leerraum, gross. */
    public static String iban(String iban) {
        return iban == null ? "" : LEERRAUM.matcher(iban).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /**
     * UID (Auslegung, design.md nennt keine Regel): gross, ohne Leerraum, Punkte und Bindestriche, ohne den Zusatz
     * MWST/TVA/IVA/HR am Ende, z. B. «CHE-123.456.789 MWST» → «CHE123456789».
     */
    public static String uid(String uid) {
        if (uid == null) {
            return "";
        }
        String s = UID_TRENNER.matcher(uid).replaceAll("").toUpperCase(Locale.ROOT);
        return UID_ZUSATZ.matcher(s).replaceAll("");
    }
}
