package ch.urinet.archiv.ki.domain.chat;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verlauf für den Agenten: beginnt mit einer Frage, Rollen wechseln sich ab. */
class VerlaufEintragTest {

    private static VerlaufEintrag f(String t) {
        return new VerlaufEintrag(ChatRolle.BENUTZER, t);
    }

    private static VerlaufEintrag a(String t) {
        return new VerlaufEintrag(ChatRolle.ASSISTENT, t);
    }

    @Test
    void fuehrendeAntwortenEntfallen() {
        assertThat(VerlaufEintrag.normalisieren(List.of(a("A1"), f("F2"), a("A2")))).containsExactly(f("F2"), a("A2"));
    }

    @Test
    void gleicheRollenHintereinanderWerdenZusammengefasst() {
        assertThat(VerlaufEintrag.normalisieren(List.of(f("F3"), f("F4"), a("A4"))))
                .containsExactly(f("F3\n\nF4"), a("A4"));
    }

    @Test
    void sauberFolgeBleibtUnveraendert() {
        List<VerlaufEintrag> folge = List.of(f("F1"), a("A1"), f("F2"), a("A2"));
        assertThat(VerlaufEintrag.normalisieren(folge)).isEqualTo(folge);
        assertThat(VerlaufEintrag.normalisieren(List.of())).isEmpty();
    }
}
