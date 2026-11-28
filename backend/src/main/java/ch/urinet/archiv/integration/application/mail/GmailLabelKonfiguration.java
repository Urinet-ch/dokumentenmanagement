package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.Postfach;

import java.time.LocalDate;

/** Validierte Konfiguration einer Quelle vom Typ {@code GMAIL_LABEL} (Spec 09, Requirement 7.1, 7.8). */
public record GmailLabelKonfiguration(Postfach postfach, String label, LocalDate seit, boolean ohneAnhang,
                                      int maxJeLauf) {

    public GmailLabelKonfiguration {
        if (label == null || label.isBlank() || label.length() > 200
                || label.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Ungültiges Gmail-Label");
        }
        if (postfach == null || seit == null || maxJeLauf < 1 || maxJeLauf > 10_000) {
            throw new IllegalArgumentException("Ungültige Gmail-Laufgrenze");
        }
    }
}
