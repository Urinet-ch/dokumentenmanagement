package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAnalyseLesenTest {
    @Test
    void kontextstufeBeruecksichtigtAlleWerkzeugeAuchOhneAngezeigteQuellen() {
        var daten = daten(true, "OK", new ChatAnalyseDaten.Werkzeug("dokumente_suchen", "INTERN"),
                new ChatAnalyseDaten.Werkzeug("wissen_index", "NORMAL"));
        assertThat(ChatAnalyseLesen.kontextstufe(daten)).isEqualTo("INTERN");
        assertThat(ChatAnalyseLesen.kontextstufe(daten(true, "OK"))).isEqualTo("NORMAL");
    }

    @Test
    void unvollstaendigeAltdatenVerboteneWerkzeugeUndUnbekannteStufenWerdenAbgelehnt() {
        for (var daten : List.of(
                daten(false, "OK"),
                daten(true, "FEHLER"),
                daten(true, "OK", new ChatAnalyseDaten.Werkzeug("kennzahl", "NORMAL")),
                daten(true, "OK", new ChatAnalyseDaten.Werkzeug("dokument_zeigen", null)),
                daten(true, "OK", new ChatAnalyseDaten.Werkzeug("dokument_zeigen", "PERSONAL")))) {
            assertThatThrownBy(() -> ChatAnalyseLesen.kontextstufe(daten))
                    .isInstanceOf(FachFehler.class)
                    .satisfies(e -> assertThat(((FachFehler) e).code()).isEqualTo(Fehlercode.ANALYSE_NICHT_MOEGLICH));
        }
    }

    @Test
    void nachrichtIdMussStrengDezimalSeinOhneVorzeichenOderNichtAsciiZiffern() {
        var lesen = new ChatAnalyseLesen(null);
        for (String id : new String[] {"+12", "-1", "0", "012", "\u0661\u0662", "", " 5", null, "99999999999999999999"}) {
            assertThatThrownBy(() -> lesen.lesen(id, "u1"))
                    .isInstanceOf(FachFehler.class)
                    .satisfies(e -> assertThat(((FachFehler) e).code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
    }

    private static ChatAnalyseDaten daten(boolean vollstaendig, String status, ChatAnalyseDaten.Werkzeug... werkzeuge) {
        return new ChatAnalyseDaten("Antwort", status, vollstaendig, List.of(werkzeuge), List.of());
    }
}
