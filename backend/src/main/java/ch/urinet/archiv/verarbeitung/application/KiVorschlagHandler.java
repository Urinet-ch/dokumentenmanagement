package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.KiStand;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.ki.application.MetadatenExtraktion;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Quelltyp;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler für {@code KI_VORSCHLAG} (Aufgabe 3.2; Req 7.1–7.6, 7.11, 7.12; design.md «KI»): PDF/A aus {@code eingang/}
 * laden und gegen ihre SHA-256 prüfen, Seiten rendern, {@link MetadatenExtraktion} (Swiss QR Code, Prompt v6, genau eine
 * Nachfrage, Nachbearbeitung), Vorschlag speichern, Partner bei eindeutigem Treffer verknüpfen und
 * {@code EINGANGSKORB} setzen. Antwortet das Modell nicht oder zweimal ausserhalb des Schemas, geht das Dokument ohne
 * Vorschlag in den Eingangskorb (Req 7.3); das Log nennt nur IDs.
 * <p>
 * Nimmt Dokumente in {@code VERARBEITUNG} und in {@code EINGANGSKORB} mit {@code pdfa_fehlt} an (Req 5.9; dann nur der
 * Volltext, ohne QR und Bild). Ein bestätigtes Dokument, eines mit Vorschlag oder eines in einem anderen Status lässt er
 * unverändert. Den Status setzt er nie zurück und nie auf {@code FEHLER}: Auch nach dem letzten Fehlversuch geht das
 * Dokument nur ohne Vorschlag weiter.
 */
@ApplicationScoped
public class KiVorschlagHandler implements AuftragHandler {

    private static final Logger LOG = Logger.getLogger(KiVorschlagHandler.class);
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final KiVorschlagSpeichern speichern;
    private final MetadatenExtraktion extraktion;
    private final AblagePort ablage;
    private final OcrPort ocr;
    private final Path arbeitsverzeichnis;
    private final int renderSeitenMax;

    public KiVorschlagHandler(KiVorschlagSpeichern speichern, MetadatenExtraktion extraktion, AblagePort ablage,
                              OcrPort ocr,
                              @ConfigProperty(name = "archiv.ocr.arbeitsverzeichnis") Path arbeitsverzeichnis,
                              @ConfigProperty(name = "archiv.ki.render-seiten-max") int renderSeitenMax) {
        this.speichern = speichern;
        this.extraktion = extraktion;
        this.ablage = ablage;
        this.ocr = ocr;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
        this.renderSeitenMax = renderSeitenMax;
    }

    @Override
    public AuftragTyp typ() {
        return VerarbeitungSpeichern.KI_VORSCHLAG;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(VerarbeitenHandler.DOKUMENT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + VerarbeitenHandler.DOKUMENT_ID));
        KiStand stand = speichern.laden(id)
                .orElseThrow(() -> new IllegalStateException("Dokument fehlt im Mandanten: " + id));
        if (!zuBearbeiten(stand)) {
            LOG.infof("KI-Vorschlag übersprungen: Dokument %s ist %s (bestätigt %s, Vorschlag %s)", id, stand.status(),
                    stand.bestaetigt(), stand.vorschlagVorhanden());
            return;
        }
        Optional<Vorschlag> vorschlag = vorschlagen(auftrag.mandant(), stand);
        unterbrochen();
        if (vorschlag.isEmpty()) {
            if (speichern.ohneVorschlagWeiter(auftrag, id)) {
                LOG.infof("Dokument %s ohne KI-Vorschlag im Eingangskorb", id);
            }
            return;
        }
        if (speichern.vorschlagSpeichern(auftrag, stand, vorschlag.get())) {
            LOG.infof("KI-Vorschlag für Dokument %s gespeichert", id);
        } else {
            LOG.infof("KI-Vorschlag für Dokument %s nicht gespeichert: Dokument inzwischen geändert", id);
        }
    }

    /** Req 7.3: Auch nach dem dritten Fehlversuch nie {@code FEHLER}, sondern ohne Vorschlag in den Eingangskorb. */
    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        Optional<UUID> id = auftrag.nutzlast().uuid(VerarbeitenHandler.DOKUMENT_ID);
        if (id.isPresent() && speichern.nachFehlversuchWeiter(id.get())) {
            LOG.infof("Dokument %s nach %d Versuchen ohne KI-Vorschlag im Eingangskorb (fehler-id=%s)", id.get(),
                    Wiederholung.MAX_VERSUCHE, fehlerId);
        }
    }

    static boolean zuBearbeiten(KiStand stand) {
        if (stand.bestaetigt() || stand.vorschlagVorhanden()) {
            return false;
        }
        return stand.status() == DokumentStatus.VERARBEITUNG
                || stand.status() == DokumentStatus.EINGANGSKORB && stand.pdfaFehlt();
    }

    private Optional<Vorschlag> vorschlagen(MandantCode mandant, KiStand stand)
            throws IOException, InterruptedException {
        LocalDate eingang = stand.eingang().atZone(ZUERICH).toLocalDate();
        boolean bildweg = Quelltyp.ausMedientyp(stand.mime()).map(Quelltyp::istBild).orElse(false);
        if (stand.pdfaFehlt() || stand.pdfa().isEmpty()) {
            return extraktion.vorschlagen(new MetadatenExtraktion.Eingabe(stand.id(), mandant, stand.volltext(),
                    List.of(), bildweg, eingang));
        }
        Datei pdfa = stand.pdfa().get();
        DateiSchluessel schluessel = DateiSchluessel.pdfa(eingang.getYear(), stand.id());
        if (!schluessel.pfad(Ablageort.EINGANG).equals(pdfa.s3Schluessel()) || pdfa.s3Version() == null
                || pdfa.sha256() == null) {
            throw new IllegalStateException("PDF/A von " + stand.id() + " mit unerwartetem Schlüssel");
        }
        Path verzeichnis = verzeichnisAnlegen();
        try {
            Path datei = verzeichnis.resolve("pdfa.pdf");
            ablage.lesenEingang(mandant, schluessel, pdfa.s3Version(), pdfa.sha256(), datei);
            unterbrochen();
            return ocr.seitenbilder(datei, renderSeitenMax, seiten -> extraktion.vorschlagen(
                    new MetadatenExtraktion.Eingabe(stand.id(), mandant, stand.volltext(), seiten, bildweg, eingang)));
        } finally {
            loeschen(verzeichnis);
        }
    }

    private Path verzeichnisAnlegen() throws IOException {
        Files.createDirectories(arbeitsverzeichnis);
        try {
            return Files.createTempDirectory(arbeitsverzeichnis, "ki-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory(arbeitsverzeichnis, "ki-");
        }
    }

    private static void loeschen(Path verzeichnis) {
        try {
            Files.walkFileTree(verzeichnis, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) throws IOException {
                    Files.deleteIfExists(datei);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path ordner, IOException fehler) throws IOException {
                    Files.deleteIfExists(ordner);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.warnf("Arbeitsverzeichnis nicht gelöscht: %s (%s)", verzeichnis.getFileName(), e.getClass().getName());
        }
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("KI-Vorschlag unterbrochen");
        }
    }
}
