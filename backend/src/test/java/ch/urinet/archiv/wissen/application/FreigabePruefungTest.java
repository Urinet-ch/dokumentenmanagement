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
import java.util.Optional;
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

    @Test
    void vorhandeneUndManuelleMindeststufeBleibenErhalten() {
        WissensGraph graph = new WissensGraph(Map.of(quelle, Set.of()), Map.of(quelle, Set.of()), Map.of(),
                Map.of(quelle, WissensStufe.NORMAL));
        Wissensseite seite = seite("konzepte/probe", Seitentyp.KONZEPT);

        assertThat(pruefung.pruefen(seite, graph, WissensStufe.INTERN, WissensStufe.NORMAL, null,
                WissensPruefung.Kontext.leer()).mindeststufe()).isEqualTo(WissensStufe.INTERN);
        assertThat(pruefung.pruefen(seite, graph, WissensStufe.NORMAL, WissensStufe.NORMAL, "INTERN",
                WissensPruefung.Kontext.leer()).mindeststufe()).isEqualTo(WissensStufe.INTERN);
        assertThat(pruefung.pruefen(seite, graph, WissensStufe.NORMAL, WissensStufe.INTERN, null,
                WissensPruefung.Kontext.leer()).mindeststufe()).isEqualTo(WissensStufe.INTERN);
    }

    @Test
    void inzwischenPersonelleQuelleWirdBeiPruefungAbgelehnt() {
        UUID dokument = UUID.randomUUID();
        Wissensseite seite = new Wissensseite(null, "konzepte/probe", Seitentyp.KONZEPT, "Probe", "Beschreibung.",
                List.of(), Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag("q1", dokument, 1, null, "Probe", null)),
                Wissensseite.Vertraulichkeit.NORMAL, "Aussage.[^q1]", 1, null, null, null, null, false, false, null);
        WissensGraph graph = new WissensGraph(Map.of(), Map.of(), Map.of(dokument, WissensStufe.NORMAL), Map.of());
        WissensPruefung.Kontext kontext = new WissensPruefung.Kontext(20000, null, Set.of(),
                Map.of(dokument, new WissensPruefung.DokumentQuelle(true, true, true, 1, null, Optional.empty())),
                Set.of(), Set.of(), Set.of(), Set.of(), false, false, false, null, null);

        assertThat(pruefung.pruefen(seite, graph, WissensStufe.NORMAL, WissensStufe.NORMAL, null, kontext)
                .pruefung().befunde()).extracting(WissensPruefung.Befund::code)
                .contains(WissensPruefung.Code.QUELLE_PERSONAL);
    }

    @Test
    void vertraulichkeitDerVorgeschlagenenFassungWirdNichtUebernommen() {
        WissensGraph graph = new WissensGraph(Map.of(quelle, Set.of()), Map.of(quelle, Set.of()), Map.of(),
                Map.of(quelle, WissensStufe.NORMAL));
        Wissensseite vorschlag = new Wissensseite(null, "ueberblick", Seitentyp.SYNTHESE, "Probe", "Beschreibung.",
                List.of(), Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag("q1", null, null, null, null, quelle)),
                Wissensseite.Vertraulichkeit.INTERN, "Aussage.[^q1]", 1, null, null, null, null, false, false, null);

        FreigabePruefung.Ergebnis ergebnis = pruefung.pruefen(vorschlag, graph, WissensStufe.NORMAL,
                WissensStufe.NORMAL, null, WissensPruefung.Kontext.leer());
        assertThat(ergebnis.mindeststufe()).isEqualTo(WissensStufe.NORMAL);
        assertThat(ergebnis.pruefung().befunde()).extracting(WissensPruefung.Befund::code)
                .doesNotContain(WissensPruefung.Code.STUFE_UEBERBLICK);
    }

    private Wissensseite seite(String pfad, Seitentyp typ) {
        return new Wissensseite(null, pfad, typ, "Probe", "Beschreibung.", List.of(),
                Wissensseite.Status.AKTIV, null, List.of(new Quelleneintrag("q1", null, null, null, null, quelle)),
                Wissensseite.Vertraulichkeit.NORMAL, "Aussage.[^q1]", 1, null, null, null, null, false, false, null);
    }
}
