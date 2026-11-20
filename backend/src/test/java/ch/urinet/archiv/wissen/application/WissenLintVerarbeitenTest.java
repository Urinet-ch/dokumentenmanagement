package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.application.WissenLintRepository.AuftragSeite;
import ch.urinet.archiv.wissen.application.WissenLintRepository.Bezug;
import ch.urinet.archiv.wissen.application.WissenLintRepository.Fortschritt;
import ch.urinet.archiv.wissen.domain.WissensLint.PaarId;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenLintVerarbeitenTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void paareMitUnausgefuehrtemSeitenauftragGeltenNichtAlsGeprueft() {
        var ausgefuehrt = new AuftragSeite(A, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var offen = new AuftragSeite(C, 1, 1, List.of(new Bezug(A, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var stand = new Fortschritt(List.of(), List.of(), 2, List.of(ausgefuehrt, offen), 1,
                List.of(new PaarId(A, B), new PaarId(A, C)), null, null, false, List.of(), List.of());

        assertThat(WissenLintVerarbeiten.ohneUnausgefuehrte(stand)).containsExactly(new PaarId(A, B));
    }

    @Test
    void nachVollstaendigemLaufBleibenAllePaareGeprueft() {
        var seite = new AuftragSeite(A, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var stand = new Fortschritt(List.of(), List.of(), 1, List.of(seite), 1,
                List.of(new PaarId(A, B)), null, null, false, List.of(), List.of());

        assertThat(WissenLintVerarbeiten.ohneUnausgefuehrte(stand)).containsExactly(new PaarId(A, B));
    }
}
