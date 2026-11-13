package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Transaktionen des Auftrags {@code EXPORT_JAHR} (eigene Bean, weil {@code @Transactional} nur über den Proxy wirkt):
 * Der Abschluss hält Status, ZIP und Protokoll in einer Transaktion fest, die zuerst den Anspruch auf den Auftrag
 * sichert (design.md «Verarbeitung»).
 */
@ApplicationScoped
public class ExportSpeichern {

    private final ExportRepository repository;
    private final ExportProtokoll protokoll;
    private final AuftragService auftraege;

    public ExportSpeichern(ExportRepository repository, ExportProtokoll protokoll, AuftragService auftraege) {
        this.repository = repository;
        this.protokoll = protokoll;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Export> laden(UUID id) {
        return repository.laden(id);
    }

    /** {@code true}, wenn dieser Lauf den Export abgeschlossen hat (Protokoll mit SHA-256 des ZIP, Req 7.3). */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fertig(Auftrag auftrag, Export export, AbgelegteDatei zip, int anzahl) {
        auftraege.anspruchSichern(auftrag);
        if (!repository.fertig(export.id(), zip, anzahl)) {
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
