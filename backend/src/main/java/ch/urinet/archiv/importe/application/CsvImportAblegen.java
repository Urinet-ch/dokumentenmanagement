package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.UUID;

/** Legt die Importdatei über den {@link AblagePort} im Bucket des Mandanten der Anfrage ab. */
@ApplicationScoped
public class CsvImportAblegen implements ImportAblage {

    private final AblagePort ablage;
    private final MandantAufloesung aufloesung;

    public CsvImportAblegen(AblagePort ablage, MandantAufloesung aufloesung) {
        this.ablage = ablage;
        this.aufloesung = aufloesung;
    }

    @Override
    public String ablegen(UUID laufId, Path datei) {
        return ablage.ablegenImport(aufloesung.pruefen(), laufId, datei).sha256();
    }
}
