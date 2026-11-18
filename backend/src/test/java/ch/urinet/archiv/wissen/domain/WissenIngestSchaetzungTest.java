package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WissenIngestSchaetzungTest {

    private final WissenIngestGrenzen grenzen = new WissenIngestGrenzen(10, 4, 100, 50, 20, 300_000, 1_000_000);

    @Test
    void schaetzungBegrenztDenTextAufDenAngefordertenSeitenbereich() {
        var schaetzung = WissenIngestSchaetzung.schaetzen("A\fBBBBBBBBBBBB\fCCC", 3, 2, 3, false, grenzen);

        assertThat(schaetzung.textZeichen()).isEqualTo(15);
        assertThat(schaetzung.seiten()).isEqualTo(2);
        assertThat(schaetzung.abschnitte()).isEqualTo(3);
    }

    @Test
    void seitenzahlMussMitDenFormFeedTrennernUebereinstimmen() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WissenIngestSchaetzung.schaetzen("eine Seite\f", 1, null, null, true, grenzen));
    }

    @Test
    void seitenbereichMitLeeremVolltextWirdAbgelehnt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WissenIngestSchaetzung.schaetzen("Text\f\f", 3, 2, 3, true, grenzen));
    }
}
