package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
import java.util.Set;

/**
 * Prüft eine Dokumentart über die Domain-Regeln hinaus (Req 9.5): Das Zusatz-Schema ist gültig, und jedes Pflichtfeld
 * {@code zusatz.<feld>} ist darin beschrieben. Gilt für die Pflege über die API und für den Seed beim Anlegen eines
 * Mandanten.
 */
@ApplicationScoped
public class DokumentartPruefung {

    private final ZusatzSchemaPruefer schemaPruefer;

    public DokumentartPruefung(ZusatzSchemaPruefer schemaPruefer) {
        this.schemaPruefer = schemaPruefer;
    }

    /** Wirft {@code FachFehler(ZUSATZ_UNGUELTIG)}, wenn Schema und Pflichtfelder nicht passen. */
    public void pruefen(Dokumentart art) {
        pflichtfelderPruefen(art, schemaPruefer.schemaPruefen(art.zusatzSchema()));
    }

    /** Wie {@link #pruefen}, für den Seed eines Mandanten mit der Seed-Frist ({@code archiv.dokumentart.seed-zeitgrenze}). */
    public void seedPruefen(Dokumentart art) {
        pflichtfelderPruefen(art, schemaPruefer.seedSchemaPruefen(art.zusatzSchema()));
    }

    private static void pflichtfelderPruefen(Dokumentart art, Set<String> schemaFelder) {
        Set<String> felder = new HashSet<>(schemaFelder);
        if (!felder.containsAll(art.zusatzPflichtfelder())) {
            throw new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
        }
    }
}
