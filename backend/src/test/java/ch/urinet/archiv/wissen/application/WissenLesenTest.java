package ch.urinet.archiv.wissen.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WissenLesenTest {

    @Test
    void diffTeiltGemeinsameZeilenUndErsetzungen() {
        WissenLesen.Diff diff = WissenLesen.Diff.aus(1, "Einleitung\nAlte Aussage\nAbschluss",
                2, "Einleitung\nNeue Aussage\nAbschluss");

        assertThat(diff.von()).isEqualTo(1);
        assertThat(diff.bis()).isEqualTo(2);
        assertThat(diff.vorher()).containsExactly("Einleitung");
        assertThat(diff.entfernt()).containsExactly("Alte Aussage");
        assertThat(diff.hinzugefuegt()).containsExactly("Neue Aussage");
        assertThat(diff.nachher()).containsExactly("Abschluss");
    }

    @Test
    void versionZeigtAusDatenNurEinfacheAnzeigefelder() {
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put("titel", "Titel");
        daten.put("tags", List.of("a", "b"));
        daten.put("vertraulichkeit_mindest", "NORMAL");
        daten.put("quellen", List.of(Map.of("quell_seite_id", "geheim")));
        daten.put("beschreibung", Map.of("verschachtelt", "geheim"));
        daten.put("pfad", null);
        daten.put("unbekannt", "geheim");
        WissenLesenRepository.WissensVersion version = new WissenLesenRepository.WissensVersion(2, daten, "Inhalt",
                "autor", "freigeber", Instant.EPOCH, null, null, null, null);

        assertThat(version.ohneInterneDaten().daten()).containsOnlyKeys("titel", "tags", "vertraulichkeit_mindest")
                .containsEntry("tags", List.of("a", "b"));
    }
}
