package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Wissensseite;
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
                       Feld<List<String>> tags, Feld<List<Quelleneintrag>> quellen) {
        public Diff {
            zeilen = List.copyOf(zeilen);
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
                new Feld<>(basis == null ? List.of() : basis.quellen(), vorschlag.quellen()));
    }
}
