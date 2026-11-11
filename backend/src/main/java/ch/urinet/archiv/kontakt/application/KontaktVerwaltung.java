package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Kontakte suchen (Partnerauswahl, A6) und von Hand anlegen oder ändern (A12, Req 2.18). Die Rechte prüft der
 * {@code ZugangFilter} vor dem Aufruf. Das Log nennt nur IDs, nie Kontaktwerte (Req 16.7).
 */
@ApplicationScoped
public class KontaktVerwaltung {

    /** Höchstzahl Treffer der Suche für die Partnerauswahl. */
    public static final int SUCHE_ANZAHL = 20;

    private static final Logger LOG = Logger.getLogger(KontaktVerwaltung.class);

    private final KontaktRegister register;

    public KontaktVerwaltung(KontaktRegister register) {
        this.register = register;
    }

    public List<Kontakt> suchen(String text) {
        return register.suchen(Normalisierer.name(text), SUCHE_ANZAHL);
    }

    @Transactional
    public Kontakt anlegen(KontaktAngaben angaben) {
        KontaktAngaben bereinigt = angaben.bereinigt();
        Kontakt kontakt = register.anlegen(bereinigt, bereinigt.nameNormalisiert(), Kontakt.QUELLE_MANUELL);
        LOG.infof("Kontakt %s angelegt", kontakt.id());
        return kontakt;
    }

    /** Optimistisches Locking: fremde oder unbekannte ID → 404, veraltete Version → 412 (tech.md, If-Match). */
    @Transactional
    public Kontakt aendern(UUID id, int version, KontaktAngaben aenderung) {
        Kontakt bisher = register.finden(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (bisher.version() != version) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        KontaktAngaben bereinigt = bisher.angaben().mit(aenderung).bereinigt();
        Kontakt kontakt = register.aendern(id, version, bereinigt, bereinigt.nameNormalisiert())
                .orElseThrow(() -> new FachFehler(Fehlercode.VERSION_KONFLIKT));
        LOG.infof("Kontakt %s geändert (Version %d)", kontakt.id(), kontakt.version());
        return kontakt;
    }
}
