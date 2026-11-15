package ch.urinet.archiv.ki.application.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DokumentZeigenTest {

    @Test
    void metadatenKommenVorhandenUndInFesterReihenfolgeInDenKopf() {
        assertThat(DokumentZeigen.metadaten(true, "1100745", "CHF 123.45", "10.03.2071", "20.03.2071",
                "Bergbahn Quellwald AG"))
                .isEqualTo("Metadaten (bestätigt)\nBelegnummer: 1100745\nBetrag: CHF 123.45\nDatum: 10.03.2071"
                        + "\nFälligkeit: 20.03.2071\nPartner: Bergbahn Quellwald AG");
    }

    @Test
    void unbestaetigterKopfNenntFehlendeFelderNicht() {
        assertThat(DokumentZeigen.metadaten(false, null, null, null, null, null))
                .isEqualTo("Metadaten (nicht bestätigt)");
    }
}
