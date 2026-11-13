package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Verarbeitungsergebnis;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Verarbeitungsstand;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;
import ch.urinet.archiv.verarbeitung.domain.OcrQuelle;
import ch.urinet.archiv.verarbeitung.domain.Quelltyp;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler für {@code VERARBEITEN} (Aufgabe 2.6, Req 5.1–5.4, 5.8–5.10; design.md «Verarbeitung»): Status
 * {@code VERARBEITUNG}, Quellen aus {@code eingang/} laden und gegen ihre SHA-256 prüfen, {@link OcrPort} (HEIC → JPEG,
 * Bilder → PDF, OCRmyPDF, {@code pdftotext}, Vorschau), PDF/A unter {@code eingang/<jahr>/<docId>/pdfa.pdf} und
 * Vorschaubilder unter {@code vorschau/<docId>/} ablegen, Ergebnis speichern und {@code KI_VORSCHLAG} einreihen;
 * Generator-Dokumente ({@code quelle = GENERATOR}, Nutzlast {@code ohneKi}) ohne KI weiter nach {@code ARCHIVIERUNG}
 * und {@code ARCHIVIEREN} (Aufgabe 4.7, Req 11.2).
 * <p>
 * Idempotent: Hat ein früherer Lauf das Ergebnis gespeichert oder ist das Dokument nicht mehr {@code NEU} oder
 * {@code VERARBEITUNG}, endet der Lauf ohne Änderung. Abgeleitete Dateien eines abgebrochenen Laufs löscht der nächste
 * Lauf nach dem Commit von {@code VERARBEITUNG} ohne Sperre; zwischen Auflisten und Löschen prüft er den Anspruch
 * erneut. Nach dem Speichern bleibt nur die gespeicherte Fassung; Originale und Arbeitsfassungen
 * bleiben unverändert (Req 5.8, 5.10). Ohne Anspruch schreibt der Lauf weder Dokument noch Folgeauftrag
 * ({@link VerarbeitungSpeichern}).
 */
@ApplicationScoped
public class VerarbeitenHandler implements AuftragHandler {

    public static final AuftragTyp TYP = new AuftragTyp("VERARBEITEN");
    /** Schlüssel der Nutzlast; weitere Schlüssel ausser {@code ohneKi} (und {@link #DOKUMENT_VERSION} bei {@code KI_VORSCHLAG}) gibt es nicht. */
    public static final String DOKUMENT_ID = "dokumentId";
    /**
     * Schlüssel der Nutzlast von {@code KI_VORSCHLAG}: die {@code version} des Dokuments direkt nach dem Speichern des
     * Ergebnisses; der {@link KiVorschlagHandler} schreibt nur bei genau dieser Version (Review PR #93, Claude S2).
     */
    public static final String DOKUMENT_VERSION = "dokumentVersion";
    static final String OHNE_KI = "ohneKi";
    static final String QUELLE_GENERATOR = "GENERATOR";
    /** Medientyp einer Nachricht als Original (Spec 02, Req 1.3). */
    static final String MIME_EML = "message/rfc822";

    private static final Logger LOG = Logger.getLogger(VerarbeitenHandler.class);
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final VerarbeitungSpeichern speichern;
    private final AblagePort ablage;
    private final OcrPort ocr;
    private final Path arbeitsverzeichnis;

    public VerarbeitenHandler(VerarbeitungSpeichern speichern, AblagePort ablage, OcrPort ocr,
                              @ConfigProperty(name = "archiv.ocr.arbeitsverzeichnis") Path arbeitsverzeichnis) {
        this.speichern = speichern;
        this.ablage = ablage;
        this.ocr = ocr;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = dokumentId(auftrag);
        Verarbeitungsstand stand = speichern.laden(id)
                .orElseThrow(() -> new IllegalStateException("Dokument fehlt im Mandanten: " + id));
        // Generator-Dokumente sind bestätigt und gehen ohne KI in ARCHIVIERUNG (Req 11.2), auch nach «Wiederholen»,
        // dessen Auftrag den Schalter nicht trägt; ohne KI gibt es nur für sie
        boolean generator = QUELLE_GENERATOR.equals(stand.quelle());
        if (auftrag.nutzlast().schalter(OHNE_KI).orElse(false) && !generator) {
            throw new IllegalStateException("Verarbeitung ohne KI nur für Generator-Dokumente: " + id);
        }
        if (!zuVerarbeiten(stand)) {
            LOG.infof("Verarbeitung übersprungen: Dokument %s ist %s", id, stand.status());
            return;
        }
        MandantCode mandant = auftrag.mandant();
        int jahr = stand.eingang().atZone(ZUERICH).getYear();
        List<Quelle> quellen = quellen(stand, jahr);
        if (!speichern.beginnen(auftrag, id)) {
            LOG.infof("Verarbeitung übersprungen: Dokument %s inzwischen verarbeitet oder nicht mehr offen", id);
            return;
        }
        unterbrochen();
        // ohne Sperre, damit Lebenszeichen und Aufräumer nicht auf den Object Storage warten (Review PR #54, S2)
        ablage.abgeleiteteBereinigen(mandant, jahr, id, List.of(), () -> speichern.anspruchPruefen(auftrag));
        unterbrochen();
        Path verzeichnis = verzeichnisAnlegen();
        try {
            List<OcrQuelle> eingabe = new ArrayList<>();
            for (Quelle quelle : quellen) {
                Path ziel = verzeichnis.resolve("quelle-" + (eingabe.size() + 1) + "." + quelle.typ().endung());
                ablage.lesenEingang(mandant, quelle.schluessel(), quelle.version(), quelle.sha256(), ziel);
                unterbrochen();
                eingabe.add(new OcrQuelle(quelle.typ(), () -> Files.newInputStream(ziel)));
            }
            Abgelegt abgelegt = ocr.verarbeiten(new OcrEingabe(eingabe), e -> ablegen(mandant, jahr, id, e));
            unterbrochen();
            if (!speichern.ergebnisSpeichern(auftrag, id, abgelegt.ergebnis(), generator)) {
                LOG.warnf("Ergebnis nicht gespeichert: Dokument %s ist nicht mehr in VERARBEITUNG", id);
                return;
            }
            nachbereinigen(mandant, jahr, id, abgelegt.dateien());
        } finally {
            loeschen(verzeichnis);
        }
    }

    /**
     * Setzt das Dokument nach dem dritten Fehlversuch auf {@code FEHLER} (Req 5.4), nur aus {@code NEU} oder
     * {@code VERARBEITUNG}; ein erneuter Aufruf ändert nichts. Der Fehlertext nennt nur die Fehler-ID (Req 16.7);
     * Originale bleiben unverändert.
     */
    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        Optional<UUID> id = auftrag.nutzlast().uuid(DOKUMENT_ID);
        if (id.isEmpty()) {
            return;
        }
        if (speichern.fehlerSetzen(id.get(), fehlertext(fehlerId))) {
            LOG.infof("Dokument %s nach %d Versuchen auf FEHLER (fehler-id=%s)", id.get(), Wiederholung.MAX_VERSUCHE,
                    fehlerId);
        }
    }

    static String fehlertext(UUID fehlerId) {
        return "Die Verarbeitung ist nach " + Wiederholung.MAX_VERSUCHE + " Versuchen gescheitert. Das Original ist "
                + "unverändert. Verarbeitung wiederholen oder das Dokument verwerfen (Fehler-ID " + fehlerId + ").";
    }

    private static UUID dokumentId(Auftrag auftrag) {
        return auftrag.nutzlast().uuid(DOKUMENT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + DOKUMENT_ID));
    }

    private static boolean zuVerarbeiten(Verarbeitungsstand stand) {
        return !stand.ergebnisVorhanden()
                && (stand.status() == DokumentStatus.NEU || stand.status() == DokumentStatus.VERARBEITUNG);
    }

    /**
     * Quellen der Verarbeitung: die Arbeitsfassungen, wenn es welche gibt (Handy-Erfassung, Req 5.10), sonst die
     * Originale, je nach Nummer. Eine Nachricht ({@value #MIME_EML}, Mail ohne Anhang, Spec 02, Req 1.3) ist keine
     * Quelle: Das PDF/A entsteht aus ihrem Text, der als PDF neben ihr liegt. Der Schlüssel muss genau dem erwarteten
     * unter {@code eingang/} entsprechen, und jede Datei braucht Versions-ID, SHA-256 und einen bekannten Typ; sonst
     * bricht der Lauf ab, bevor er etwas ändert.
     */
    private static List<Quelle> quellen(Verarbeitungsstand stand, int jahr) {
        boolean arbeitsfassung = stand.dateien().stream().anyMatch(d -> d.rolle() == DateiRolle.ARBEITSFASSUNG);
        DateiRolle rolle = arbeitsfassung ? DateiRolle.ARBEITSFASSUNG : DateiRolle.ORIGINAL;
        List<Quelle> quellen = stand.dateien().stream()
                .filter(d -> d.rolle() == rolle)
                .filter(d -> !MIME_EML.equals(d.mime()))
                .sorted(Comparator.comparingInt(Datei::nr))
                .map(d -> quelle(stand.id(), jahr, d))
                .toList();
        if (quellen.isEmpty()) {
            throw new IllegalStateException("Dokument " + stand.id() + " ohne abgelegte Datei");
        }
        boolean pdf = quellen.stream().anyMatch(q -> q.typ() == Quelltyp.PDF);
        if (pdf && quellen.size() > 1) {
            // ein Dokument aus mehreren PDFs oder aus PDF und Bildern sieht der Upload nicht vor (Req 3.5)
            throw new IllegalStateException("Dokument " + stand.id() + " mit mehreren Dateien und PDF");
        }
        return quellen;
    }

    private static Quelle quelle(UUID id, int jahr, Datei datei) {
        if (datei.s3Version() == null || datei.s3Schluessel() == null || datei.sha256() == null) {
            throw new IllegalStateException("Datei " + datei.rolle() + " " + datei.nr() + " von " + id
                    + " nicht abgelegt");
        }
        Quelltyp typ = Quelltyp.ausMedientyp(datei.mime()).orElseThrow(() -> new IllegalStateException(
                "Datei " + datei.rolle() + " " + datei.nr() + " von " + id + " mit unbekanntem Typ"));
        DateiSchluessel schluessel = switch (datei.rolle()) {
            case ARBEITSFASSUNG -> DateiSchluessel.arbeitsfassung(jahr, id, datei.nr());
            case ORIGINAL -> DateiSchluessel.original(jahr, id, datei.nr(), endung(datei.s3Schluessel()));
            case PDFA -> throw new IllegalStateException("PDF/A ist keine Quelle");
        };
        if (!schluessel.pfad(Ablageort.EINGANG).equals(datei.s3Schluessel())) {
            throw new IllegalStateException("Datei " + datei.rolle() + " " + datei.nr() + " von " + id
                    + " mit unerwartetem Schlüssel");
        }
        return new Quelle(schluessel, datei.s3Version(), datei.sha256(), typ);
    }

    private static String endung(String schluessel) {
        int punkt = schluessel.lastIndexOf('.');
        return punkt < 0 ? "" : schluessel.substring(punkt + 1);
    }

    private Abgelegt ablegen(MandantCode mandant, int jahr, UUID id, OcrErgebnis ergebnis)
            throws IOException, InterruptedException {
        List<AbgelegteDatei> dateien = new ArrayList<>();
        Optional<AbgelegteDatei> pdfa = Optional.empty();
        if (ergebnis.pdfa().isPresent()) {
            Path datei = ergebnis.pdfa().get();
            try (InputStream inhalt = Files.newInputStream(datei)) {
                pdfa = Optional.of(ablage.ablegenEingang(mandant, DateiSchluessel.pdfa(jahr, id), inhalt,
                        Files.size(datei)));
            }
            dateien.add(pdfa.get());
            unterbrochen();
        }
        int seite = 0;
        for (Path bild : ergebnis.vorschaubilder()) {
            dateien.add(ablage.ablegenVorschau(mandant, id, ++seite, bild));
            unterbrochen();
        }
        Integer seiten = seite == 0 ? null : seite;
        Verarbeitungsergebnis gespeichert = pdfa
                .map(d -> new Verarbeitungsergebnis(d.sha256(), d.schluessel(), d.versionId(), false, null, seiten,
                        ergebnis.volltext()))
                .orElseGet(() -> new Verarbeitungsergebnis(null, null, null, true,
                        ergebnis.pdfaFehlt().orElseThrow().fehlertext(), seiten, ergebnis.volltext()));
        return new Abgelegt(gespeichert, dateien);
    }

    /** Nach dem Commit: übrige Versionen (z. B. von einem Lauf ohne Anspruch) löschen; ein Fehler ändert nichts. */
    private void nachbereinigen(MandantCode mandant, int jahr, UUID id, List<AbgelegteDatei> behalten) {
        try {
            ablage.abgeleiteteBereinigen(mandant, jahr, id, behalten);
        } catch (RuntimeException e) {
            LOG.warnf("Abgeleitete Dateien von Dokument %s nicht bereinigt (%s)", id, e.getClass().getName());
        }
    }

    private Path verzeichnisAnlegen() throws IOException {
        Files.createDirectories(arbeitsverzeichnis);
        try {
            return Files.createTempDirectory(arbeitsverzeichnis, "quellen-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory(arbeitsverzeichnis, "quellen-");
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
            throw new InterruptedException("Verarbeitung unterbrochen");
        }
    }

    private record Quelle(DateiSchluessel schluessel, String version, String sha256, Quelltyp typ) {
    }

    private record Abgelegt(Verarbeitungsergebnis ergebnis, List<AbgelegteDatei> dateien) {
    }
}
