package ch.urinet.archiv.wissen.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WissenLesenTest {

    @Test
    void diffTeiltGemeinsameZeilenUndErsetzungen() {
        WissenLesenRepository.WissensVersion alt = version(1, "Einleitung\nAlte Aussage\nAbschluss");
        WissenLesenRepository.WissensVersion neu = version(2, "Einleitung\nNeue Aussage\nAbschluss");

        WissenLesen.Diff diff = WissenLesen.Diff.aus(alt, neu);

        assertThat(diff.von()).isEqualTo(1);
        assertThat(diff.bis()).isEqualTo(2);
        assertThat(diff.vorher()).containsExactly("Einleitung");
        assertThat(diff.entfernt()).containsExactly("Alte Aussage");
        assertThat(diff.hinzugefuegt()).containsExactly("Neue Aussage");
        assertThat(diff.nachher()).containsExactly("Abschluss");
    }

    private static WissenLesenRepository.WissensVersion version(int nummer, String inhalt) {
        return new WissenLesenRepository.WissensVersion(nummer, Map.of(), inhalt, "autor", "freigeber",
                Instant.EPOCH, null, null, null, null);
    }
}
