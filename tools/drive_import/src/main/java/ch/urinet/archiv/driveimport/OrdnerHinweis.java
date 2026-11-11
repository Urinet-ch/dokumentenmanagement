package ch.urinet.archiv.driveimport;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baut den {@code hinweis} einer Datei aus ihrem Ordnerpfad (Req 12.1, 12.2): «Drive-Import; Ordner: …» und, wenn ein
 * bekanntes Muster passt, «Partner: …», «Art: …», «Jahr: …». Der Ordnerpfad beginnt beim Namen des Startordners
 * (z. B. {@code UriNet/Kunden/FC Beispiel/Rechnungen}); Muster gelten ohne Rücksicht auf Gross-/Kleinschreibung:
 * <ul>
 *   <li>{@code Kunden/<Kunde>/…} → Partner {@code <Kunde>}</li>
 *   <li>{@code Kunden/<Kunde>/Rechnungen} → Art {@code AUSGANGSRECHNUNG}</li>
 *   <li>{@code Offerten} (irgendwo im Pfad) → Art {@code OFFERTE}</li>
 *   <li>{@code Buchhaltung <Jahr>} → Bereich Buchhaltung, Jahr {@code <Jahr>}</li>
 * </ul>
 * Der Hinweis hält die Grenzen des Archivs ein (höchstens {@value #MAX_ZEICHEN} Codepoints, keine Steuerzeichen):
 * Steuerzeichen werden zu Leerzeichen, ein zu langer Ordnerpfad wird vorne mit «…» gekürzt.
 */
final class OrdnerHinweis {

    static final int MAX_ZEICHEN = 1000;
    private static final Pattern BUCHHALTUNG = Pattern.compile("buchhaltung\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);

    private OrdnerHinweis() {
    }

    static String von(Path wurzel, Path datei) {
        List<String> teile = new ArrayList<>();
        Path name = wurzel.toAbsolutePath().normalize().getFileName();
        if (name != null) {
            teile.add(sauber(name.toString()));
        }
        Path ordner = datei.getParent() == null ? null : wurzel.relativize(datei.getParent());
        if (ordner != null) {
            for (Path teil : ordner) {
                if (!teil.toString().isEmpty()) {
                    teile.add(sauber(teil.toString()));
                }
            }
        }
        StringBuilder zusatz = new StringBuilder();
        for (int i = 0; i < teile.size(); i++) {
            String teil = teile.get(i).toLowerCase(Locale.ROOT);
            if (teil.equals("kunden") && i + 1 < teile.size()) {
                zusatz.append("; Partner: ").append(teile.get(i + 1));
                if (i + 2 < teile.size() && teile.get(i + 2).equalsIgnoreCase("rechnungen")) {
                    zusatz.append("; Art: AUSGANGSRECHNUNG");
                }
            }
            if (teil.equals("offerten")) {
                zusatz.append("; Art: OFFERTE");
            }
            Matcher jahr = BUCHHALTUNG.matcher(teile.get(i));
            if (jahr.matches()) {
                zusatz.append("; Bereich: Buchhaltung; Jahr: ").append(jahr.group(1));
            }
        }
        String pfad = String.join("/", teile);
        String kopf = "Drive-Import; Ordner: ";
        int platz = MAX_ZEICHEN - kopf.codePointCount(0, kopf.length())
                - zusatz.codePointCount(0, zusatz.length());
        if (platz < 1) {
            return kuerzen(kopf + pfad + zusatz);
        }
        if (pfad.codePointCount(0, pfad.length()) > platz) {
            int weg = pfad.codePointCount(0, pfad.length()) - (platz - 1);
            pfad = "…" + pfad.substring(pfad.offsetByCodePoints(0, weg));
        }
        return kopf + pfad + zusatz;
    }

    /** NFC (macOS speichert Dateinamen als NFD) und Steuer- bzw. Formatzeichen durch Leerzeichen ersetzen. */
    static String sauber(String text) {
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder aus = new StringBuilder(nfc.length());
        nfc.codePoints().forEach(c -> {
            int typ = Character.getType(c);
            aus.appendCodePoint(typ == Character.CONTROL || typ == Character.FORMAT && c != 0x200C && c != 0x200D
                    || typ == Character.SURROGATE ? ' ' : c);
        });
        return aus.toString();
    }

    private static String kuerzen(String text) {
        return text.codePointCount(0, text.length()) <= MAX_ZEICHEN ? text
                : text.substring(0, text.offsetByCodePoints(0, MAX_ZEICHEN));
    }
}
