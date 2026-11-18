package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SichtregelSqlTest {

    private static final Sichtregel WEBSEITE = Sichtregel.fuerWebseite(
            new Sichtregel.Webseite(List.of("OEFFENTLICH"), null, null));

    @Test
    void regelDerWebseiteBrichtOhneFestenFilterAb() {
        assertThatThrownBy(() -> SichtregelSql.dokument(WEBSEITE, "d"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grundsichtOhneFestenFilterBleibtFuerDieWebseiteVerfuegbar() {
        assertThat(SichtregelSql.ohneFestenFilter(WEBSEITE, "d").bedingung()).contains("d.vertraulichkeit = 'NORMAL'");
    }
}
