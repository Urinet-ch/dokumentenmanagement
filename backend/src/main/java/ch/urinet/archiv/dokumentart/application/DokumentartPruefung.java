package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prüft eine Dokumentart über die Domain-Regeln hinaus (Req 9.5): Das Zusatz-Schema ist gültig, und jedes Pflichtfeld
 * {@code zusatz.<feld>} ist darin beschrieben. Gilt für die Pflege über die API und für den Seed beim Anlegen eines
 * Mandanten.
 */
@ApplicationScoped
public class DokumentartPruefung {

    private static final Pattern KI_FELDNAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
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
        fristfeldPruefen(art);
        pflichtfelderPruefen(art, schemaFelder);
        kiFelderPruefen(art, kiFelder, schemaFelder);
    }

    /** Wie {@link #pruefen}, für den Seed eines Mandanten mit der Seed-Frist ({@code archiv.dokumentart.seed-zeitgrenze}). */
    public void seedPruefen(Dokumentart art) {
        seedPruefen(art, List.of());
    }

    /** Prüft die KI-Felder des Seeds wie beim Ändern über die API. */
    public void seedPruefen(Dokumentart art, List<String> kiFelder) {
        Set<String> schemaFelder = schemaPruefer.seedSchemaPruefen(art.zusatzSchema());
        fristfeldPruefen(art);
        pflichtfelderPruefen(art, schemaFelder);
        kiFelderPruefen(art, kiFelder, schemaFelder);
    }

    private void kiFelderPruefen(Dokumentart art, List<String> kiFelder, Set<String> schemaFelder) {
        if (!schemaFelder.containsAll(kiFelder)
                || kiFelder.stream().anyMatch(feld -> !KI_FELDNAME.matcher(feld).matches())
                || kiFelder.contains("zugriff") || kiFelder.contains("veroeffentlicht")
                || !schemaPruefer.reservierteFelderGueltig(art.zusatzSchema())) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
    }

    private void fristfeldPruefen(Dokumentart art) {
        if (art.aufbewahrung().ab() == AufbewahrungAb.FELD
                && (!art.zusatzPflichtfelder().contains(art.aufbewahrung().feld())
                || !schemaPruefer.datumsfeldGueltig(art.zusatzSchema(), art.aufbewahrung().feld()))) {
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
