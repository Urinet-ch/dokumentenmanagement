package ch.urinet.archiv.ablage.application;

import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Archivierung in der Reihenfolge von design.md «Ablage»: kopieren, prüfen und sperren ({@link AblagePort}), dann
 * festhalten (Versions-IDs in {@code dokument_datei}, Status {@code ARCHIVIERT}; Handler {@code ARCHIVIEREN}, Aufgabe
 * 2.6), erst danach {@code eingang/} mit allen Versionen löschen. Scheitert das Festhalten, bleibt {@code eingang/}.
 */
@ApplicationScoped
public class AblageService {

    private final AblagePort ablage;

    public AblageService(AblagePort ablage) {
        this.ablage = ablage;
    }

    public ArchiviertesDokument archivieren(MandantCode mandant, List<ZuArchivieren> dateien, Sperrvorgabe vorgabe,
                                            Consumer<ArchiviertesDokument> festhalten) {
        if (dateien.isEmpty()) {
            throw new IllegalArgumentException("Keine Dateien zum Archivieren");
        }
        int jahr = dateien.getFirst().schluessel().jahr();
        UUID docId = dateien.getFirst().schluessel().docId();
        if (dateien.stream().anyMatch(d -> d.schluessel().jahr() != jahr || !d.schluessel().docId().equals(docId))) {
            throw new IllegalArgumentException("Alle Dateien müssen zum selben Dokument gehören");
        }
        ArchiviertesDokument archiviert = ablage.archivieren(mandant, dateien, vorgabe);
        festhalten.accept(archiviert);
        ablage.loeschenEingang(mandant, jahr, docId);
        return archiviert;
    }
}
