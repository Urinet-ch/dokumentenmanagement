package ch.urinet.archiv.verarbeitung.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schlüssel eines Befunds nur aus IDs und Status (Req 16.7), passend zum Check der Tabelle {@code alarm}. */
class BefundTest {

    @Test
    void schluesselAusQuelleGegenstandUndStatus() {
        assertThat(new Befund(Befund.Quelle.MANIFEST, "2026-11-08", "FEHLT").schluessel())
                .isEqualTo("manifest:2026-11-08:FEHLT")
                .matches("^[a-z]+:[A-Za-z0-9-]+:[A-Z_]+$");
    }

    @Test
    void lehntTextAusserhalbVonIdsUndStatusAb() {
        assertThatThrownBy(() -> new Befund(Befund.Quelle.DOKUMENT, "Rechnung Muster", "FEHLER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Befund(Befund.Quelle.DOKUMENT, "abc", "fehler: Zeitüberschreitung"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
