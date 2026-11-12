package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kontakte, auf die eine Seite der Vorschau verweist (Treffer und Dubletten-Kandidaten), damit die Oberfläche beim
 * Entscheid über eine Dublette zeigen kann, mit wem sie zusammenführen würde (Req 13.6, Aufgabe 4.4). Nur Kontakte des
 * eigenen Mandanten; Werte werden nie geloggt.
 */
@ApplicationScoped
public class ImportKontakte {

    private final KontaktRegister register;

    public ImportKontakte(KontaktRegister register) {
        this.register = register;
    }

    @Transactional
    public Map<UUID, Kontakt> zu(Collection<UUID> ids) {
        Map<UUID, Kontakt> kontakte = new LinkedHashMap<>();
        for (Kontakt kontakt : register.finden(ids)) {
            kontakte.put(kontakt.id(), kontakt);
        }
        return kontakte;
    }
}
