package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WissenExportSpeichern {
    private final WissenExportRepository repository;
    private final WissenExportProtokoll protokoll;
    private final AuftragService auftraege;

    public WissenExportSpeichern(WissenExportRepository repository, WissenExportProtokoll protokoll,
                                 AuftragService auftraege) {
        this.repository = repository;
        this.protokoll = protokoll;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<WissenExportRepository.Export> laden(UUID id) {
        return repository.laden(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fertig(Auftrag auftrag, WissenExportRepository.Export export, AbgelegteDatei zip) {
        auftraege.anspruchSichern(auftrag);
        if (!repository.fertig(export.id(), zip)) {
            return false;
        }
        protokoll.abgeschlossen(export.id(), export.benutzer(), export.rolle(), zip.sha256());
        return true;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehler(UUID id, UUID fehlerId) {
        return repository.fehler(id, fehlerId);
    }
}
