package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WissensEmbeddingSpeichern {
    public static final AuftragTyp EINZELN = new AuftragTyp("WISSEN_EMBEDDING");
    public static final AuftragTyp ALLE = new AuftragTyp("WISSEN_EMBEDDING_ALLE");
    static final int TEIL = 500;
    private final WissensEmbeddingPort port;
    private final AuftragService auftraege;

    public WissensEmbeddingSpeichern(WissensEmbeddingPort port, AuftragService auftraege) {
        this.port = port;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<WissensEmbeddingPort.Stand> laden(UUID id) {
        return port.laden(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean ersetzen(Auftrag auftrag, UUID id, int version, List<ChunkPort.Eingebettet> chunks) {
        boolean ersetzt = port.ersetzen(id, version, chunks);
        auftraege.anspruchSichern(auftrag);
        return ersetzt;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long starten() {
        var laufend = auftraege.offen(ALLE, Optional.empty(), true);
        if (laufend.isEmpty()) {
            laufend = auftraege.offen(EINZELN, Optional.of("vorgaenger"), false);
        }
        return laufend.isPresent() ? laufend.getAsLong() : auftraege.einreihen(ALLE, Nutzlast.leer());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void teilEinreihen(Auftrag auftrag) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, "vorgaenger")) return;
        var seiten = port.nachberechnen(auftrag.nutzlast().uuid("nach"), TEIL);
        for (var seite : seiten) {
            auftraege.einreihen(EINZELN, new Nutzlast(Map.of("seiteId", seite.id(), "version", seite.version(),
                    "vorgaenger", auftrag.id())));
        }
        if (seiten.size() == TEIL) {
            Map<String, Object> nutzlast = new HashMap<>();
            nutzlast.put("nach", seiten.getLast().id());
            nutzlast.put("vorgaenger", auftrag.id());
            auftraege.einreihen(ALLE, new Nutzlast(nutzlast));
        }
    }
}
