package ch.urinet.archiv.benutzer.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BenutzerStatusTest {

    private static final Map<BenutzerStatus, Set<BenutzerStatus>> UEBERGAENGE = Map.of(
            BenutzerStatus.EINGELADEN, EnumSet.of(BenutzerStatus.AKTIV, BenutzerStatus.DEAKTIVIERT),
            BenutzerStatus.AKTIV, EnumSet.of(BenutzerStatus.GESPERRT, BenutzerStatus.DEAKTIVIERT),
            BenutzerStatus.GESPERRT, EnumSet.of(BenutzerStatus.AKTIV, BenutzerStatus.DEAKTIVIERT),
            BenutzerStatus.DEAKTIVIERT, EnumSet.of(BenutzerStatus.EINGELADEN, BenutzerStatus.GELOESCHT),
            BenutzerStatus.GELOESCHT, EnumSet.noneOf(BenutzerStatus.class));

    @Test
    void prueftAlleStatuspaare() {
        for (BenutzerStatus von : BenutzerStatus.values()) {
            for (BenutzerStatus zu : BenutzerStatus.values()) {
                assertThat(von.darfWechselnZu(zu)).as("%s -> %s", von, zu)
                        .isEqualTo(UEBERGAENGE.get(von).contains(zu));
            }
        }
    }
}
