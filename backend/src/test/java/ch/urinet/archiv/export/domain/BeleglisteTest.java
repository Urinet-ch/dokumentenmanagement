package ch.urinet.archiv.export.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code belegliste.csv} und {@code pruefsummen.sha256} (Spec 02, Req 7.1; Spec 01, design.md: Formel-Injection). */
class BeleglisteTest {

    private static final UUID ID = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110");
    private static final String SHA = "a".repeat(64);

    @Test
    void kopfMitSemikolonUndCrlf() {
        assertThat(new String(Belegliste.kopf(), StandardCharsets.UTF_8))
                .isEqualTo("ID;Art;Datum;Partner;Belegnummer;Richtung;Betrag;Währung;SHA-256;Dateiname\r\n");
        assertThat(Belegliste.BOM).containsExactly(0xEF, 0xBB, 0xBF);
    }

    @Test
    void zeileMitZahlenUndLeerenFeldern() {
        String zeile = new String(Belegliste.zeile(new Belegliste.Zeile(ID, "QUITTUNG", LocalDate.of(2026, 1, 2),
                null, null, "AUSGABE", new BigDecimal("-12.50"), "CHF", SHA, "belege/x.pdf")), StandardCharsets.UTF_8);
        assertThat(zeile).isEqualTo(ID + ";QUITTUNG;2026-01-02;;;AUSGABE;-12.50;CHF;" + SHA + ";belege/x.pdf\r\n");
    }

    @Test
    void textfelderGegenFormelnUndMitAnfuehrungszeichen() {
        assertThat(Belegliste.text("=SUMME(A1)")).isEqualTo("'=SUMME(A1)");
        assertThat(Belegliste.text("+41 79")).isEqualTo("'+41 79");
        assertThat(Belegliste.text("-x")).isEqualTo("'-x");
        assertThat(Belegliste.text("@a")).isEqualTo("'@a");
        assertThat(Belegliste.text("\tx")).isEqualTo("'\tx");
        assertThat(Belegliste.text("\rx")).isEqualTo("\"'\rx\"");
        assertThat(Belegliste.text("A; B")).isEqualTo("\"A; B\"");
        assertThat(Belegliste.text("Sagt \"ja\"")).isEqualTo("\"Sagt \"\"ja\"\"\"");
        assertThat(Belegliste.text("Zeile\nzwei")).isEqualTo("\"Zeile\nzwei\"");
        assertThat(Belegliste.text("Müller AG")).isEqualTo("Müller AG");
    }

    @Test
    void pruefsummenImFormatVonSha256sum() {
        assertThat(new String(Pruefsummen.zeile(SHA, "belege/x.pdf"), StandardCharsets.US_ASCII))
                .isEqualTo(SHA + "  belege/x.pdf\n");
        assertThatThrownBy(() -> Pruefsummen.zeile("abc", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pruefsummen.zeile(SHA, "a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pruefsummen.zeile(SHA, "ä")).isInstanceOf(IllegalArgumentException.class);
    }
}
