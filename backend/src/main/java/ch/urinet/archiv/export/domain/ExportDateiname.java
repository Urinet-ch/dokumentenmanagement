package ch.urinet.archiv.export.domain;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dateinamen im Jahresexport (Spec 02, Req 7.4): {@code <datum>_<art>_<belegnummer|id>_<partner>.<endung>}, nur ASCII,
 * ohne Leerzeichen. Der Unterstrich trennt die Teile und kommt in keinem Teil vor. Der Dateiname beim Eingang
 * (Spec 01, Req 3.9) fliesst nie ein (Req 3.12).
 * <ul>
 *     <li>{@code datum}: Belegdatum {@code yyyy-mm-dd}, ohne Belegdatum das Eingangsdatum (Europe/Zurich).</li>
 *     <li>{@code art}: Code der Dokumentart, ohne Art {@code ohne-art}.</li>
 *     <li>{@code belegnummer|id}: die Belegnummer, ohne (oder ohne ASCII-Zeichen) die Dokument-ID.</li>
 *     <li>{@code partner}: Name des Partners, ohne Partner {@code ohne-partner}.</li>
 * </ul>
 * Umlaute werden zu {@code ae}, {@code oe}, {@code ue}, {@code ss}; übrige Akzente fallen weg; jedes andere Zeichen
 * ausser {@code A–Z a–z 0–9 . -} wird zu {@code -}, Folgen zu einem. Belegnummer und Partner sind höchstens
 * {@value #MAX_TEIL} Zeichen lang. Mehrere Dateien eines Dokuments (Originale bei {@code pdfa_fehlt}) erhalten
 * {@code -<nr>} vor der Endung. Gleiche Namen im selben Export unterscheidet {@link Vergabe} mit dem Anfang der
 * Dokument-ID.
 */
public final class ExportDateiname {

    public static final int MAX_TEIL = 60;
    private static final Pattern NICHT_ERLAUBT = Pattern.compile("[^A-Za-z0-9.-]+");
    private static final Pattern STRICHE = Pattern.compile("-{2,}");
    private static final Pattern RAND = Pattern.compile("^[.-]+|[.-]+$");
    private static final Pattern MARKEN = Pattern.compile("\\p{M}+");
    private static final Pattern ENDUNG = Pattern.compile("^[a-z0-9]{1,10}$");
    /** Name im ZIP: nur die erlaubten Zeichen und der Unterstrich als Trenner. */
    public static final Pattern MUSTER = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}_[A-Za-z0-9.-]+_[A-Za-z0-9.-]+_[A-Za-z0-9.-]+\\.[a-z0-9]{1,10}$");

    private ExportDateiname() {
    }

    /**
     * Name ohne Nummer; {@code nr} &gt; 0 hängt {@code -<nr>} an (mehrere Dateien eines Dokuments).
     */
    public static String fuer(LocalDate datum, String art, String belegnummer, UUID id, String partner, int nr,
                              String endung) {
        Objects.requireNonNull(datum, "datum");
        Objects.requireNonNull(id, "id");
        String nummer = teil(belegnummer).orElse(id.toString());
        String name = datum + "_" + teil(art).orElse("ohne-art") + "_" + nummer + "_"
                + teil(partner).orElse("ohne-partner");
        return name + (nr > 0 ? "-" + nr : "") + "." + endung(endung);
    }

    /** ASCII-Teil ohne Trenner; leer, wenn nichts übrig bleibt. */
    static Optional<String> teil(String roh) {
        if (roh == null) {
            return Optional.empty();
        }
        String text = roh.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss");
        text = MARKEN.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("");
        text = NICHT_ERLAUBT.matcher(text).replaceAll("-");
        text = STRICHE.matcher(text).replaceAll("-");
        text = RAND.matcher(text).replaceAll("");
        if (text.length() > MAX_TEIL) {
            text = RAND.matcher(text.substring(0, MAX_TEIL)).replaceAll("");
        }
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    static String endung(String endung) {
        String e = endung == null ? "" : endung.toLowerCase(Locale.ROOT);
        return ENDUNG.matcher(e).matches() ? e : "bin";
    }

    /**
     * Vergibt Namen eindeutig innerhalb eines Exports: Ist ein Name schon vergeben, kommt vor der Endung
     * {@code -<erste 8 Zeichen der ID>} dazu, notfalls die ganze ID. Hält nur die Namen, nicht die Dateien.
     */
    public static final class Vergabe {

        private final Set<String> vergeben = new HashSet<>();

        public String vergeben(String name, UUID id) {
            if (vergeben.add(name)) {
                return name;
            }
            int punkt = name.lastIndexOf('.');
            String stamm = name.substring(0, punkt);
            String endung = name.substring(punkt);
            String kurz = stamm + "-" + id.toString().substring(0, 8) + endung;
            if (vergeben.add(kurz)) {
                return kurz;
            }
            String lang = stamm + "-" + id + endung;
            for (int i = 2; !vergeben.add(lang); i++) {
                lang = stamm + "-" + id + "-" + i + endung;
            }
            return lang;
        }
    }
}
