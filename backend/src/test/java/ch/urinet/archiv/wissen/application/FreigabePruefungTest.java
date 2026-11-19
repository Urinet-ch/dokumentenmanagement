package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreigabePruefungTest {
    private final FreigabePruefung pruefung = new FreigabePruefung();
    private final UUID quelle = UUID.randomUUID();

    @Test
    void mindeststufeZitierterSeiteKannNichtUnterschrittenWerden() {
        WissensGraph graph = new WissensGraph(Map.of(quelle, Set.of()), Map.of(quelle, Set.of()), Map.of(),
                Map.of(quelle, WissensStufe.INTERN));
        Wissensseite seite = seite("konzepte/probe", Seitentyp.KONZEPT);

        assertThat(pruefung.pruefen(seite, graph, WissensStufe.NORMAL, WissensStufe.NORMAL, null,
                WissensPruefung.Kontext.leer()).mindeststufe()).isEqualTo(WissensStufe.INTERN);
        assertThatThrownBy(() -> pruefung.pruefen(seite, graph, WissensStufe.NORMAL, WissensStufe.NORMAL, "NORMAL",
                WissensPruefung.Kontext.leer())).isInstanceOfSatisfying(FachFehler.class,
                fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.VERTRAULICHKEIT_ZU_TIEF));
        assertThatThrownBy(() -> pruefung.pruefen(seite, graph, WissensStufe.NORMAL, WissensStufe.NORMAL, "PERSONAL",
                WissensPruefung.Kontext.leer())).isInstanceOfSatisfying(FachFehler.class,
                fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.VERTRAULICHKEIT_UNGUELTIG));
    }

    @Test
    void ueberblickMitInternerZitatquelleErhaeltBefund() {
        WissensGraph graph = new WissensGraph(Map.of(quelle, Set.of()), Map.of(quelle, Set.of()), Map.of(),
                Map.of(quelle, WissensStufe.INTERN));
        FreigabePruefung.Ergebnis ergebnis = pruefung.pruefen(seite("ueberblick", Seitentyp.SYNTHESE), graph,
                WissensStufe.NORMAL, WissensStufe.NORMAL, null, WissensPruefung.Kontext.leer());

        assertThat(ergebnis.pruefung().befunde()).extracting(WissensPruefung.Befund::code)
                .contains(WissensPruefung.Code.STUFE_UEBERBLICK);
    }

    private Wissensseite seite(String pfad, Seitentyp typ) {
        return new Wissensseite(null, pfad, typ, "Probe", "Beschreibung.", List.of(),
                Wissensseite.Status.AKTIV, null, List.of(new Quelleneintrag("q1", null, null, null, null, quelle)),
                Wissensseite.Vertraulichkeit.NORMAL, "Aussage.[^q1]", 1, null, null, null, null, false, false, null);
    }
}
