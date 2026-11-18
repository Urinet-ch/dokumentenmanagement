package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.AblageService;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.dokument.application.DokumentArchivierungPort.Archiviert;
import ch.urinet.archiv.dokument.application.DokumentArchivierungPort.Archivierungsstand;
import ch.urinet.archiv.dokument.application.DokumentBestaetigen;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler für {@code ARCHIVIEREN} (Aufgabe 3.4, Req 6.3, 6.4, 6.8; design.md «Ablage»): Originale, Arbeitsfassungen
 * und PDF/A von {@code eingang/} nach {@code archiv/} kopieren, gegen ihre SHA-256 prüfen und sperren
 * ({@link AblageService}), danach Versions-IDs, {@code sperre_bis} und Status {@code ARCHIVIERT} festhalten und erst
 * dann {@code eingang/} löschen.
 * <p>
 * Eine abweichende Prüfsumme oder eine nicht gesetzte Sperre ({@link ArchivierungAbgebrochen}) setzt das Dokument
 * sofort auf {@code ARCHIVIERUNG_FEHLER}, ohne weiteren Versuch, und benachrichtigt den Inhaber (Req 6.4); Netz- und
 * 5xx-Fehler wiederholt die Auftragsregel, nach dem letzten Versuch gilt dasselbe. Die Metadaten bleiben unverändert.
 * <p>
 * Idempotent: Ist das Dokument schon {@code ARCHIVIERT} (ein früherer Lauf hat festgehalten, aber {@code eingang/} nicht
 * mehr gelöscht), räumt der Lauf nur {@code eingang/} auf; in jedem anderen Status ausser {@code ARCHIVIERUNG} endet er
 * ohne Änderung.
 */
@ApplicationScoped
public class ArchivierenHandler implements AuftragHandler {

    public static final AuftragTyp TYP = DokumentBestaetigen.ARCHIVIEREN;
    public static final String DOKUMENT_ID = DokumentBestaetigen.NUTZLAST_DOKUMENT;

    private static final Logger LOG = Logger.getLogger(ArchivierenHandler.class);
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final ArchivierungSpeichern speichern;
    private final AblageService ablageService;
    private final AblagePort ablage;
    private final InhaberBenachrichtigung benachrichtigung;

    public ArchivierenHandler(ArchivierungSpeichern speichern, AblageService ablageService, AblagePort ablage,
                              InhaberBenachrichtigung benachrichtigung) {
        this.speichern = speichern;
        this.ablageService = ablageService;
        this.ablage = ablage;
        this.benachrichtigung = benachrichtigung;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(DOKUMENT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + DOKUMENT_ID));
        Archivierungsstand stand = speichern.laden(id)
                .orElseThrow(() -> new IllegalStateException("Dokument fehlt im Mandanten: " + id));
        MandantCode mandant = auftrag.mandant();
        int jahr = stand.eingang().atZone(ZUERICH).getYear();
        if (stand.status() == DokumentStatus.ARCHIVIERT) {
            ablage.loeschenEingang(mandant, jahr, id);
            LOG.infof("Dokument %s schon archiviert; eingang/ aufgeräumt", id);
            return;
        }
        if (stand.status() != DokumentStatus.ARCHIVIERUNG) {
            LOG.infof("Archivierung übersprungen: Dokument %s ist %s", id, stand.status());
            return;
        }
        List<Zuordnung> zuordnungen = zuordnungen(stand, jahr);
        unterbrochen();
        Sperrvorgabe vorgabe = new Sperrvorgabe(stand.gesetzlichePflicht(), stand.aufbewahrenBis(),
                stand.fristAbgelaufen());
        try {
            ablageService.archivieren(mandant, zuordnungen.stream().map(Zuordnung::datei).toList(), vorgabe,
                    archiviert -> festhalten(auftrag, id, zuordnungen, archiviert));
        } catch (ArchivierungAbgebrochen e) {
            UUID fehlerId = UUID.randomUUID();
            LOG.errorf("Archivierung von Dokument %s abgebrochen: %s (fehler-id=%s)", id, e.grund(), fehlerId);
            if (speichern.fehlerSetzen(auftrag, id, fehlertext(fehlerId))) {
                benachrichtigung.archivierungGescheitert(mandant, id, fehlerId);
            }
        }
    }

    /** Nach dem dritten Fehlversuch (Netz, 5xx): {@code ARCHIVIERUNG_FEHLER} und Benachrichtigung (Req 6.4). */
    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        Optional<UUID> id = auftrag.nutzlast().uuid(DOKUMENT_ID);
        if (id.isEmpty()) {
            return;
        }
        if (speichern.fehlerSetzen(id.get(), fehlertext(fehlerId))) {
            LOG.infof("Dokument %s nach %d Versuchen auf ARCHIVIERUNG_FEHLER (fehler-id=%s)", id.get(),
                    Wiederholung.MAX_VERSUCHE, fehlerId);
            benachrichtigung.archivierungGescheitert(auftrag.mandant(), id.get(), fehlerId);
        }
    }

    static String fehlertext(UUID fehlerId) {
        return "Die Archivierung ist gescheitert. Die Metadaten sind erhalten. Archivierung wiederholen (Fehler-ID "
                + fehlerId + ").";
    }

    /** Letzter gesperrter Tag in Europe/Zurich: die Sperre läuft bis zum Beginn des Folgetags (Sperrfrist). */
    static Optional<LocalDate> sperreBis(Optional<Instant> sperre) {
        return sperre.map(i -> i.minusNanos(1).atZone(ZUERICH).toLocalDate());
    }

    private void festhalten(Auftrag auftrag, UUID id, List<Zuordnung> zuordnungen, ArchiviertesDokument archiviert) {
        List<AbgelegteDatei> dateien = archiviert.dateien();
        if (dateien.size() != zuordnungen.size()) {
            throw new IllegalStateException("Archivierung von " + id + " lieferte " + dateien.size() + " statt "
                    + zuordnungen.size() + " Dateien");
        }
        List<Archiviert> ergebnis = new ArrayList<>();
        for (int i = 0; i < dateien.size(); i++) {
            Zuordnung z = zuordnungen.get(i);
            AbgelegteDatei d = dateien.get(i);
            if (!z.datei().schluessel().pfad(Ablageort.ARCHIV).equals(d.schluessel())
                    || !z.datei().sha256().equals(d.sha256())) {
                throw new IllegalStateException("Archivierung von " + id + " mit unerwarteter Datei");
            }
            ergebnis.add(new Archiviert(z.rolle(), z.nr(), d.schluessel(), d.versionId()));
        }
        if (!speichern.festhalten(auftrag, id, ergebnis, sperreBis(archiviert.sperreBis()).orElse(null))) {
            // eingang/ bleibt, damit ein späterer Versuch (Wiederholen) die Dateien noch findet
            throw new IllegalStateException("Dokument " + id + " nicht mehr in ARCHIVIERUNG");
        }
    }

    /**
     * Zu archivierende Dateien in fester Reihenfolge: Originale, Arbeitsfassungen, PDF/A. Der Schlüssel muss genau dem
     * erwarteten unter {@code eingang/} entsprechen, und jede Datei braucht Versions-ID und SHA-256; sonst bricht der
     * Lauf ab, bevor er etwas kopiert.
     */
    private static List<Zuordnung> zuordnungen(Archivierungsstand stand, int jahr) {
        List<Zuordnung> liste = new ArrayList<>();
        stand.dateien().stream()
                .filter(d -> d.rolle() != DateiRolle.PDFA)
                .sorted(Comparator.comparing(Datei::rolle).thenComparingInt(Datei::nr))
                .forEach(d -> liste.add(zuordnung(stand.id(), jahr, d)));
        if (liste.stream().noneMatch(z -> z.rolle() == DateiRolle.ORIGINAL)) {
            throw new IllegalStateException("Dokument " + stand.id() + " ohne Original");
        }
        if (stand.pdfa().isPresent()) {
            liste.add(zuordnung(stand.id(), jahr, stand.pdfa().get()));
        } else if (!stand.pdfaFehlt()) {
            throw new IllegalStateException("Dokument " + stand.id() + " ohne PDF/A");
        }
        return liste;
    }

    private static Zuordnung zuordnung(UUID id, int jahr, Datei datei) {
        if (datei.s3Version() == null || datei.s3Schluessel() == null || datei.sha256() == null) {
            throw new IllegalStateException("Datei " + datei.rolle() + " " + datei.nr() + " von " + id
                    + " nicht abgelegt");
        }
        DateiSchluessel schluessel = switch (datei.rolle()) {
            case ORIGINAL -> DateiSchluessel.original(jahr, id, datei.nr(), endung(datei.s3Schluessel()));
            case ARBEITSFASSUNG -> DateiSchluessel.arbeitsfassung(jahr, id, datei.nr());
            case PDFA -> DateiSchluessel.pdfa(jahr, id);
        };
        if (!schluessel.pfad(Ablageort.EINGANG).equals(datei.s3Schluessel())) {
            throw new IllegalStateException("Datei " + datei.rolle() + " " + datei.nr() + " von " + id
                    + " mit unerwartetem Schlüssel");
        }
        return new Zuordnung(datei.rolle(), datei.nr(), new ZuArchivieren(schluessel, datei.s3Version(),
                datei.sha256()));
    }

    private static String endung(String schluessel) {
        int punkt = schluessel.lastIndexOf('.');
        return punkt < 0 ? "" : schluessel.substring(punkt + 1);
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Archivierung unterbrochen");
        }
    }

    private record Zuordnung(DateiRolle rolle, int nr, ZuArchivieren datei) {
    }
}
