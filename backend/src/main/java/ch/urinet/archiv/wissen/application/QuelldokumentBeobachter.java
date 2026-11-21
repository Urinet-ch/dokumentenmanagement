package ch.urinet.archiv.wissen.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class QuelldokumentBeobachter {
    private final WissenHuelleDienst huellen;
    private final WissenQuellenAenderungPort quellen;

    public QuelldokumentBeobachter(WissenHuelleDienst huellen, WissenQuellenAenderungPort quellen) {
        this.huellen = huellen;
        this.quellen = quellen;
    }

    public void sperren() {
        huellen.sperren();
    }

    public void geaendert(UUID dokumentId) {
        Set<UUID> seiten = huellen.dokumentSichtbarkeitGeaendert(dokumentId);
        quellen.quellePruefenSetzen(seiten);
    }
}
