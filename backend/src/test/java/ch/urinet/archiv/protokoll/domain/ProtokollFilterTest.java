package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Grenzen des Filters der Einsicht (Req 10.5). */
class ProtokollFilterTest {

    private static final Instant VON = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void leererBenutzerGiltAlsOhneFilterUndVersatzFolgtDerSeite() {
        ProtokollFilter filter = filter(Optional.of(VON), Optional.of(VON.plusSeconds(1)), Optional.of("  "), 3, 50);
        assertThat(filter.benutzer()).isEmpty();
        assertThat(filter.versatz()).isEqualTo(150);
    }

    @Test
    void ungueltigeAngabenWerdenAbgelehnt() {
        assertThatThrownBy(() -> filter(Optional.of(VON), Optional.of(VON), Optional.empty(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter(Optional.empty(), Optional.empty(), Optional.of("x".repeat(201)), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter(Optional.empty(), Optional.empty(), Optional.empty(), -1, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter(Optional.empty(), Optional.empty(), Optional.empty(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter(Optional.empty(), Optional.empty(), Optional.empty(), 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeitpunkteAusserhalbDerJahre1Bis9999WerdenAbgelehnt() {
        for (String zeit : new String[] {"+300000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z", "0000-12-31T23:59:59Z"}) {
            Optional<Instant> wert = Optional.of(Instant.parse(zeit));
            assertThatThrownBy(() -> filter(wert, Optional.empty(), Optional.empty(), 0, 50)).as(zeit)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> filter(Optional.empty(), wert, Optional.empty(), 0, 50)).as(zeit)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(filter(Optional.of(Instant.parse("0001-01-01T00:00:00Z")),
                Optional.of(Instant.parse("9999-12-31T23:59:59Z")), Optional.empty(), 0, 50).von()).isPresent();
    }

    private static ProtokollFilter filter(Optional<Instant> von, Optional<Instant> bis, Optional<String> benutzer,
                                          int seite, int groesse) {
        return new ProtokollFilter(von, bis, benutzer, Optional.empty(), Optional.empty(), seite, groesse);
    }
}
