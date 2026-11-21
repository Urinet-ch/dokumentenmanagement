package ch.urinet.archiv.benutzer.domain;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BenutzerFormregelnTest {
    @Test
    void erlaubtGrenzwerteUndUnicodeNamen() {
        assertThatCode(() -> BenutzerFormregeln.pruefeAnlage("a.-", "x".repeat(252) + "@x",
                "ä".repeat(60), "𐐀".repeat(60))).doesNotThrowAnyException();
        assertThatCode(() -> BenutzerFormregeln.pruefeAnlage("a".repeat(40), "a@b", "A", "B"))
                .doesNotThrowAnyException();
        assertThatCode(() -> BenutzerFormregeln.pruefeGrund("x".repeat(10))).doesNotThrowAnyException();
        assertThatCode(() -> BenutzerFormregeln.pruefeGrund("x".repeat(500))).doesNotThrowAnyException();
    }

    @Test
    void meldetFormfehlerOhneEingabewerte() {
        KonfigurationUngueltig fehler = catchThrowableOfType(
                () -> BenutzerFormregeln.pruefeAnlage("AB", "a@@b", "Test\n", "a".repeat(61)),
                KonfigurationUngueltig.class);
        assertThat(fehler).isNotNull();
        assertThat(fehler.befunde()).extracting(KonfigurationUngueltig.Befund::pfad)
                .containsExactly("benutzername", "email", "vorname", "nachname");
        assertThat(fehler.befunde().toString()).doesNotContain("AB", "a@@b", "Test");
        assertThatCode(() -> BenutzerFormregeln.pruefeAnlage("a".repeat(41), "a@b", "A", "B"))
                .isInstanceOf(KonfigurationUngueltig.class);
        assertThatCode(() -> BenutzerFormregeln.pruefeAnlage("abc", "x".repeat(254) + "@", "A", "B"))
                .isInstanceOf(KonfigurationUngueltig.class);
        assertThatCode(() -> BenutzerFormregeln.pruefeGrund("x".repeat(9)))
                .isInstanceOf(KonfigurationUngueltig.class);
        assertThatCode(() -> BenutzerFormregeln.pruefeGrund("x".repeat(501)))
                .isInstanceOf(KonfigurationUngueltig.class);
        assertThatCode(() -> BenutzerFormregeln.pruefeGrund("012345678\n"))
                .isInstanceOf(KonfigurationUngueltig.class);
    }
}
