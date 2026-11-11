package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.application.DokumentStatusPort.Stand;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Wiederholen (A10, Req 6.8, 6.13, 8.7; design.md «Verarbeitung»): {@code FEHLER} → {@code NEU} mit neuem Auftrag
 * {@code VERARBEITEN}, {@code ARCHIVIERUNG_FEHLER} → {@code ARCHIVIERUNG} mit neuem Auftrag {@code ARCHIVIEREN} (die
 * bestätigten Metadaten bleiben), sonst 409. Status und Auftrag in einer Transaktion.
 */
@ApplicationScoped
@Transactional
public class DokumentWiederholen {

    private final DokumentRepository repository;
    private final DokumentStatusPort status;
    private final AuftragService auftraege;

    public DokumentWiederholen(DokumentRepository repository, DokumentStatusPort status, AuftragService auftraege) {
        this.repository = repository;
        this.status = status;
        this.auftraege = auftraege;
    }

    /** @return die neue Version des Dokuments (für das ETag) */
    public int wiederholen(Benutzer benutzer, UUID id, int erwarteteVersion) {
        Dokument dokument = repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (dokument.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        Stand stand = status.stand(id, true).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (stand.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        switch (stand.status()) {
            case FEHLER -> {
                if (!status.verarbeitungWiederholen(id, erwarteteVersion)) {
                    throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                }
                auftraege.einreihen(DokumentErfassen.VERARBEITEN,
                        new Nutzlast(Map.of(DokumentErfassen.NUTZLAST_DOKUMENT, id)));
            }
            case ARCHIVIERUNG_FEHLER -> {
                if (!status.archivierungWiederholen(id, erwarteteVersion)) {
                    throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                }
                auftraege.einreihen(DokumentBestaetigen.ARCHIVIEREN,
                        new Nutzlast(Map.of(DokumentBestaetigen.NUTZLAST_DOKUMENT, id)));
            }
            default -> throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        return erwarteteVersion + 1;
    }
}
