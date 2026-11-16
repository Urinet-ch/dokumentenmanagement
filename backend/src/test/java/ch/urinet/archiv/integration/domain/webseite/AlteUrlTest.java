package ch.urinet.archiv.integration.domain.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Normalform der alten URLs für Zuordnung und Umleitung (Spec 03, Aufgabe 11.2, Req 4.3, 5.10). */
class AlteUrlTest {

    @Test
    void nurDerPfadZaehlt() {
        assertThat(AlteUrl.normalisieren("/alt/heft.pdf")).isEqualTo("/alt/heft.pdf");
        assertThat(AlteUrl.normalisieren("https://alt.example/alt/heft.pdf")).isEqualTo("/alt/heft.pdf");
        assertThat(AlteUrl.normalisieren("http://alt.example:8080/alt/heft.pdf?x=1&y=2#seite=3"))
                .isEqualTo("/alt/heft.pdf");
        assertThat(AlteUrl.normalisieren("/alt/heft.pdf?x=1")).isEqualTo("/alt/heft.pdf");
        assertThat(AlteUrl.normalisieren("https://alt.example")).isEqualTo("/");
        assertThat(AlteUrl.normalisieren("https://alt.example?x=1")).isEqualTo("/");
    }

    @Test
    void schraegstricheUndProzentKodierungWerdenNormalisiert() {
        assertThat(AlteUrl.normalisieren("/alt//heft/")).isEqualTo("/alt/heft");
        assertThat(AlteUrl.normalisieren("/")).isEqualTo("/");
        assertThat(AlteUrl.normalisieren("/alt/zweite%20seite")).isEqualTo("/alt/zweite seite");
        assertThat(AlteUrl.normalisieren("/alt/zweite seite")).isEqualTo("/alt/zweite seite");
        assertThat(AlteUrl.normalisieren("/alt/heft%2D1.pdf")).isEqualTo("/alt/heft-1.pdf");
        assertThat(AlteUrl.normalisieren("/alt/gr%C3%BCn")).isEqualTo("/alt/grün");
    }

    @Test
    void unicodeIstNfcUndGrossKleinbleibt() {
        assertThat(AlteUrl.normalisieren("/alt/grün")).isEqualTo("/alt/grün");
        assertThat(AlteUrl.normalisieren("/ALT/Heft.PDF")).isEqualTo("/ALT/Heft.PDF");
    }

    @Test
    void ungueltigeUrlsSindOhneErgebnis() {
        for (String falsch : new String[] {null, "", "   ", "kein-pfad", "alt/heft", "ftp:alt", "/alt/%", "/alt/%4",
                "/alt/%zz", "/alt/%FF", "/alt/%00", "/alt/\u0000", "/alt/\n", "/" + "a".repeat(2000)}) {
            assertThat(AlteUrl.versuchen(falsch)).as("«%s»", falsch).isEqualTo(Optional.empty());
        }
    }

    @Test
    void ungueltigeEingabeIst400OhneWert() {
        assertThatThrownBy(() -> AlteUrl.normalisieren("geheim-pfad")).isInstanceOfSatisfying(FachFehler.class,
                e -> assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG))
                .hasMessageNotContaining("geheim-pfad");
    }
}
