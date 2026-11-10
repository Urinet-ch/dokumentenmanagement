package ch.urinet.archiv.dokumentart;

import ch.urinet.archiv.dokumentart.application.DokumentZaehlerPort;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attrappe für Req 9.4, bis 2.2 die Tabelle {@code dokument} und den echten Zähler bringt: Szenarien setzen die Zahl
 * der Dokumente je Art; ohne Eintrag zählt sie 0 wie {@code OhneDokumenteZaehler}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class DokumentZaehlerAttrappe implements DokumentZaehlerPort {

    private final Map<String, Long> anzahl = new ConcurrentHashMap<>();

    @Override
    public long anzahl(String art) {
        return anzahl.getOrDefault(art, 0L);
    }

    void setzen(String art, long dokumente) {
        anzahl.put(art, dokumente);
    }

    void leeren() {
        anzahl.clear();
    }
}
