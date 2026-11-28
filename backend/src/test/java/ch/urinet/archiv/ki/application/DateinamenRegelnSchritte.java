package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.konfiguration.application.DateinamenErkennung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Vorschlag;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class DateinamenRegelnSchritte {

    private Konfiguration konfiguration;
    private Optional<ch.urinet.archiv.konfiguration.application.DateinamenTreffer> treffer = Optional.empty();
    private Optional<Vorschlag> vorschlag = Optional.empty();

    @Angenommen("für den Import gelten geordnete synthetische Dateinamen-Regeln")
    public void regelnEinrichten() {
        Konfiguration.Art art = art();
        konfiguration = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(), Konfiguration.KiTexte.LEER, List.of(
                new Konfiguration.DateinamenRegel(1, "^anders-(?<nr>\\d{4}-\\d{3})$", art.code(),
                        Map.of("belegnummer", "nr")),
                new Konfiguration.DateinamenRegel(2,
                        "^syn-(?<nr>\\d{4}-\\d{3})_(?<partner>.+)\\.pdf$", art.code(),
                        Map.of("belegnummer", "nr", "partner", "partner"))));
        assertThat(konfiguration.pruefen()).isEmpty();
    }

    @Wenn("das Archiv den Dateinamen «{}» auswertet")
    public void dateinameAuswerten(String dateiname) {
        DateinamenErkennung erkennung = new DateinamenErkennung(() -> konfiguration, Duration.ofMillis(100));
        treffer = erkennung.erkennen(dateiname);
    }

    @Dann("übernimmt es Belegnummer «{word}» und Partner «{}» aus der ersten passenden Regel")
    public void ergebnisPruefen(String belegnummer, String partner) {
        assertThat(treffer).isPresent();
        assertThat(treffer.orElseThrow().regelNr()).isEqualTo(2);
        assertThat(treffer.orElseThrow().felder()).containsExactlyInAnyOrderEntriesOf(
                Map.of("belegnummer", belegnummer, "partner", partner));
        vorschlag = DateinamenAbgleich.anwenden(Optional.empty(), treffer, "Rechnung " + belegnummer);
        assertThat(vorschlag).isPresent();
        assertThat(vorschlag.orElseThrow().sicherheit(Feld.BELEGNUMMER)).isEqualTo(BigDecimal.ONE);
        assertThat(vorschlag.orElseThrow().sicherheit(Feld.PARTNER)).isEqualTo(BigDecimal.ONE);
        assertThat(vorschlag.orElseThrow().sicherheit(Feld.ART)).isEqualTo(BigDecimal.ONE);
        assertThat(vorschlag.orElseThrow().herkunft()).containsEntry("belegnummer", "DATEINAME")
                .containsEntry(Feld.PARTNER.schluessel(), "DATEINAME").containsEntry("art", "DATEINAME");
    }

    @Angenommen("eine Dateinamen-Regel erkennt die synthetische Belegnummer «{word}»")
    public void regelErkenntBelegnummer(String belegnummer) {
        Konfiguration.Art art = art();
        konfiguration = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(), Konfiguration.KiTexte.LEER,
                List.of(new Konfiguration.DateinamenRegel(1, "^(?<nr>\\d{4}-\\d{3})\\.pdf$", art.code(),
                        Map.of("belegnummer", "nr"))));
        treffer = new DateinamenErkennung(() -> konfiguration, Duration.ofMillis(100))
                .erkennen(belegnummer + ".pdf");
    }

    @Wenn("der erkannte Dateiname mit dem OCR-Volltext «{}» abgeglichen wird")
    public void ocrAbgleichen(String volltext) {
        vorschlag = DateinamenAbgleich.anwenden(Optional.empty(), treffer, volltext);
    }

    @Dann("ist die Belegnummer im Vorschlag als «bitte prüfen» markiert")
    public void bittePruefen() {
        assertThat(vorschlag).isPresent();
        assertThat(vorschlag.orElseThrow().bittePruefen()).containsExactly("belegnummer");
    }

    @Angenommen("die Konfiguration enthält ein ungültiges Dateinamen-Muster")
    public void ungueltigesMuster() {
        Konfiguration.Art art = art();
        konfiguration = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(), Konfiguration.KiTexte.LEER,
                List.of(new Konfiguration.DateinamenRegel(1, "(", art.code(),
                        Map.of("belegnummer", "nr"))));
    }

    @Dann("weist die Konfigurationsprüfung das Muster als ungültig zurück")
    public void musterAbweisen() {
        assertThat(konfiguration.pruefen()).extracting(Konfiguration.Befund::code)
                .contains("MUSTER_UNGUELTIG");
    }

    private static Konfiguration.Art art() {
        return new Konfiguration.Art("AUSGANGSRECHNUNG", "Ausgangsrechnung", null, List.of("finanz"),
                List.of("titel"), Konfiguration.OHNE_ZUSATZ, "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, true, List.of(), null, null, true,
                List.of(), null, 0);
    }
}
