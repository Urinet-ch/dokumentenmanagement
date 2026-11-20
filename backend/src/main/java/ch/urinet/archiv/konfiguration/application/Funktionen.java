package ch.urinet.archiv.konfiguration.application;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Funktionen des Mandanten im laufenden Kontext (Spec 05, Req 8.8).
 */
@ApplicationScoped
public class Funktionen {

    public static final String GRUPPE_FINANZ = "finanz";

    private final KonfigurationLesen konfiguration;

    public Funktionen(KonfigurationLesen konfiguration) {
        this.konfiguration = konfiguration;
    }

    /** Hat der Mandant mindestens eine aktive Art mit der Gruppe {@value #GRUPPE_FINANZ}? */
    public boolean finanzVerfuegbar() {
        return konfiguration.aktuell().arten().stream()
                .anyMatch(art -> art.aktiv() && art.gruppen().contains(GRUPPE_FINANZ));
    }
}
