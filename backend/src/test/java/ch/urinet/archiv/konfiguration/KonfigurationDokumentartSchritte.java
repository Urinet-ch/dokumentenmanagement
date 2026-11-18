package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class KonfigurationDokumentartSchritte {

    private List<Konfiguration.Befund> befunde;

    @Wenn("eine Test-Konfiguration die Art «WISSENSQUELLE» mit geänderten festen Eigenschaften prüft")
    public void wissensquellePruefen() {
        var wissensquelle = new Konfiguration.Art("WISSENSQUELLE", "Test-Wissensquelle", null, List.of("frei"),
                List.of("titel", "datum"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, false, List.of(), null, null, true,
                List.of(), null, 0);
        var einstellungen = new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of()));
        befunde = new Konfiguration(1, einstellungen, List.of(wissensquelle)).pruefen();
    }

    @Dann("enthält die Test-Konfiguration den Befund «{word}»")
    public void befund(String code) {
        assertThat(befunde).extracting(Konfiguration.Befund::code).contains(code);
    }
}
