package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gleichzeitig laufende Fragen je Benutzer und gesamt (Req 5.5) und Länge der Frage. */
class ChatGrenzenTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Test
    void einePlatzJeBenutzerUndMandant() {
        ChatGrenzen grenzen = new ChatGrenzen(1, 4);
        Optional<ChatGrenzen.Platz> erster = grenzen.belegen(URINET, "a");
        assertThat(erster).isPresent();
        assertThat(grenzen.belegen(URINET, "a")).isEmpty();
        assertThat(grenzen.belegen(URINET, "b")).isPresent();
        assertThat(grenzen.belegen(VHF, "a")).as("derselbe Name im anderen Mandanten").isPresent();
        erster.get().freigeben();
        erster.get().freigeben();
        assertThat(grenzen.belegt()).isEqualTo(2);
        assertThat(grenzen.belegen(URINET, "a")).isPresent();
    }

    @Test
    void hoechstensGesamt() {
        ChatGrenzen grenzen = new ChatGrenzen(1, 2);
        assertThat(grenzen.belegen(URINET, "a")).isPresent();
        assertThat(grenzen.belegen(URINET, "b")).isPresent();
        assertThat(grenzen.belegen(URINET, "c")).isEmpty();
    }

    @Test
    void ungueltigeGrenzenBrechenAb() {
        assertThatThrownBy(() -> new ChatGrenzen(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatGrenzen(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frageNachTrimEinsBisZweitausendZeichen() {
        assertThat(ChatFragen.frage("  Wie viel?  ")).isEqualTo("Wie viel?");
        assertThat(ChatFragen.frage(" " + "ä".repeat(2000) + " ")).hasSize(2000);
        for (String falsch : new String[]{null, "", "   \n", "x".repeat(2001)}) {
            assertThatThrownBy(() -> ChatFragen.frage(falsch)).isInstanceOfSatisfying(FachFehler.class,
                    f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
    }
}
