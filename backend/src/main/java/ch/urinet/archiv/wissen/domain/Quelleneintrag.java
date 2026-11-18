package ch.urinet.archiv.wissen.domain;

import java.util.UUID;

public record Quelleneintrag(String schluessel, UUID dokumentId, Integer seiteVon, Integer seiteBis, String auszug,
                             UUID quellSeiteId) {
}
