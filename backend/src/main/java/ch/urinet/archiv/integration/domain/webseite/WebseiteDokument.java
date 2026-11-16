package ch.urinet.archiv.integration.domain.webseite;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein Dokument mit den Antwortfeldern der Webseiten-Schnittstelle (Spec 03, design.md «Antwortfelder», Req 5.11): nie
 * Dateiname, Vertraulichkeit, Status, {@code veroeffentlicht}, Referenzen oder URLs. {@code auszug} ist ein kurzer Text
 * aus dem Dokument ohne Dateinamen, nur bei Suche und Details gesetzt.
 */
public record WebseiteDokument(UUID id, String titel, WebseiteReihe reihe, String ausgabe, Integer jahr,
                               LocalDate datum, String zugriff, Integer seiten, String auszug) {

    /** Längster Auszug in Zeichen. */
    public static final int AUSZUG_MAX = 300;

    public WebseiteDokument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(zugriff, "zugriff");
        if (auszug != null) {
            String eng = auszug.replaceAll("\\s+", " ").strip();
            auszug = eng.isEmpty() ? null : kuerzen(eng);
        }
    }

    /** Höchstens {@link #AUSZUG_MAX} Zeichen (Unicode-Zeichen, nie ein halbes Surrogatpaar). */
    private static String kuerzen(String text) {
        if (text.codePointCount(0, text.length()) <= AUSZUG_MAX) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, AUSZUG_MAX)).strip();
    }
}
