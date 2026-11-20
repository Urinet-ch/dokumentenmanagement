package ch.urinet.archiv.wissen.application;

import java.util.List;

public interface WissenZeilendiff {
    enum Art { EINFUEGEN, ENTFERNEN, AENDERN }

    record Aenderung(Art art, int altePosition, int neuePosition, List<String> alt, List<String> neu) {
        public Aenderung {
            alt = List.copyOf(alt);
            neu = List.copyOf(neu);
        }
    }

    List<Aenderung> vergleichen(String alt, String neu);
}
