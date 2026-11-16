package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import jakarta.enterprise.context.RequestScoped;

/**
 * Hält den {@link Lesezugriff} der laufenden Anfrage, den {@link LeseZugriffFilter} gesetzt hat. Endpunkte mit
 * {@code @WebseitenZugang(LESEN)} injizieren das Objekt und geben {@link #zugriff()} an Abfragen weiter; ohne gesetzten
 * Zugriff ({@link IllegalStateException}) liefert nichts Daten (fail closed).
 */
@RequestScoped
public class LeseAnfrage {

    private Lesezugriff zugriff;

    void setzen(Lesezugriff zugriff) {
        this.zugriff = zugriff;
    }

    public Lesezugriff zugriff() {
        if (zugriff == null) {
            throw new IllegalStateException("Kein Lesezugriff gesetzt");
        }
        return zugriff;
    }
}
