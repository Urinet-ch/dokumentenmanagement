package ch.urinet.archiv.gemeinsam.domain;

import java.util.UUID;

public final class PaketBereitsImportiert extends FachFehler {
    private final UUID entwurfId;

    public PaketBereitsImportiert(UUID entwurfId) {
        super(Fehlercode.STATUS_KONFLIKT);
        this.entwurfId = entwurfId;
    }

    public UUID entwurfId() {
        return entwurfId;
    }
}
