package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Löst einen Mandanten-Vollexport aus und hält den Abschluss des Worker-Auftrags fest. */
@ApplicationScoped
public class MandantVollexport {

    public static final AuftragTyp TYP = new AuftragTyp("MANDANT_EXPORT");
    public static final String EXPORT_ID = "exportId";

    private final MandantVollexportRepository repository;
    private final AuftragService auftraege;
    private final AblagePort ablage;

    public MandantVollexport(MandantVollexportRepository repository, AuftragService auftraege, AblagePort ablage) {
        this.repository = repository;
        this.auftraege = auftraege;
        this.ablage = ablage;
    }

    /** Legt pro Aufruf eine eigene Export-ID an; Job und Exportdatensatz werden gemeinsam eingereiht. */
    @Transactional
    public UUID anfordern() {
        UUID id = UUID.randomUUID();
        if (!repository.anlegen(id)) {
            throw new IllegalStateException("Mandanten-Vollexport konnte nicht angelegt werden");
        }
        auftraege.einreihen(TYP, new Nutzlast(Map.of(EXPORT_ID, id)));
        return id;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<MandantVollexportRepository.Vollexport> laden(UUID id) {
        return repository.laden(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fertig(Auftrag auftrag, UUID id, AbgelegteDatei zip, int anzahlDokumente) {
        auftraege.anspruchSichern(auftrag);
        return repository.fertig(id, zip, anzahlDokumente);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehler(UUID id, UUID fehlerId) {
        return repository.fehler(id, fehlerId);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehler(Auftrag auftrag, UUID id, UUID fehlerId, MandantVollexportRepository.Fehlerart art) {
        auftraege.anspruchSichern(auftrag);
        return repository.fehler(id, fehlerId, art);
    }

    public VorsignierteUrl herunterladen(MandantCode mandant, UUID id) {
        MandantVollexportRepository.Vollexport export = repository.laden(id)
                .orElseThrow(() -> new IllegalArgumentException("Vollexport nicht gefunden"));
        if (export.status() == MandantVollexportRepository.Status.FEHLER) {
            if (export.fehlerart().orElse(null)
                    == MandantVollexportRepository.Fehlerart.KONFIGURATION_ZU_GROSS) {
                throw new MandantFehler(MandantFehler.Grund.KONFIGURATION_ZU_GROSS,
                        "Die aktuelle Konfigurationsdatei überschreitet 1 MiB. "
                                + "Bitte die Konfiguration verkleinern und den Vollexport erneut starten.");
            }
            throw new IllegalStateException("Vollexport fehlgeschlagen; bitte einen neuen Export starten.");
        }
        if (export.status() != MandantVollexportRepository.Status.FERTIG) {
            throw new IllegalStateException("Vollexport ist noch nicht fertig");
        }
        String schluessel = export.schluessel().orElseThrow();
        String version = export.versionId().orElseThrow();
        Instant gueltigBis = Instant.now().plus(Duration.ofDays(1));
        return ablage.exportUrl(mandant, schluessel, version, gueltigBis, "mandanten-vollexport.zip");
    }
}
