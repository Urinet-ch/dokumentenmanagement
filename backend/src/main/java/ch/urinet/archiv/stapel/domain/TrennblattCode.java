package ch.urinet.archiv.stapel.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Inhalt des QR-Codes auf einem Trennblatt oder Ordner-Deckblatt (Spec 09, Glossar, Req 6.2, 6.5; design.md
 * «Trennblatt-Generator»): {@code URINET-TRENN;v=1}, {@code URINET-TRENN;v=1;art=<CODE>} oder
 * {@code URINET-ORDNER;v=1;titel=<Titel>}. Der Titel steht URL-kodiert (UTF-8, Leerzeichen als {@code %20}) im Code,
 * damit {@code ;} und {@code =} im Titel den Aufbau nicht stören und der Code nur ASCII enthält.
 *
 * @param typ   Trennblatt oder Ordner-Deckblatt
 * @param art   Code der Dokumentart, nur beim Trennblatt, sonst {@code null}
 * @param titel Titel des Deckblatts (NFC, ohne Leerraum aussen), nur beim Deckblatt, sonst {@code null}
 */
public record TrennblattCode(Typ typ, String art, String titel) {

    public enum Typ { TRENN, ORDNER }

    /** Höchstens so viele Zeichen (Unicode-Codepunkte nach NFC) hat der Titel eines Deckblatts (Req 6.1). */
    public static final int TITEL_MAX = 60;

    static final String TRENN = "URINET-TRENN";
    static final String ORDNER = "URINET-ORDNER";
    static final String VERSION = "v=1";
    // Syntax der Codes von Dokumentarten (Spec 01, Req 9.1); ob die Art aktiv ist, prüft die Anwendung
    private static final Pattern ART = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    // Ausgabe von URLEncoder: unreservierte Zeichen und %XX mit grossen Hex-Ziffern ("+" kommt nie vor)
    private static final Pattern KODIERT = Pattern.compile("^(?:[A-Za-z0-9*._-]|%[0-9A-F]{2})+$");

    public TrennblattCode {
        Objects.requireNonNull(typ, "typ");
        if (typ == Typ.TRENN && (titel != null || art != null && !ART.matcher(art).matches())
                || typ == Typ.ORDNER && (art != null || !titelGueltig(titel))) {
            throw new IllegalArgumentException("Trennblatt-Code ungültig");
        }
    }

    /** Trennblatt ohne Art ({@code art == null}) oder mit Art; eine Art mit falscher Syntax ist ein Programmfehler. */
    public static TrennblattCode trenn(String art) {
        return new TrennblattCode(Typ.TRENN, art, null);
    }

    /**
     * Ordner-Deckblatt mit dem Titel in NFC, ohne Leerraum aussen. Fehlt der Titel, ist er nach dem Kürzen leer, länger
     * als {@value #TITEL_MAX} Zeichen oder enthält er Zeichen nach Spec 01, 3.10, folgt 400
     * {@code TRENNBLATT_UNGUELTIG} (Req 6.5).
     */
    public static TrennblattCode ordner(String titel) {
        if (titel == null || titel.codePoints().anyMatch(TrennblattCode::verboten)) {
            throw new FachFehler(Fehlercode.TRENNBLATT_UNGUELTIG);
        }
        String sauber = Normalizer.normalize(titel, Normalizer.Form.NFC).strip();
        if (!titelGueltig(sauber)) {
            throw new FachFehler(Fehlercode.TRENNBLATT_UNGUELTIG);
        }
        return new TrennblattCode(Typ.ORDNER, null, sauber);
    }

    /** Der Text für den QR-Code. */
    public String inhalt() {
        return switch (typ) {
            case TRENN -> TRENN + ";" + VERSION + (art == null ? "" : ";art=" + art);
            case ORDNER -> ORDNER + ";" + VERSION + ";titel="
                    + URLEncoder.encode(titel, StandardCharsets.UTF_8).replace("+", "%20");
        };
    }

    /**
     * Liest einen QR-Inhalt in genau dem Aufbau von {@link #inhalt()}; alles andere ist leer: fremde Codes wie Swiss QR,
     * andere Version, unbekannte oder doppelte Felder, ungültiger Titel, ungültiges UTF-8 (z. B. {@code %FF}) und jede
     * nicht kanonische Kodierung (unkodierte Trennzeichen wie {@code =} oder {@code +}, Hex in Kleinbuchstaben). Der
     * Inhalt muss Zeichen für Zeichen dem entsprechen, was {@link #inhalt()} für den gelesenen Code erzeugt.
     */
    public static Optional<TrennblattCode> lesen(String inhalt) {
        if (inhalt == null) {
            return Optional.empty();
        }
        String[] teile = inhalt.split(";", -1);
        if (teile.length < 2 || !VERSION.equals(teile[1])) {
            return Optional.empty();
        }
        TrennblattCode code = null;
        try {
            if (TRENN.equals(teile[0]) && teile.length == 2) {
                code = trenn(null);
            } else if (TRENN.equals(teile[0]) && teile.length == 3 && teile[2].startsWith("art=")) {
                code = trenn(teile[2].substring(4));
            } else if (ORDNER.equals(teile[0]) && teile.length == 3 && teile[2].startsWith("titel=")) {
                code = ordner(dekodieren(teile[2].substring(6)));
            }
        } catch (IllegalArgumentException | FachFehler | CharacterCodingException e) {
            // ungültige Art, falsche %-Folge, ungültiges UTF-8 oder ungültiger Titel: kein Trennblatt-Code
        }
        return code != null && code.inhalt().equals(inhalt) ? Optional.of(code) : Optional.empty();
    }

    /** %-Kodierung streng: nur die Zeichen von {@link URLEncoder} und {@code %XX}, Bytes als gültiges UTF-8. */
    private static String dekodieren(String kodiert) throws CharacterCodingException {
        if (!KODIERT.matcher(kodiert).matches()) {
            throw new IllegalArgumentException("Kodierung");
        }
        ByteBuffer bytes = ByteBuffer.allocate(kodiert.length());
        for (int i = 0; i < kodiert.length(); i++) {
            char c = kodiert.charAt(i);
            if (c == '%') {
                bytes.put((byte) Integer.parseInt(kodiert, i + 1, i + 3, 16));
                i += 2;
            } else {
                bytes.put((byte) c);
            }
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes.flip()).toString();
    }

    private static boolean titelGueltig(String titel) {
        return titel != null && !titel.isEmpty() && titel.codePointCount(0, titel.length()) <= TITEL_MAX
                && titel.codePoints().noneMatch(TrennblattCode::verboten);
    }

    /** Wie beim Dateinamen (Spec 01, 3.10): Cc, Cs, Zl, Zp und Cf ausser den Verbindern U+200C/U+200D. */
    private static boolean verboten(int c) {
        int typ = Character.getType(c);
        return typ == Character.CONTROL || typ == Character.SURROGATE || typ == Character.LINE_SEPARATOR
                || typ == Character.PARAGRAPH_SEPARATOR
                || typ == Character.FORMAT && c != 0x200C && c != 0x200D;
    }
}
