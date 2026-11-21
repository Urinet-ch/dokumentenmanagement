package ch.urinet.archiv.wissen.domain;

import java.util.UUID;

public record Quelleneintrag(String schluessel, UUID dokumentId, Integer seiteVon, Integer seiteBis, String auszug,
                             UUID quellSeiteId, String sha256) {
    public Quelleneintrag(String schluessel, UUID dokumentId, Integer seiteVon, Integer seiteBis, String auszug,
                         UUID quellSeiteId) {
        this(schluessel, dokumentId, seiteVon, seiteBis, auszug, quellSeiteId, null);
    }
}
