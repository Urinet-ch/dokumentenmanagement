package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.adapter.out.diff.JavaWissenZeilendiff;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiffDienstTest {
    private final DiffDienst dienst = new DiffDienst(new JavaWissenZeilendiff());

    @Test
    void zeilenUndGeaenderteFelderSindGetrenntErkennbar() {
        Quelleneintrag quelle = new Quelleneintrag("q1", UUID.randomUUID(), 1, 1, "Probe", null);
        DiffDienst.Diff diff = dienst.vergleichen(
                seite("Alt", List.of("vorher"), List.of(), "Erste Zeile\nZweite Zeile"),
                seite("Neu", List.of("nachher"), List.of(quelle), "Erste Zeile\nGeänderte Zeile"));

        assertThat(diff.zeilen()).containsExactly(new WissenZeilendiff.Aenderung(WissenZeilendiff.Art.AENDERN,
                1, 1, List.of("Zweite Zeile"), List.of("Geänderte Zeile")));
        assertThat(diff.titel().geaendert()).isTrue();
        assertThat(diff.beschreibung().geaendert()).isFalse();
        assertThat(diff.tags().alt()).containsExactly("vorher");
        assertThat(diff.tags().neu()).containsExactly("nachher");
        assertThat(diff.quellen().neu()).containsExactly(quelle);
    }

    @Test
    void unterschiedlicheZeilenendenErgebenKeinenDiff() {
        DiffDienst.Diff diff = dienst.vergleichen(seite("Titel", List.of(), List.of(), "a\r\nb\rc"),
                seite("Titel", List.of(), List.of(), "a\nb\nc"));
        assertThat(diff.zeilen()).isEmpty();
    }

    @Test
    void neueSeiteUndLetzterZeilenumbruchSindNichtUnsichtbar() {
        DiffDienst.Diff neu = dienst.vergleichen(null, seite("Neu", List.of(), List.of(), "Inhalt"));
        assertThat(neu.zeilen()).containsExactly(new WissenZeilendiff.Aenderung(WissenZeilendiff.Art.EINFUEGEN,
                0, 0, List.of(), List.of("Inhalt")));
        DiffDienst.Diff umbruch = dienst.vergleichen(seite("Titel", List.of(), List.of(), "Inhalt"),
                seite("Titel", List.of(), List.of(), "Inhalt\n"));
        assertThat(umbruch.zeilen()).isNotEmpty();
    }

    private Wissensseite seite(String titel, List<String> tags, List<Quelleneintrag> quellen, String inhalt) {
        Instant zeit = Instant.parse("2026-01-01T00:00:00Z");
        return new Wissensseite(UUID.randomUUID(), "konzepte/probe", Seitentyp.KONZEPT, titel, "Beschreibung.",
                tags, Wissensseite.Status.AKTIV, null, quellen, Wissensseite.Vertraulichkeit.NORMAL,
                inhalt, 1, "human:probe", zeit, "human:probe", zeit, false, false, null);
    }
}
