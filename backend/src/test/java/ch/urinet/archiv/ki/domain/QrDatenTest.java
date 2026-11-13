package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Swiss QR Code lesen (Req 7.11): Betrag nur im Format des Swiss Payments Code, nie mit Exponent. */
class QrDatenTest {

    /** Synthetischer Payload nach Swiss Payments Code 2.0 (31 Zeilen) mit dem gegebenen Betrag in Zeile 18. */
    static String payload(String betrag) {
        List<String> z = new ArrayList<>(List.of("SPC", "0200", "1", "CH4431999123000889012", "S", "Lieferant GmbH",
                "Teststrasse", "1", "6000", "Luzern", "CH", "", "", "", "", "", "", "", betrag, "CHF", "", "", "", "",
                "", "", "", "NON", "", "", "EPD"));
        return String.join("\n", z);
    }

    @Test
    void gueltigeBetraege() {
        assertThat(QrDaten.parse(payload("1234.50")).orElseThrow().betrag()).isEqualByComparingTo("1234.50");
        assertThat(QrDaten.parse(payload("999999999.99")).orElseThrow().betrag()).isEqualByComparingTo("999999999.99");
        assertThat(QrDaten.parse(payload("80")).orElseThrow().betrag()).isEqualByComparingTo("80");
        QrDaten ohne = QrDaten.parse(payload("")).orElseThrow();
        assertThat(ohne.betrag()).isNull();
        assertThat(ohne.betragUngueltig()).isFalse();
    }

    @Test
    void betragAusserhalbDesFormatsBleibtLeerUndDerRestGilt() {
        for (String ungueltig : List.of("1E300000000", "1e999999999", "-5.00", "1000000000.00", "1.234", "12,50",
                "abc")) {
            QrDaten q = QrDaten.parse(payload(ungueltig)).orElseThrow();
            assertThat(q.betrag()).as(ungueltig).isNull();
            assertThat(q.betragUngueltig()).as(ungueltig).isTrue();
            assertThat(q.iban()).isEqualTo("CH4431999123000889012");
            assertThat(q.zahlungsempfaenger()).isEqualTo("Lieferant GmbH");
        }
    }

    @Test
    void nurSpcMitMindestens31Zeilen() {
        assertThat(QrDaten.parse(payload("1.00").replace("SPC", "XYZ"))).isEmpty();
        assertThat(QrDaten.parse("SPC\n0200")).isEmpty();
        assertThat(QrDaten.parse(null)).isEmpty();
    }
}
