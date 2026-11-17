package ch.urinet.archiv.konfiguration.domain;

import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KonfigurationTest {

    @Test
    void pruefenLiefertAlleDerzeitMoeglichenBefunde() {
        var art = new Dokumentart("RECHNUNG", "Rechnung", null, List.of("finanz"), List.of("titel"),
                Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.NORMAL, Aufbewahrungsregel.UNBEFRISTET, false, true);

        var konfiguration = new Konfiguration(0,
                new Konfiguration.Einstellungen("12-31", null, List.of()), List.of(art, art));

        assertThat(konfiguration.pruefen()).containsExactly(
                new Konfiguration.Befund("version", "VERSION_UNGUELTIG",
                        "Die Konfigurationsversion muss positiv sein."),
                new Konfiguration.Befund("dokumentarten.RECHNUNG", "DOKUMENTART_DOPPELT",
                        "Der Dokumentart-Code kommt mehrfach vor."));
    }

    @Test
    void kopiertListenUnveraenderlich() {
        var ibans = new java.util.ArrayList<>(List.of("TEST-IBAN-1"));
        var einstellungen = new Konfiguration.Einstellungen("12-31", null, ibans);
        ibans.add("TEST-IBAN-2");

        assertThat(einstellungen.ibans()).containsExactly("TEST-IBAN-1");
        assertThatThrownBy(() -> einstellungen.ibans().add("TEST-IBAN-2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
