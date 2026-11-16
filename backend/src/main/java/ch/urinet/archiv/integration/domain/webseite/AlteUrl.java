package ch.urinet.archiv.integration.domain.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Alte URL der bisherigen Webseite in der Form der Zuordnungstabelle {@code alte_url} (Spec 03, Req 4.3, 5.10): nur der
 * Pfad, ohne Schema, Host (auch protokollrelativ {@code //host/pfad}), Anfrage ({@code ?…}) und Fragment ({@code #…}), Prozent-Kodierung aufgelöst (UTF-8), Unicode
 * in NFC, doppelte Schrägstriche zusammengefasst, ohne abschliessenden Schrägstrich (ausser Wurzel). Gross- und
 * Kleinschreibung bleibt: Dateipfade sind verschieden, auch wenn die Seite sie gleich behandelt. Der CSV-Import der
 * alten Links (Aufgabe 10.5) und die Abfrage {@code /public/v1/umleitung} verwenden diese Form; was sich nicht so lesen
 * lässt (kein Pfad, Steuerzeichen, ungültige Prozent-Folge, zu lang), ist ungültig.
 */
public final class AlteUrl {

    public static final int MAX_LAENGE = 2000;
    private static final Pattern AUTORITAET = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*:)?//[^/?#]*");

    private AlteUrl() {
    }

    /** Die normalisierte Form oder 400 {@code ANFRAGE_UNGUELTIG}; der Wert steht nie in der Fehlermeldung. */
    public static String normalisieren(String eingabe) {
        return versuchen(eingabe).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    /** Wie {@link #normalisieren(String)}, leer statt Fehler. */
    public static Optional<String> versuchen(String eingabe) {
        if (eingabe == null || eingabe.isBlank() || eingabe.length() > MAX_LAENGE
                || eingabe.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        String text = eingabe.strip();
        var autoritaet = AUTORITAET.matcher(text);
        boolean mitHost = autoritaet.find();
        if (mitHost) {
            text = text.substring(autoritaet.end());
        }
        int ende = text.length();
        for (char zeichen : new char[] {'?', '#'}) {
            int stelle = text.indexOf(zeichen);
            if (stelle >= 0 && stelle < ende) {
                ende = stelle;
            }
        }
        String pfad = text.substring(0, ende);
        if (pfad.isEmpty() && mitHost) {
            pfad = "/";
        }
        if (!pfad.startsWith("/")) {
            return Optional.empty();
        }
        Optional<String> entschluesselt = entschluesseln(pfad);
        if (entschluesselt.isEmpty() || entschluesselt.get().chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        String normal = Normalizer.normalize(entschluesselt.get(), Normalizer.Form.NFC).replaceAll("/{2,}", "/");
        if (normal.length() > 1 && normal.endsWith("/")) {
            normal = normal.substring(0, normal.length() - 1);
        }
        return Optional.of(normal);
    }

    /** {@code %XX} als UTF-8; eine unvollständige oder ungültige Folge ist ungültig. */
    private static Optional<String> entschluesseln(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length()) {
                    return Optional.empty();
                }
                int hoch = hexWert(text.charAt(i + 1));
                int tief = hexWert(text.charAt(i + 2));
                if (hoch < 0 || tief < 0) {
                    return Optional.empty();
                }
                bytes.write(hoch * 16 + tief);
                i += 2;
            } else {
                if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    bytes.writeBytes(text.substring(i, i + 2).getBytes(StandardCharsets.UTF_8));
                    i++;
                } else if (Character.isSurrogate(c)) {
                    return Optional.empty();
                } else {
                    bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** Nur ASCII-Hex-Ziffern; {@link Character#digit} liesse auch Vollbreite- und andere Unicode-Ziffern zu. */
    private static int hexWert(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }
}
