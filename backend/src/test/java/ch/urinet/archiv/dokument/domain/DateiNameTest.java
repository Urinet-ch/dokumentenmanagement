package ch.urinet.archiv.dokument.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Dateiname im Upload-Ergebnis und in {@code dokument.dateiname} (design.md «Umsetzung 2.4», «Dateiname»). */
class DateiNameTest {

    @Test
    void pfadanteileFallenWeg() {
        assertThat(DateiName.aus("../../etc/passwd")).isEqualTo(new DateiName("passwd", true, false));
        assertThat(DateiName.aus("C:\\Belege\\scan.pdf")).isEqualTo(new DateiName("scan.pdf", true, false));
    }

    @Test
    void leerOderPunkteWerdenZumErsatz() {
        assertThat(DateiName.aus(null).anzeige()).isEqualTo(DateiName.ERSATZ);
        assertThat(DateiName.aus("  ").anzeige()).isEqualTo(DateiName.ERSATZ);
        assertThat(DateiName.aus("ordner/..").anzeige()).isEqualTo(DateiName.ERSATZ);
        assertThat(DateiName.aus("ordner/").anzeige()).isEqualTo(DateiName.ERSATZ);
    }

    @Test
    void wirdNachNfcNormalisiert() {
        assertThat(DateiName.aus("Rechnung Zu\u0308rich.pdf").anzeige()).isEqualTo("Rechnung Zürich.pdf");
    }

    @Test
    void steuerUndFormatzeichenMachenDenNamenUngueltig() {
        DateiName mitZeilenumbruch = DateiName.aus("scan\n.pdf");
        assertThat(mitZeilenumbruch.gueltig()).isFalse();
        assertThat(mitZeilenumbruch.anzeige()).isEqualTo("scan.pdf");
        assertThat(DateiName.aus("rechnung\u202Efdp.exe").gueltig()).isFalse();
        assertThat(DateiName.aus("halb\uD800.pdf").gueltig()).isFalse();
    }

    @Test
    void zeilenUndAbsatztrennerMachenDenNamenUngueltig() {
        assertThat(DateiName.aus("scan\u2028.pdf").gueltig()).isFalse();
        assertThat(DateiName.aus("scan\u2029.pdf").gueltig()).isFalse();
    }

    @Test
    void privateUndNichtVergebeneZeichenBleibenGueltig() {
        assertThat(DateiName.aus("scan\uE000.pdf").speicherbar()).contains("scan\uE000.pdf");
        assertThat(DateiName.aus("scan\u0378.pdf").speicherbar()).contains("scan\u0378.pdf");
    }

    @Test
    void gespeichertWirdNurEinGueltigerNameOhneErsatz() {
        assertThat(DateiName.aus("ordner/Scan_Rechnung-2026.pdf").speicherbar()).contains("Scan_Rechnung-2026.pdf");
        assertThat(DateiName.aus(null).speicherbar()).isEmpty();
        assertThat(DateiName.aus("").speicherbar()).isEmpty();
        assertThat(DateiName.aus("ordner/..").speicherbar()).isEmpty();
        assertThat(DateiName.aus("rechnung\u202Efdp.exe").speicherbar()).isEmpty();
    }

    @Test
    void eineEchteDateiNamensDateiIstKeinErsatz() {
        DateiName name = DateiName.aus("datei");
        assertThat(name.ersatz()).isFalse();
        assertThat(name.speicherbar()).isEqualTo(Optional.of("datei"));
    }

    @Test
    void emojiMitVerbinderBleibtGueltig() {
        assertThat(DateiName.aus("Familie \uD83D\uDC69\u200D\uD83D\uDC67.jpg").gueltig()).isTrue();
    }

    @Test
    void wirdAufHoechstens255ZeichenGekuerzt() {
        DateiName lang = DateiName.aus("ä".repeat(300) + ".pdf");
        assertThat(lang.anzeige().codePointCount(0, lang.anzeige().length())).isEqualTo(DateiName.MAX_ZEICHEN);
        assertThat(lang.gueltig()).isTrue();
    }
}
