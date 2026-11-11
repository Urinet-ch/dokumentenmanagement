package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.dokument.domain.DateiName;
import ch.urinet.archiv.dokument.domain.Dateipruefung;
import ch.urinet.archiv.dokument.domain.Dateityp;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Upload (Req 3.1–3.8, 4.7, 6.1; design.md «Upload», «Umsetzung 2.4»). Je Seite in dieser Reihenfolge: SHA-256 der
 * temporären Datei (Regel 3), Grösse (413), leer (422), Typ am Inhalt (415), Struktur (422), Dublette (Sperre auf der
 * Prüfsumme bis zum Commit), dann {@code dokument_datei} schreiben und unter {@code eingang/} ablegen. Je neuem
 * Dokument folgt der Auftrag {@code VERARBEITEN} in derselben Transaktion. Rollt sie zurück, löscht das Archiv die
 * schon abgelegten Dateien unter {@code eingang/} wieder.
 * <p>
 * Abgelehnte Dateien hinterlassen nichts: kein Dokument, keine Zeile in {@code dokument_datei}, nichts in der Ablage.
 * Ihre Spur ist das Log: Zu jeder empfangenen Datei hält es Mandant, Rolle, SHA-256 und Grösse fest, bevor das Archiv
 * sie weiter prüft, und danach das Ergebnis (angenommen mit Dokument-ID, Dublette oder Fehlercode); nie den Dateinamen
 * oder den Inhalt (Regel 3, Req 6.1, 16.7).
 */
@ApplicationScoped
@Transactional
public class DokumentErfassen {

    public static final AuftragTyp VERARBEITEN = new AuftragTyp("VERARBEITEN");
    public static final String NUTZLAST_DOKUMENT = "dokumentId";
    public static final int MAX_HINWEIS = 1000;
    public static final String QUELLE_UPLOAD = "UPLOAD";
    public static final String QUELLE_SCAN = "SCAN";
    public static final String QUELLE_DRIVE = "DRIVE";
    public static final String QUELLE_GENERATOR = "GENERATOR";
    static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private static final Logger LOG = Logger.getLogger(DokumentErfassen.class);
    private static final Set<Dateityp> ALLE = Set.of(Dateityp.values());
    /** Die Handy-App sendet die Arbeitsfassung als JPEG (design.md «Ablage»: {@code <nr>-arbeit.jpg}). */
    private static final Set<Dateityp> ARBEITSFASSUNG = Set.of(Dateityp.JPEG);

    private final DokumentRepository repository;
    private final Typerkennung typerkennung;
    private final AblagePort ablage;
    private final AuftragService auftraege;
    private final MandantAufloesung aufloesung;
    private final TransactionSynchronizationRegistry transaktion;
    private final UploadGrenzen grenzen;
    private final Clock uhr;

    @Inject
    public DokumentErfassen(DokumentRepository repository, Typerkennung typerkennung, AblagePort ablage,
                            AuftragService auftraege, MandantAufloesung aufloesung,
                            TransactionSynchronizationRegistry transaktion, UploadGrenzen grenzen) {
        this(repository, typerkennung, ablage, auftraege, aufloesung, transaktion, grenzen, Clock.systemUTC());
    }

    DokumentErfassen(DokumentRepository repository, Typerkennung typerkennung, AblagePort ablage,
                     AuftragService auftraege, MandantAufloesung aufloesung,
                     TransactionSynchronizationRegistry transaktion, UploadGrenzen grenzen, Clock uhr) {
        this.repository = repository;
        this.typerkennung = typerkennung;
        this.ablage = ablage;
        this.auftraege = auftraege;
        this.aufloesung = aufloesung;
        this.transaktion = transaktion;
        this.grenzen = grenzen;
        this.uhr = uhr;
    }

    /** Ein Ergebnis je Seite in der gesendeten Reihenfolge; Fehler der ganzen Anfrage als {@link FachFehler}. */
    public List<UploadErgebnis> erfassen(UploadAnfrage anfrage) {
        MandantCode mandant = aufloesung.pruefen();
        // Regel 3: jede empfangene Datei zuerst prüfen und ihre Prüfsumme festhalten, auch wenn danach die ganze
        // Anfrage abgelehnt wird
        List<Empfangen> empfangen = anfrage.seiten().stream().map(seite -> new Empfangen(
                pruefen(mandant, seite.original(), ALLE, DateiRolle.ORIGINAL),
                seite.arbeitsfassung().map(datei -> pruefen(mandant, datei, ARBEITSFASSUNG,
                        DateiRolle.ARBEITSFASSUNG)))).toList();
        if (empfangen.isEmpty() || anfrage.hinweis().map(DokumentErfassen::hinweisUngueltig).orElse(false)) {
            LOG.infof("Upload abgelehnt: mandant=%s dateien=%d fehlercode=%s", mandant.wert(), empfangen.size(),
                    Fehlercode.ANFRAGE_UNGUELTIG);
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Instant eingang = uhr.instant();
        int jahr = eingang.atZone(ZUERICH).getYear();
        List<UploadErgebnis> ergebnisse = new ArrayList<>();
        Set<UUID> neue = new LinkedHashSet<>();
        UUID gemeinsam = null;
        int nr = 0;
        for (int i = 0; i < anfrage.seiten().size(); i++) {
            UploadAnfrage.Seite seite = anfrage.seiten().get(i);
            DateiName name = DateiName.aus(seite.name());
            Geprueft original = empfangen.get(i).original();
            Optional<Geprueft> arbeitsfassung = empfangen.get(i).arbeitsfassung();
            Optional<Fehlercode> fehler = original.fehler();
            if (fehler.isEmpty() && !name.gueltig()) {
                fehler = Optional.of(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            if (fehler.isEmpty() && arbeitsfassung.isPresent()) {
                fehler = arbeitsfassung.get().fehler();
            }
            if (fehler.isPresent()) {
                LOG.infof("Datei abgelehnt: mandant=%s sha256=%s ergebnis=%s", mandant.wert(), original.sha256(),
                        fehler.get());
                ergebnisse.add(new UploadErgebnis.Abgelehnt(name.anzeige(), fehler.get()));
                continue;
            }
            Optional<UUID> vorhanden = repository.dubletteSperren(original.sha256());
            if (vorhanden.isPresent()) {
                LOG.infof("Datei ist Dublette: mandant=%s sha256=%s ergebnis=DUBLETTE vorhanden=%s", mandant.wert(),
                        original.sha256(), vorhanden.get());
                ergebnisse.add(new UploadErgebnis.Dublette(name.anzeige(), sichtbar(anfrage, vorhanden.get())));
                continue;
            }
            UUID id;
            if (anfrage.zusammenfassen() && gemeinsam != null) {
                id = gemeinsam;
                nr++;
            } else {
                id = anlegen(anfrage, mandant, jahr, eingang);
                gemeinsam = id;
                nr = 1;
                neue.add(id);
            }
            ablegen(mandant, DateiSchluessel.original(jahr, id, nr, original.typ().endung()), DateiRolle.ORIGINAL,
                    seite.original(), original);
            if (arbeitsfassung.isPresent()) {
                ablegen(mandant, DateiSchluessel.arbeitsfassung(jahr, id, nr), DateiRolle.ARBEITSFASSUNG,
                        seite.arbeitsfassung().get(), arbeitsfassung.get());
            }
            LOG.infof("Datei angenommen: mandant=%s sha256=%s ergebnis=ANGENOMMEN dokument=%s nr=%d", mandant.wert(),
                    original.sha256(), id, nr);
            ergebnisse.add(new UploadErgebnis.Angenommen(name.anzeige(), id));
        }
        for (UUID id : neue) {
            auftraege.einreihen(VERARBEITEN, new Nutzlast(Map.of(NUTZLAST_DOKUMENT, id)));
        }
        LOG.debugf("Upload: %d Seiten, neue Dokumente %s", anfrage.seiten().size(), neue);
        return ergebnisse;
    }

    /**
     * Generator (Req 11.1; design.md «Generator-Pfad»): eine PDF wie beim Upload prüfen, die Prüfsumme zuerst
     * festhalten (Regel 3). Eine abgelehnte Datei endet mit dem Fehlercode der Prüfung und hinterlässt nichts.
     */
    Geprueft pdfPruefen(Path datei) {
        MandantCode mandant = aufloesung.pruefen();
        Geprueft geprueft = pruefen(mandant, datei, Set.of(Dateityp.PDF), DateiRolle.ORIGINAL);
        if (geprueft.fehler().isPresent()) {
            LOG.infof("Datei abgelehnt: mandant=%s sha256=%s ergebnis=%s", mandant.wert(), geprueft.sha256(),
                    geprueft.fehler().get());
            throw new FachFehler(geprueft.fehler().get());
        }
        return geprueft;
    }

    Instant jetzt() {
        return uhr.instant();
    }

    /**
     * Legt das Generator-Dokument mit {@code quelle = GENERATOR} an und die geprüfte PDF unter {@code eingang/} ab;
     * Metadaten und Auftrag folgen beim Aufrufer in derselben Transaktion. Rollt sie zurück, räumt das Archiv den
     * Eingang wieder auf.
     */
    UUID generatorAnlegen(Geprueft pdf, Path datei, String erstelltVon, Instant eingang) {
        MandantCode mandant = aufloesung.pruefen();
        int jahr = eingang.atZone(ZUERICH).getYear();
        UUID id = UUID.randomUUID();
        repository.anlegen(new NeuesDokument(id, eingang, QUELLE_GENERATOR, erstelltVon, Optional.empty()));
        transaktion.registerInterposedSynchronization(new EingangAufraeumen(mandant, jahr, id));
        ablegen(mandant, DateiSchluessel.original(jahr, id, 1, pdf.typ().endung()), DateiRolle.ORIGINAL, datei, pdf);
        LOG.infof("Datei angenommen: mandant=%s sha256=%s ergebnis=ANGENOMMEN dokument=%s nr=1", mandant.wert(),
                pdf.sha256(), id);
        return id;
    }

    /** Ohne Benutzer (API-Token) oder ohne Leserecht keine ID (Req 2.3, 3.4). */
    private Optional<UUID> sichtbar(UploadAnfrage anfrage, UUID vorhanden) {
        return anfrage.erfasser().benutzer()
                .filter(benutzer -> repository.sichtbar(vorhanden, Sichtregel.fuer(benutzer)))
                .map(benutzer -> vorhanden);
    }

    private UUID anlegen(UploadAnfrage anfrage, MandantCode mandant, int jahr, Instant eingang) {
        UUID id = UUID.randomUUID();
        repository.anlegen(new NeuesDokument(id, eingang, anfrage.erfasser().quelle(), anfrage.erfasser().id(),
                anfrage.hinweis().filter(h -> !h.isBlank())));
        transaktion.registerInterposedSynchronization(new EingangAufraeumen(mandant, jahr, id));
        return id;
    }

    private void ablegen(MandantCode mandant, DateiSchluessel schluessel, DateiRolle rolle, Path datei,
                         Geprueft geprueft) {
        repository.dateiAnlegen(new NeueDatei(schluessel.docId(), rolle, schluessel.nr(), geprueft.sha256(),
                schluessel.pfad(Ablageort.EINGANG), geprueft.typ().mime(), geprueft.groesse(), geprueft.empfangen()));
        AbgelegteDatei abgelegt = ablage.ablegenEingang(mandant, schluessel, datei);
        if (!abgelegt.sha256().equals(geprueft.sha256())) {
            throw new IllegalStateException("Prüfsumme nach der Ablage weicht ab: " + schluessel.pfad(Ablageort.EINGANG));
        }
        repository.dateiAbgelegt(schluessel.docId(), rolle, schluessel.nr(), abgelegt.versionId());
    }

    private Geprueft pruefen(MandantCode mandant, Path datei, Set<Dateityp> erlaubt, DateiRolle rolle) {
        MessageDigest digest = Pruefsumme.sha256();
        long groesse;
        try (InputStream ein = new DigestInputStream(Files.newInputStream(datei), digest)) {
            groesse = ein.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Upload-Datei nicht lesbar", e);
        }
        Geprueft geprueft = new Geprueft(Pruefsumme.hex(digest), groesse, uhr.instant(), null, Optional.empty());
        LOG.infof("Datei empfangen: mandant=%s rolle=%s sha256=%s bytes=%d", mandant.wert(), rolle, geprueft.sha256(),
                groesse);
        if (groesse > grenzen.maxDatei()) {
            return geprueft.abgelehnt(Fehlercode.DATEI_ZU_GROSS);
        }
        if (groesse == 0) {
            return geprueft.abgelehnt(Fehlercode.DATEI_UNGUELTIG);
        }
        Optional<Dateityp> typ = typerkennung.erkennen(datei).filter(erlaubt::contains);
        if (typ.isEmpty()) {
            return geprueft.abgelehnt(Fehlercode.DATEITYP_UNBEKANNT);
        }
        byte[] kopf = lesen(datei, 0, (int) Math.min(Dateipruefung.KOPF, groesse));
        int endeLaenge = (int) Math.min(Dateipruefung.ENDE, groesse);
        byte[] ende = lesen(datei, groesse - endeLaenge, endeLaenge);
        if (!Dateipruefung.lesbar(typ.get(), kopf, ende, groesse)) {
            return geprueft.abgelehnt(Fehlercode.DATEI_UNGUELTIG);
        }
        return new Geprueft(geprueft.sha256(), groesse, geprueft.empfangen(), typ.get(), Optional.empty());
    }

    private static byte[] lesen(Path datei, long ab, int laenge) {
        try (FileChannel kanal = FileChannel.open(datei, StandardOpenOption.READ)) {
            ByteBuffer puffer = ByteBuffer.allocate(laenge);
            kanal.position(ab);
            while (puffer.hasRemaining() && kanal.read(puffer) >= 0) {
                // weiterlesen, bis der Puffer voll oder die Datei zu Ende ist
            }
            return Arrays.copyOf(puffer.array(), puffer.position());
        } catch (IOException e) {
            throw new UncheckedIOException("Upload-Datei nicht lesbar", e);
        }
    }

    private static boolean hinweisUngueltig(String hinweis) {
        return hinweis.codePointCount(0, hinweis.length()) > MAX_HINWEIS
                || hinweis.codePoints().anyMatch(Character::isISOControl);
    }

    private record Empfangen(Geprueft original, Optional<Geprueft> arbeitsfassung) {
    }

    record Geprueft(String sha256, long groesse, Instant empfangen, Dateityp typ, Optional<Fehlercode> fehler) {
        Geprueft abgelehnt(Fehlercode code) {
            return new Geprueft(sha256, groesse, empfangen, null, Optional.of(code));
        }
    }

    /** Nach einem Rollback liegen unter {@code eingang/<jahr>/<docId>/} keine verwaisten Dateien (design.md «Upload»). */
    private final class EingangAufraeumen implements Synchronization {

        private final MandantCode mandant;
        private final int jahr;
        private final UUID id;

        EingangAufraeumen(MandantCode mandant, int jahr, UUID id) {
            this.mandant = mandant;
            this.jahr = jahr;
            this.id = id;
        }

        @Override
        public void beforeCompletion() {
            // nichts vor dem Commit
        }

        @Override
        public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
                return;
            }
            try {
                ablage.loeschenEingang(mandant, jahr, id);
            } catch (RuntimeException e) {
                LOG.warnf("Eingang nach Rollback nicht gelöscht: Dokument %s (%s)", id, e.getClass().getSimpleName());
            }
        }
    }
}
