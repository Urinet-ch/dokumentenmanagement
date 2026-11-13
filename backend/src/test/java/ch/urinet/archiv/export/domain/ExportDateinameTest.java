package ch.urinet.archiv.export.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dateinamen im Jahresexport (Spec 02, Req 7.4; design.md «Testing Strategy»: Unit «Dateinamen im Export»). */
class ExportDateinameTest {

    private static final UUID ID = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110");
    private static final LocalDate DATUM = LocalDate.of(2026, 3, 14);

    @Test
    void musterMitAllenTeilen() {
        assertThat(ExportDateiname.fuer(DATUM, "AUSGANGSRECHNUNG", "2026-003", ID, "Müller & Söhne AG", 0, "pdf"))
                .isEqualTo("2026-03-14_AUSGANGSRECHNUNG_2026-003_Mueller-Soehne-AG.pdf")
                .matches(ExportDateiname.MUSTER);
    }

    @Test
    void ohneBelegnummerDieIdOhnePartnerPlatzhalter() {
        assertThat(ExportDateiname.fuer(DATUM, "LOHNABRECHNUNG", null, ID, " ", 0, "pdf"))
                .isEqualTo("2026-03-14_LOHNABRECHNUNG_" + ID + "_ohne-partner.pdf");
        assertThat(ExportDateiname.fuer(DATUM, null, "€€", ID, null, 0, "pdf"))
                .isEqualTo("2026-03-14_ohne-art_" + ID + "_ohne-partner.pdf");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Zürcher Kantonalbank|Zuercher-Kantonalbank",
            "Société Générale|Societe-Generale",
            "Straße_und Weg|Strasse-und-Weg",
            "=HYPERLINK(\"x\")|HYPERLINK-x",
            "../../etc/passwd|etc-passwd",
            "ﬁrma ①|firma-1"})
    void nurAsciiOhneLeerzeichenUndTrenner(String partner, String erwartet) {
        String name = ExportDateiname.fuer(DATUM, "QUITTUNG", "Q1", ID, partner, 0, "pdf");
        assertThat(name).isEqualTo("2026-03-14_QUITTUNG_Q1_" + erwartet + ".pdf").matches(ExportDateiname.MUSTER);
        assertThat(name.chars().allMatch(c -> c > ' ' && c <= '~')).isTrue();
    }

    @Test
    void steuerUndFormatzeichenWerdenZuTrennern() {
        assertThat(ExportDateiname.fuer(DATUM, "QUITTUNG", "Q1", ID, "A\u202Eevil\u0000", 0, "pdf"))
                .isEqualTo("2026-03-14_QUITTUNG_Q1_A-evil.pdf");
        assertThat(ExportDateiname.fuer(DATUM, "QUITTUNG", "Q1", ID, "Ärzte\tÖl\nÜbel", 0, "pdf"))
                .isEqualTo("2026-03-14_QUITTUNG_Q1_Aerzte-Oel-Uebel.pdf");
    }

    @Test
    void langeTeileWerdenGekuerztUndEndungGeprueft() {
        String name = ExportDateiname.fuer(DATUM, "QUITTUNG", "x".repeat(200), ID, "y".repeat(200), 2, "JPG");
        assertThat(name).isEqualTo("2026-03-14_QUITTUNG_" + "x".repeat(60) + "_" + "y".repeat(60) + "-2.jpg");
        assertThat(ExportDateiname.fuer(DATUM, "QUITTUNG", "Q1", ID, "P", 0, "p d f")).endsWith(".bin");
    }

    @Test
    void vergabeMachtGleicheNamenEindeutig() {
        ExportDateiname.Vergabe vergabe = new ExportDateiname.Vergabe();
        UUID andere = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000000");
        String name = "2026-03-14_QUITTUNG_Q1_P.pdf";
        assertThat(vergabe.vergeben(name, ID)).isEqualTo(name);
        assertThat(vergabe.vergeben(name, andere)).isEqualTo("2026-03-14_QUITTUNG_Q1_P-a1b2c3d4.pdf");
        assertThat(vergabe.vergeben(name, andere)).isEqualTo("2026-03-14_QUITTUNG_Q1_P-" + andere + ".pdf");
        assertThat(vergabe.vergeben(name, andere)).isEqualTo("2026-03-14_QUITTUNG_Q1_P-" + andere + "-2.pdf");
    }
}
