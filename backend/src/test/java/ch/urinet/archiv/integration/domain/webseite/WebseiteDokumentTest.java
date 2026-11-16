package ch.urinet.archiv.integration.domain.webseite;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Auszug eines Dokuments der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.2, Req 5.11). */
class WebseiteDokumentTest {

    private static String auszug(String text) {
        return new WebseiteDokument(UUID.randomUUID(), null, null, null, null, null, "oeffentlich", null, text)
                .auszug();
    }

    @Test
    void auszugIstHoechstens300ZeichenLang() {
        assertThat(auszug("a".repeat(299) + "b".repeat(50))).hasSize(300);
        assertThat(auszug("kurz")).isEqualTo("kurz");
        assertThat(auszug("  \n ")).isNull();
    }

    @Test
    void auszugTrenntKeinSurrogatpaar() {
        // das Emoji liegt auf den UTF-16-Stellen 299 und 300: ein Schnitt nach 300 Einheiten träfe sein halbes Paar
        String ergebnis = auszug("a".repeat(299) + "😀" + "b".repeat(20));
        assertThat(ergebnis).endsWith("\uD83D\uDE00");
        assertThat(ergebnis.codePointCount(0, ergebnis.length())).isEqualTo(300);
    }
}
