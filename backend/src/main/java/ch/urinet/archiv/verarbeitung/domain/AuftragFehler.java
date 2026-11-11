package ch.urinet.archiv.verarbeitung.domain;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inhalt von {@code auftrag.fehler} (Req 16.7, design.md «Verarbeitung»): nur Art und Fehler-ID, nie eine Meldung,
 * z. B. {@code fehlgeschlagen fehler-id=<uuid>}. Die Details stehen nur im Log unter derselben Fehler-ID.
 */
public record AuftragFehler(String art, UUID fehlerId) {

    private static final Pattern ART = Pattern.compile("^[a-z]{1,30}$");
    private static final Pattern TEXT = Pattern.compile(
            "^([a-z]{1,30}) fehler-id=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    public AuftragFehler {
        if (art == null || !ART.matcher(art).matches()) {
            throw new IllegalArgumentException("Ungültige Fehlerart");
        }
        if (fehlerId == null) {
            throw new IllegalArgumentException("Fehler-ID fehlt");
        }
    }

    public static AuftragFehler neu(String art) {
        return new AuftragFehler(art, UUID.randomUUID());
    }

    /** Leer, wenn der Text nicht die Form {@code <art> fehler-id=<uuid>} hat. */
    public static Optional<AuftragFehler> lesen(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher treffer = TEXT.matcher(text);
        return treffer.matches()
                ? Optional.of(new AuftragFehler(treffer.group(1), UUID.fromString(treffer.group(2))))
                : Optional.empty();
    }

    public String text() {
        return art + " fehler-id=" + fehlerId;
    }
}
