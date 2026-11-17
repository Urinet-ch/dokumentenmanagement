package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 03, Aufgabe 12.2: überprüft exportrelevante Sicherheits- und Aufbewahrungsregeln. */
public class MandantVollexportAbbauSchritte {

    private String csv;
    private Optional<MandantKommando.AbbauAufruf> abbauAufruf = Optional.empty();
    private LocalDate sicherungsstaendeBis;

    @Wenn("eine Vollexport-CSV-Zeile mit einer Formelzelle serialisiert wird")
    public void csvZeileSchreiben() {
        csv = MandantVollexportSchreiber.csvZeile("id", "=1+1");
    }

    @Dann("ist die Formelzelle in der CSV entschärft")
    public void csvIstEntschaerft() {
        assertThat(csv).contains("\"'=1+1\"");
    }

    @Wenn("der Betreiber einen Abbau-Aufruf ohne Ausführungsflag parst")
    public void abbauParsen() {
        abbauAufruf = MandantKommando.abbauLesen(List.of("abbauen", "vhf-gsk", "--nach-export",
                "bc9b7ac8-0ddd-4ab8-9850-27a7f598f0d7"));
    }

    @Dann("ist der Abbau-Aufruf ein Trockenlauf")
    public void trockenlauf() {
        assertThat(abbauAufruf).hasValueSatisfying(aufruf -> assertThat(aufruf.ausfuehren()).isFalse());
    }

    @Und("ein widersprüchlicher Trockenlauf wird abgelehnt")
    public void widerspruch() {
        assertThat(MandantKommando.abbauLesen(List.of("abbauen", "vhf-gsk", "--nach-export",
                "bc9b7ac8-0ddd-4ab8-9850-27a7f598f0d7", "--ausfuehren", "--dry-run"))).isEmpty();
    }

    @Wenn("die Sicherungsfrist für einen Mandanten berechnet wird")
    public void sicherungsfristBerechnen() {
        sicherungsstaendeBis = MandantAbbau.sicherungsstaendeBis(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Dann("nennt der Bericht eine Datenbankfrist und die unbefristete Datei-Aufbewahrung")
    public void sicherungsaufbewahrung() {
        assertThat(sicherungsstaendeBis).isEqualTo(LocalDate.of(2036, 1, 1));
        assertThat(MandantAbbau.DATEI_SICHERUNG_AUFBEWAHRUNG)
                .isEqualTo("unbefristet, bis die Spec zur Löschfunktion (Spec 01, 9.9) einen Weg regelt");
    }
}
