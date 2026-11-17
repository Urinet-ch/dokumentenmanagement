package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
import java.util.List;
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
        pruefen(art, List.of());
    }

    /** Prüft zusätzlich die KI-Felder und reservierten Felddefinitionen (Spec 05, Req 2.7). */
    public void pruefen(Dokumentart art, List<String> kiFelder) {
        Set<String> schemaFelder = schemaPruefer.schemaPruefen(art.zusatzSchema());
        pflichtfelderPruefen(art, schemaFelder);
        if (!schemaFelder.containsAll(kiFelder)
                || kiFelder.contains("zugriff") || kiFelder.contains("veroeffentlicht")
                || !schemaPruefer.reservierteFelderGueltig(art.zusatzSchema())) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
    }

    /** Wie {@link #pruefen}, für den Seed eines Mandanten mit der Seed-Frist ({@code archiv.dokumentart.seed-zeitgrenze}). */
    public void seedPruefen(Dokumentart art) {
        Set<String> schemaFelder = schemaPruefer.seedSchemaPruefen(art.zusatzSchema());
        pflichtfelderPruefen(art, schemaFelder);
        if (!schemaPruefer.reservierteFelderGueltig(art.zusatzSchema())) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
    }

    private static void pflichtfelderPruefen(Dokumentart art, Set<String> schemaFelder) {
        Set<String> felder = new HashSet<>(schemaFelder);
        if (!felder.containsAll(art.zusatzPflichtfelder())) {
            throw new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
        }
    }
}
