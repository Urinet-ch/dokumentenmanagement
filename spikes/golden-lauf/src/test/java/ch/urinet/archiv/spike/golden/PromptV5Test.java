package ch.urinet.archiv.spike.golden;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prompt v5: fremde Arten vor eigenen, Anzeigetexte, Sozialversicherungs-Regel und Feldregeln (Fabian, 09.11.2026). */
class PromptV5Test {

    static final List<String> EIGENE = List.of("AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG");
    static final String MANDANT = "Muster Mandant GmbH";

    static String liste() throws IOException {
        return Dokumentarten.alsPromptListe(Dokumentarten.aus(Dokumentarten.designMd(Path.of(""))), EIGENE, MANDANT);
    }

    @Test
    void fremdeArtenVorEigenenInListeUndEnum() throws IOException {
        List<String> codes = liste().lines().map(z -> z.substring(2, z.indexOf(':'))).toList();
        assertThat(codes).hasSize(11).endsWith("AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG");
        assertThat(codes.getFirst()).isEqualTo("LIEFERANTENBELEG");
        assertThat(Prompt.artCodes()).isEqualTo(codes);
    }

    @Test
    void anzeigetexteMitMandantenname() throws IOException {
        assertThat(liste())
                .contains("- LIEFERANTENBELEG: Lieferantenbeleg – Rechnung, Facture, Fattura oder Gutschrift eines "
                        + "Lieferanten an " + MANDANT + " (Eingangsrechnung), ")
                .contains("- QUITTUNG: Quittung / Spesen – Kassenbon, Zahlungsbestätigung, Billett, Parkticket; bereits bezahlt, ")
                .contains("- AUSGANGSRECHNUNG: Ausgangsrechnung – nur wenn " + MANDANT + " im Briefkopf als Absender steht, ")
                .doesNotContain("%s");
    }

    @Test
    void gerenderterPromptMitBehoerdeSatzOhnePlatzhalter() throws IOException {
        String system = Prompt.system(MANDANT, liste());
        assertThat(system).contains("Mandant (eigene Firma): " + MANDANT).doesNotContain("{").doesNotContain("}")
                .contains("Sozialversicherungen (AHV-Ausgleichskassen, IV, EO, FAK; frz. caisse de compensation, ital. "
                        + "cassa di compensazione) sind BEHOERDE, auch bei Rückerstattungen und Gutschriften; UVG- und "
                        + "Krankentaggeld-Versicherer sind VERSICHERUNG.");
    }

    @Test
    void feldregelnOfferteUndAntrag() {
        assertThat(Prompt.SYSTEM)
                .contains("Bei Offerten das einmalige Total; wiederkehrende Beträge (pro Monat oder Jahr) zählen nicht.")
                .contains("«Antrag»")
                .doesNotContain("Geschäftsnummer");
    }
}
