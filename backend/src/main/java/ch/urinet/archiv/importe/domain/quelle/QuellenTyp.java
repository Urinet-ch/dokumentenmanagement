package ch.urinet.archiv.importe.domain.quelle;

import java.util.Optional;

/** Typen im Quellenregister (Spec 09, design.md Abschnitt 5). */
public enum QuellenTyp {
    GENERATOR, DRIVE, ORDNER, MAC_EINGANG, GMAIL_LABEL, CRM_CSV, SCAN;

    /**
     * Wert von {@code dokument.quelle} für Uploads in einen Lauf dieser Quelle; leer, wo der Erfassungsweg die Quelle
     * bestimmt (Spec 09, 10.4 und design.md: Mac-Eingang und Ordner setzen {@code ORDNER} über den Quelllauf, Gmail
     * {@code GMAIL}).
     */
    public Optional<String> dokumentQuelle() {
        return switch (this) {
            case ORDNER, MAC_EINGANG -> Optional.of("ORDNER");
            case GMAIL_LABEL -> Optional.of("GMAIL");
            default -> Optional.empty();
        };
    }
}
