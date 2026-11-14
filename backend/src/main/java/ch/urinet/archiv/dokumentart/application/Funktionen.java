package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Welche Funktionen der Mandant der laufenden Anfrage hat (Spec 05, Req 8.8), heute nur das Minimum für die
 * Chat-Werkzeuge {@code kennzahl} und {@code offene_posten} (Spec 02, Aufgabe 7.1b). Läuft in der Transaktion und im
 * Mandanten des Aufrufers.
 * <p>
 * Spec 05, 17.2/18.2 verschiebt das nach {@code konfiguration} und ergänzt {@code GET /mandant/funktionen}.
 */
@ApplicationScoped
public class Funktionen {

    /** Gruppe der Arten mit Finanzangaben (Betrag, Richtung, Fälligkeit). */
    public static final String GRUPPE_FINANZ = "finanz";

    private final DokumentartRepository arten;

    public Funktionen(DokumentartRepository arten) {
        this.arten = arten;
    }

    /** Hat der Mandant mindestens eine aktive Art mit der Gruppe {@value #GRUPPE_FINANZ}? */
    public boolean finanzVerfuegbar() {
        return arten.alle().stream().anyMatch(Funktionen::finanzart);
    }

    private static boolean finanzart(Dokumentart art) {
        return art.aktiv() && art.gruppen().contains(GRUPPE_FINANZ);
    }
}
