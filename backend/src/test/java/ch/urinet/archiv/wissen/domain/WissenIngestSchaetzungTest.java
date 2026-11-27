package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WissenIngestSchaetzungTest {

    /** Startwerte aus design.md, «Konfiguration und Aufträge». */
    private static final WissenIngestGrenzen STARTWERTE =
            new WissenIngestGrenzen(30_000, 15, 60_000, 20_000, 12_000, 300_000, 1_000_000);

    private final WissenIngestGrenzen grenzen = new WissenIngestGrenzen(10, 4, 100, 50, 20, 300_000, 1_000_000);

    /**
     * Fester Wert der Formel aus design.md: 1'000 Zeichen Text + 60'000 Index + 20'000 ueberblick + 2 × 15 × 20'000 +
     * (1 Abschnitt + 15 Seiten + 2) × 12'000 Systemprompt = 897'000 Zeichen; / 3 × 1.3 = 388'700 Tokens.
     */
    @Test
    void schaetzungFolgtDerFormelAusDemDesign() {
        var schaetzung = WissenIngestSchaetzung.schaetzen("x".repeat(1_000) + "\f", 1, null, null, true,
                STARTWERTE, 12_000);

        assertThat(schaetzung.tokens()).isEqualTo(388_700);
        assertThat(schaetzung.aufrufe()).isEqualTo(18);
    }

    @Test
    void ohneNormalEntfaelltDerUeberblick() {
        var normal = WissenIngestSchaetzung.schaetzen("x".repeat(1_000) + "\f", 1, null, null, true, STARTWERTE, 0);
        var intern = WissenIngestSchaetzung.schaetzen("x".repeat(1_000) + "\f", 1, null, null, false, STARTWERTE, 0);

        assertThat(normal.tokens()).isEqualTo(295_100);
        assertThat(intern.tokens()).isEqualTo(286_434);
    }

    @Test
    void schaetzungBegrenztDenTextAufDenAngefordertenSeitenbereich() {
        var schaetzung = WissenIngestSchaetzung.schaetzen("A\fBBBBBBBBBBBB\fCCC\f", 3, 2, 3, false, grenzen, 20);

        assertThat(schaetzung.textZeichen()).isEqualTo(15);
        assertThat(schaetzung.abschnitte()).isEqualTo(3);
    }

    @Test
    void kurzeSeitenTeilenSichEinenAbschnitt() {
        var schaetzung = WissenIngestSchaetzung.schaetzen("AAA\fBBB\fCCC\fDDDDDDDDD\f", 4, null, null, false,
                grenzen, 20);

        assertThat(schaetzung.abschnitte()).isEqualTo(2);
    }

    @Test
    void wiederholungSchaetztNurDenAngefordertenAbschnitt() {
        var schaetzung = WissenIngestSchaetzung.abschnittSchaetzen("AAA\fBBB\fCCC\f", 3, null, null,
                1, grenzen.abschnittZeichen(), false, grenzen, 20);

        assertThat(schaetzung.textZeichen()).isEqualTo("[Seite 2]\nBBB".length());
        assertThat(schaetzung.abschnitte()).isEqualTo(1);
    }

    @Test
    void derSchlusstrennerVonPdftotextErgibtKeineZusaetzlicheSeite() {
        assertThat(WissenIngestSchaetzung.schaetzen("eine Seite\f", 1, null, null, true, grenzen, 20).textZeichen())
                .isEqualTo(10);
        assertThat(WissenIngestSchaetzung.schaetzen("Seite eins\fSeite zwei\f", 2, null, null, true, grenzen, 20)
                .textZeichen()).isEqualTo(20);
    }

    @Test
    void seitenzahlMussMitDenSeitentrennernUebereinstimmen() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WissenIngestSchaetzung.schaetzen("eine Seite\f", 2, null, null, true, grenzen, 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WissenIngestSchaetzung.schaetzen("ohne Trenner", 1, null, null, true, grenzen, 20));
    }

    @Test
    void seitenbereichMitLeeremVolltextWirdAbgelehnt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WissenIngestSchaetzung.schaetzen("Text\f\f\f", 3, 2, 3, true, grenzen, 20));
    }

    @Test
    void ueberlaufSaettigt() {
        var riesig = new WissenIngestGrenzen(1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1,
                300_000, 1_000_000);

        assertThat(WissenIngestSchaetzung.schaetzen("x\f", 1, null, null, true, riesig, Integer.MAX_VALUE).tokens())
                .isEqualTo(Long.MAX_VALUE);
    }
}
