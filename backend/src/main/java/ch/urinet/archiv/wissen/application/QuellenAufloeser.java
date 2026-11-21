package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class QuellenAufloeser {
    private final DokumentRepository dokumente;
    private final WissenIngestPort daten;

    public QuellenAufloeser(DokumentRepository dokumente, WissenIngestPort daten) {
        this.dokumente = dokumente;
        this.daten = daten;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Aufloesung aufloesen(Benutzer benutzer, String sha256) {
        List<UUID> treffer = dokumente.wissensquellenNachSha256(sha256, Sichtregel.fuer(benutzer));
        if (treffer.isEmpty()) {
            return new Aufloesung(Status.UNBEKANNT, null, null, null);
        }
        if (treffer.size() != 1) {
            return new Aufloesung(Status.MEHRDEUTIG, null, null, null);
        }
        UUID id = treffer.getFirst();
        var quelle = daten.quelleLaden(id);
        var dokument = daten.dokumentQuellenLaden(Set.of(id)).get(id);
        if (quelle.isEmpty() || dokument == null || !dokument.archiviert()
                || !"WISSENSQUELLE".equals(quelle.get().art())) {
            return new Aufloesung(Status.UNBEKANNT, null, null, null);
        }
        if (dokument.personal()) {
            return new Aufloesung(Status.PERSONAL, null, null, null);
        }
        WissensStufe stufe = "NORMAL".equals(quelle.get().vertraulichkeit())
                ? WissensStufe.NORMAL : WissensStufe.INTERN;
        if (!quelle.get().pdfaVorhanden()) {
            dokument = new WissensPruefung.DokumentQuelle(true, true, false, dokument.seiten(), dokument.datum(),
                    Optional.empty());
        }
        return new Aufloesung(Status.DOKUMENT, id, stufe, dokument);
    }

    public enum Status { DOKUMENT, UNBEKANNT, PERSONAL, MEHRDEUTIG }

    public record Aufloesung(Status status, UUID dokumentId, WissensStufe stufe,
                            WissensPruefung.DokumentQuelle dokument) {
    }
}
