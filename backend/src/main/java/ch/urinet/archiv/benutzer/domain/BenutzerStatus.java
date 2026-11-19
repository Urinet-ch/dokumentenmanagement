package ch.urinet.archiv.benutzer.domain;

/** Benutzerlebenszyklus der Verwaltung (Spec 06, Requirement 3.16). */
public enum BenutzerStatus {
    EINGELADEN,
    AKTIV,
    GESPERRT,
    DEAKTIVIERT,
    GELOESCHT;

    public boolean darfWechselnZu(BenutzerStatus neu) {
        return switch (this) {
            case EINGELADEN -> neu == AKTIV || neu == DEAKTIVIERT;
            case AKTIV -> neu == GESPERRT || neu == DEAKTIVIERT;
            case GESPERRT -> neu == AKTIV || neu == DEAKTIVIERT;
            case DEAKTIVIERT -> neu == EINGELADEN || neu == GELOESCHT;
            case GELOESCHT -> false;
        };
    }
}
