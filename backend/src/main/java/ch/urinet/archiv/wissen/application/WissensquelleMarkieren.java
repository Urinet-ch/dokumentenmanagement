package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.WissensquelleUngueltig;
import ch.urinet.archiv.gemeinsam.domain.WissensquelleUngueltig.Grund;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WissensquelleMarkieren {
    private final WissensquelleMarkierungPort markierungen;
    private final WissenIngestPort quellen;
    private final WissenIngestStarten ingest;
    private final AuftragService auftraege;

    public WissensquelleMarkieren(WissensquelleMarkierungPort markierungen, WissenIngestPort quellen,
                                 WissenIngestStarten ingest, AuftragService auftraege) {
        this.markierungen = markierungen;
        this.quellen = quellen;
        this.ingest = ingest;
        this.auftraege = auftraege;
    }

    @Transactional
    public WissenIngestStarten.Ergebnis markieren(UUID id, String benutzer) {
        auftraege.offen(WissenIngestStarten.TYP, Optional.empty(), true);
        sperren(id);
        if (markierungen.aktiv(id) || quellen.entwurfFuerDokumentLaufend(id)) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        WissenIngestPort.Quelle quelle = quellen.quelleLaden(id)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!"ARCHIVIERT".equals(quelle.status())) {
            throw new WissensquelleUngueltig(Grund.NICHT_ARCHIVIERT);
        }
        if ("PERSONAL".equals(quelle.vertraulichkeit()) || !quelle.bestaetigt()) {
            throw new WissensquelleUngueltig(Grund.PERSONAL);
        }
        if (quelle.volltext() == null || quelle.volltext().isBlank()) {
            throw new WissensquelleUngueltig(Grund.KEIN_VOLLTEXT);
        }
        if (!quelle.pdfaVorhanden()) {
            throw new WissensquelleUngueltig(Grund.KEINE_PDFA);
        }
        markierungen.markieren(id, benutzer);
        return ingest.nachMarkierung(id, benutzer);
    }

    @Transactional
    public void entfernen(UUID id, String benutzer) {
        sperren(id);
        if (!markierungen.entfernen(id, benutzer)) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
    }

    @Transactional
    public List<WissensquelleMarkierungPort.Quelle> quellen(Sichtregel sicht) {
        return markierungen.quellen(sicht);
    }

    private void sperren(UUID id) {
        if (!markierungen.dokumentSperren(id)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }
}
