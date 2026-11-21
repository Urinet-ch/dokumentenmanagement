package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class DiffDienst {
    public record Feld<T>(T alt, T neu) {
        public boolean geaendert() {
            return !Objects.equals(alt, neu);
        }
    }

    public record Diff(List<WissenZeilendiff.Aenderung> zeilen, Feld<String> titel, Feld<String> beschreibung,
                       Feld<List<String>> tags, Feld<List<Quelleneintrag>> quellen,
                       Feld<List<WissensMarkdown.Aussage>> widersprueche,
                       List<WissensMarkdown.Aussage> entfernteWidersprueche) {
        public Diff {
            zeilen = List.copyOf(zeilen);
            // Altdaten (vor 14.3 als JSON gespeichert) kennen die beiden Komponenten nicht: null-sichere Vorgaben.
            widersprueche = widersprueche == null ? new Feld<>(List.of(), List.of()) : widersprueche;
            entfernteWidersprueche = entfernteWidersprueche == null ? List.of() : List.copyOf(entfernteWidersprueche);
        }
    }

    private final WissenZeilendiff zeilendiff;

    public DiffDienst(WissenZeilendiff zeilendiff) {
        this.zeilendiff = zeilendiff;
    }

    public Diff vergleichen(Wissensseite basis, Wissensseite vorschlag) {
        Objects.requireNonNull(vorschlag, "vorschlag");
        return new Diff(zeilendiff.vergleichen(basis == null ? "" : basis.inhalt(), vorschlag.inhalt()),
                new Feld<>(basis == null ? null : basis.titel(), vorschlag.titel()),
                new Feld<>(basis == null ? null : basis.beschreibung(), vorschlag.beschreibung()),
                new Feld<>(basis == null ? List.of() : basis.tags(), vorschlag.tags()),
                new Feld<>(basis == null ? List.of() : basis.quellen(), vorschlag.quellen()),
                new Feld<>(WissensMarkdown.analysieren(basis == null ? "" : basis.inhalt(),
                        WissensMarkdown.MAX_EINGABE_ZEICHEN).widersprueche(),
                        WissensMarkdown.analysieren(vorschlag.inhalt(),
                                WissensMarkdown.MAX_EINGABE_ZEICHEN).widersprueche()),
                WissensMarkdown.entfernteWidersprueche(basis == null ? "" : basis.inhalt(), vorschlag.inhalt()));
    }
}
