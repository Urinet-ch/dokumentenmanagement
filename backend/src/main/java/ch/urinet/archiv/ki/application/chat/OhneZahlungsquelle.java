package ch.urinet.archiv.ki.application.chat;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/** Standard bis Welle 8: keine AbaNinja-Verbindung, Zahlstatus und Zahlungsdatum sind unbekannt. */
@DefaultBean
@ApplicationScoped
public class OhneZahlungsquelle implements Zahlungsquelle {

    @Override
    public boolean verbunden() {
        return false;
    }
}
