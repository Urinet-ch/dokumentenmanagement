package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.KiStand;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.ki.application.KiAusfall;
import ch.urinet.archiv.ki.application.MetadatenExtraktion;
import ch.urinet.archiv.ki.domain.QrDaten;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handler für {@code KI_VORSCHLAG} (Aufgabe 3.2; Req 7.1–7.6, 7.11, 7.12; design.md «KI», «Umsetzung 3.2»): PDF/A aus
 * {@code eingang/} laden und gegen ihre SHA-256 prüfen, Text mit {@code pdftotext -layout} wie im Golden-Lauf, Seiten
 * rendern (graustufig für den Swiss QR Code, farbig nur im Bildweg), {@link MetadatenExtraktion} (Prompt v6, genau eine
 * Nachfrage, Nachbearbeitung), Vorschlag speichern, Partner bei eindeutigem Treffer verknüpfen und
 * {@code EINGANGSKORB} setzen. Antwortet das Modell zweimal ausserhalb des Schemas, geht das Dokument ohne Vorschlag in
 * den Eingangskorb (Req 7.3); antwortet es nicht ({@link KiAusfall}) oder scheitert das Rendern, ist es ein Fehlversuch
 * des Auftrags (Req 5.4). Reicht die Frist des Renderns nicht, geht es ohne QR bzw. Seitenbild weiter (kein
 * Fehlversuch). Das Log nennt nur IDs.
 * <p>
 * Rendern und QR-Lesen belegen viel Heap; sie laufen darum in der JVM nacheinander (eine Sperre mit einem Platz) und
 * innerhalb von {@code archiv.ki.render-zeitgrenze}, der Aufruf des Modells danach ausserhalb der Sperre.
 * <p>
 * Nimmt Dokumente in {@code VERARBEITUNG} und in {@code EINGANGSKORB} mit {@code pdfa_fehlt} an (Req 5.9; dann nur der
 * Volltext, ohne QR und Bild). Ein bestätigtes Dokument, eines mit Vorschlag oder eines in einem anderen Status lässt er
 * unverändert. Den Status setzt er nie zurück und nie auf {@code FEHLER}: Auch nach dem letzten Fehlversuch geht das
 * Dokument nur ohne Vorschlag weiter.
 * <p>
 * Trägt die Nutzlast {@link VerarbeitenHandler#DOKUMENT_VERSION} (die {@code version} beim Einreihen), gilt in jedem
 * Versuch nur sie: Weicht die geladene ab, hat der Benutzer das Dokument inzwischen geändert (z. B. ein
 * {@code pdfa_fehlt}-Dokument im Eingangskorb zwischen zwei Versuchen), und der Handler ruft das Modell nicht auf und
 * schreibt weder Vorschlag noch Partner (Review PR #93, Claude S2). Aufträge ohne den Schlüssel (vor dem Deploy
 * eingereiht) prüfen wie bisher die beim Laden gelesene {@code version}.
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
    private final Duration renderZeitgrenze;
    private final long renderPixelMax;
    /** Ein Platz: Rendern und Decodieren der Seiten nie gleichzeitig in dieser JVM (Speicherbudget Stufe A). */
    private final Semaphore sperre = new Semaphore(1, true);

    public KiVorschlagHandler(KiVorschlagSpeichern speichern, MetadatenExtraktion extraktion, AblagePort ablage,
                              OcrPort ocr,
                              @ConfigProperty(name = "archiv.ocr.arbeitsverzeichnis") Path arbeitsverzeichnis,
                              @ConfigProperty(name = "archiv.ki.render-seiten-max") int renderSeitenMax,
                              @ConfigProperty(name = "archiv.ki.render-zeitgrenze") Duration renderZeitgrenze,
                              @ConfigProperty(name = "archiv.ki.render-pixel-max") long renderPixelMax) {
        this.speichern = speichern;
        this.extraktion = extraktion;
        this.ablage = ablage;
        this.ocr = ocr;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
        this.renderSeitenMax = renderSeitenMax;
        this.renderZeitgrenze = renderZeitgrenze;
        this.renderPixelMax = renderPixelMax;
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
        OptionalLong erwartet = auftrag.nutzlast().zahl(VerarbeitenHandler.DOKUMENT_VERSION);
        if (erwartet.isPresent() && stand.version() != erwartet.getAsLong()) {
            LOG.infof("KI-Vorschlag für Dokument %s nicht erstellt: Dokument inzwischen geändert", id);
            if (speichern.ohneVorschlagWeiter(auftrag, id)) {
                LOG.infof("Dokument %s ohne KI-Vorschlag im Eingangskorb", id);
            }
            return;
        }
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
        int version = erwartet.isPresent() ? (int) erwartet.getAsLong() : stand.version();
        if (speichern.vorschlagSpeichern(auftrag, stand, version, vorschlag.get())) {
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
            throws IOException, InterruptedException, TimeoutException {
        LocalDate eingang = stand.eingang().atZone(ZUERICH).toLocalDate();
        if (stand.pdfaFehlt() || stand.pdfa().isEmpty()) {
            return extraktion.vorschlagen(new MetadatenExtraktion.Eingabe(stand.id(), mandant, stand.volltext(),
                    Optional.empty(), Optional.empty(), eingang));
        }
        boolean bildweg = Quelltyp.ausMedientyp(stand.mime()).map(Quelltyp::istBild).orElse(false);
        Datei pdfa = stand.pdfa().get();
        DateiSchluessel schluessel = DateiSchluessel.pdfa(eingang.getYear(), stand.id());
        if (!schluessel.pfad(Ablageort.EINGANG).equals(pdfa.s3Schluessel()) || pdfa.s3Version() == null
                || pdfa.sha256() == null) {
            throw new IllegalStateException("PDF/A von " + stand.id() + " mit unerwartetem Schlüssel");
        }
        Path verzeichnis = verzeichnisAnlegen();
        MetadatenExtraktion.Eingabe eingabe;
        try {
            Path datei = verzeichnis.resolve("pdfa.pdf");
            ablage.lesenEingang(mandant, schluessel, pdfa.s3Version(), pdfa.sha256(), datei);
            unterbrochen();
            Instant ende = Instant.now().plus(renderZeitgrenze);
            String layout = ocr.layoutText(datei, rest(ende));
            String text = layout.isBlank() ? stand.volltext() : layout;
            Optional<QrDaten> qr = Optional.empty();
            Optional<byte[]> bild = Optional.empty();
            try {
                if (!sperre.tryAcquire(rest(ende).toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new TimeoutException("Rendern für Dokument " + stand.id() + " nicht innerhalb der Frist frei");
                }
                try {
                    qr = ocr.seitenbilder(datei,
                            new OcrPort.Render(renderSeitenMax, false, renderPixelMax, rest(ende)), extraktion::qr);
                    if (bildweg) {
                        bild = ocr.seitenbilder(datei,
                                new OcrPort.Render(renderSeitenMax, true, renderPixelMax, rest(ende)),
                                extraktion::bild);
                    }
                } finally {
                    sperre.release();
                }
            } catch (TimeoutException e) {
                fristAbgelaufen(stand.id(), qr.isPresent(), bildweg);
            } catch (OcrFehlgeschlagen e) {
                if (e.art() != OcrFehlgeschlagen.Art.ZEITUEBERSCHREITUNG) {
                    throw e;
                }
                fristAbgelaufen(stand.id(), qr.isPresent(), bildweg);
            }
            eingabe = new MetadatenExtraktion.Eingabe(stand.id(), mandant, text, qr, bild, eingang);
        } finally {
            loeschen(verzeichnis);
        }
        unterbrochen();
        return extraktion.vorschlagen(eingabe);
    }

    /**
     * Seiten nicht innerhalb von {@code archiv.ki.render-zeitgrenze} gerendert (z. B. ein langer Scan auf Stufe A mit
     * {@code cpus: 0.3}): kein Fehlversuch, sondern weiter mit dem Text und ohne, was fehlt (QR, Seitenbild). Ein
     * Fehlversuch lieferte bei denselben Seiten dreimal dasselbe und das Dokument käme nie zu einem Vorschlag.
     */
    private void fristAbgelaufen(UUID id, boolean qrGelesen, boolean bildweg) {
        LOG.warnf("Seiten von Dokument %s nicht innerhalb von %s gerendert: weiter ohne %s", id, renderZeitgrenze,
                qrGelesen ? "Seitenbild" : bildweg ? "Swiss QR Code und Seitenbild" : "Swiss QR Code");
    }

    /** Restzeit bis {@code ende}; abgelaufen ist ein Fehlversuch (Frist des Renderns, nicht die des Auftrags). */
    private Duration rest(Instant ende) throws TimeoutException {
        Duration rest = Duration.between(Instant.now(), ende);
        if (rest.isNegative() || rest.isZero()) {
            throw new TimeoutException("Frist für Text und Seitenbilder abgelaufen");
        }
        return rest;
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
