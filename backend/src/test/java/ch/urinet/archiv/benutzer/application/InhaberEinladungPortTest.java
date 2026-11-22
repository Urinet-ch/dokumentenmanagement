package ch.urinet.archiv.benutzer.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InhaberEinladungPortTest {
    @Test
    void eingabeGibtKeinePersonendatenAus() {
        var eingabe = new InhaberEinladungPort.Eingabe("inhaber", "inhaber@example.test", "Vorname", "Nachname",
                "Betreiber");

        assertThat(eingabe.toString()).isEqualTo("Eingabe[geschützt]")
                .doesNotContain("inhaber", "example.test", "Vorname", "Nachname", "Betreiber");
    }
}
