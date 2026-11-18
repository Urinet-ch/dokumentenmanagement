package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WissenIngestAuszuegeTest {

    @Test
    void akzeptiertNurAuszuegeDieAufDerAngegebenenSeiteStehen() {
        assertThat(WissenIngestAuszuege.belegt("Die Frist endet\nam 30. April.", "Frist endet am 30. April.")).isTrue();
        assertThat(WissenIngestAuszuege.belegt("Die Frist endet am 30. April.", "Die Frist endet am 31. April."))
                .isFalse();
    }

    @Test
    void normalisiertUnicodeUndLeerraumAberKeineWortlaute() {
        assertThat(WissenIngestAuszuege.belegt("Cafe\u0301\nUrinet", "Café Urinet")).isTrue();
        assertThat(WissenIngestAuszuege.belegt("Kanton Uri", "Kanton Nidwalden")).isFalse();
    }

    @Test
    void lehntLeereUndZuLangeAuszuegeAb() {
        assertThat(WissenIngestAuszuege.belegt("Text", " ")).isFalse();
        assertThat(WissenIngestAuszuege.belegt("x".repeat(301), "x".repeat(301))).isFalse();
    }

    @Test
    void zerlegtSeitenMitMarkernInBegrenzteCodepunktAbschnitte() {
        List<WissenIngestSegmentierer.Abschnitt> abschnitte = WissenIngestSegmentierer.zerlegen(
                new Seitentext(List.of("a😀bc", "de")), 1, 2, 2);

        assertThat(abschnitte).extracting(WissenIngestSegmentierer.Abschnitt::nummer)
                .containsExactly(0, 1, 2);
        assertThat(abschnitte).extracting(WissenIngestSegmentierer.Abschnitt::seite)
                .containsExactly(1, 1, 2);
        assertThat(abschnitte).allSatisfy(abschnitt -> {
            String text = abschnitt.text().substring(abschnitt.text().indexOf('\n') + 1);
            assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(2);
        });
    }
}
