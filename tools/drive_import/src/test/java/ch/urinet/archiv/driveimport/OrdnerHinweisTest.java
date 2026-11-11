package ch.urinet.archiv.driveimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OrdnerHinweisTest {

    @TempDir
    Path tmp;

    @Test
    void kundenRechnungenGebenPartnerUndArt() {
        Path wurzel = tmp.resolve("UriNet");
        String hinweis = OrdnerHinweis.von(wurzel, wurzel.resolve("kunden/FC Muster/Rechnungen/2026/r.pdf"));
        assertThat(hinweis).isEqualTo("Drive-Import; Ordner: UriNet/kunden/FC Muster/Rechnungen/2026; "
                + "Partner: FC Muster; Art: AUSGANGSRECHNUNG");
    }

    @Test
    void offertenUndBuchhaltung() {
        Path wurzel = tmp.resolve("UriNet");
        assertThat(OrdnerHinweis.von(wurzel, wurzel.resolve("Kunden/Verein X/Offerten/o.pdf")))
                .endsWith("; Partner: Verein X; Art: OFFERTE");
        assertThat(OrdnerHinweis.von(wurzel, wurzel.resolve("Buchhaltung 2026/Bank/a.pdf")))
                .isEqualTo("Drive-Import; Ordner: UriNet/Buchhaltung 2026/Bank; Bereich: Buchhaltung; Jahr: 2026");
        assertThat(OrdnerHinweis.von(wurzel, wurzel.resolve("a.pdf"))).isEqualTo("Drive-Import; Ordner: UriNet");
    }

    @Test
    void rechnungenAusserhalbVonKundenSindKeineAusgangsrechnungen() {
        Path wurzel = tmp.resolve("UriNet");
        assertThat(OrdnerHinweis.von(wurzel, wurzel.resolve("Lieferanten/Rechnungen/a.pdf"))).doesNotContain("Art:");
    }

    @Test
    void haeltDieGrenzenDesArchivsEin() {
        Path wurzel = tmp.resolve("UriNet");
        Path tief = wurzel.resolve("Kunden/K\u0001X");
        for (int i = 0; i < 120; i++) {
            tief = tief.resolve("Ordner" + i);
        }
        String hinweis = OrdnerHinweis.von(wurzel, tief.resolve("a.pdf"));
        assertThat(hinweis.codePointCount(0, hinweis.length())).isLessThanOrEqualTo(OrdnerHinweis.MAX_ZEICHEN);
        assertThat(hinweis.codePoints()).noneMatch(Character::isISOControl);
        assertThat(hinweis).contains("Ordner: …").endsWith("Ordner119; Partner: K X");
    }

    @Test
    void normalisiertNfd() {
        Path wurzel = tmp.resolve("UriNet");
        assertThat(OrdnerHinweis.von(wurzel, wurzel.resolve("Bu\u0308ro/a.pdf"))).contains("Büro");
    }
}
