package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.ExportStatus;
import ch.urinet.archiv.export.domain.Geschaeftsjahr;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Handler für {@code EXPORT_JAHR} (Spec 02, Aufgabe 6.5; Req 7.1–7.3, 7.6; design.md «Jahresexport»): schreibt das ZIP
 * stromweise ({@link ExportSchreiber}) nach {@code exporte/<id>.zip}, hält es mit SHA-256, Grösse und Zahl der Belege
 * fest, protokolliert den Abschluss und meldet ihn per E-Mail (nicht in Stufe P). Vorher stellt er den Lebenszyklus
 * des Buckets für {@code exporte/} sicher (7 Tage).
 * <p>
 * Idempotent: Ein Export, der nicht mehr {@code OFFEN} ist, endet ohne Wirkung. Ein neuer Versuch schreibt das ZIP neu
 * (neue Version unter demselben Schlüssel); ein abgebrochener Upload hinterlässt nichts. Nach dem letzten Fehlversuch
 * steht der Export auf {@code FEHLER} (nur die Fehler-ID, Req 16.7).
 */
@ApplicationScoped
public class ExportJahrHandler implements AuftragHandler {

    public static final AuftragTyp TYP = new AuftragTyp("EXPORT_JAHR");
    public static final String EXPORT_ID = "exportId";
    static final int SEITE = 200;

    private static final Logger LOG = Logger.getLogger(ExportJahrHandler.class);

    private final ExportSpeichern speichern;
    private final ExportBelege belege;
    private final AblagePort ablage;
    private final MandantEinstellungen einstellungen;
    private final ExportBenachrichtigung benachrichtigung;
    private final Stufe stufe;

    @Inject
    public ExportJahrHandler(ExportSpeichern speichern, ExportBelege belege, AblagePort ablage,
                             MandantEinstellungen einstellungen, ExportBenachrichtigung benachrichtigung,
                             @ConfigProperty(name = "archiv.stufe") Optional<String> stufe) {
        this.speichern = speichern;
        this.belege = belege;
        this.ablage = ablage;
        this.einstellungen = einstellungen;
        this.benachrichtigung = benachrichtigung;
        this.stufe = Stufe.aus(stufe);
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(EXPORT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + EXPORT_ID));
        Export export = speichern.laden(id).orElseThrow(() -> new IllegalStateException("Export fehlt: " + id));
        if (export.status() != ExportStatus.OFFEN) {
            LOG.infof("Export %s schon %s", id, export.status());
            return;
        }
        MandantCode mandant = auftrag.mandant();
        Geschaeftsjahr jahr = new Geschaeftsjahr(export.geschaeftsjahr(),
                einstellungen.lesen(mandant).geschaeftsjahresende().monatTag());
        ablage.exportLebenszyklusSichern(mandant);
        ExportSchreiber schreiber = new ExportSchreiber(belege,
                (schluessel, version, ziel) -> ablage.kopieren(mandant, schluessel, version, ziel), SEITE);
        ExportSchreiber.Ergebnis ergebnis;
        AbgelegteDatei zip;
        try (Exportziel ziel = ablage.exportSchreiben(mandant, id)) {
            ergebnis = schreiber.schreiben(jahr, ziel.strom());
            zip = ziel.abschliessen();
        }
        if (!speichern.fertig(auftrag, export, zip, ergebnis.belege())) {
            LOG.infof("Export %s schon abgeschlossen", id);
            return;
        }
        // nur IDs und Zahlen (Req 16.7)
        LOG.infof("Export %s fertig: geschaeftsjahr=%d belege=%d dateien=%d manifestdateien=%d groesse=%d",
                id, export.geschaeftsjahr(), ergebnis.belege(), ergebnis.belegdateien(),
                ergebnis.manifestdateien(), zip.groesse());
        if (ergebnis.ohneManifest() > 0) {
            LOG.infof("Export %s: %d Belege ohne Tages-Manifest ihres Archivierungstags (Manifest folgt nach "
                    + "Tagesende)", id, ergebnis.ohneManifest());
        }
        if (stufe != Stufe.P) {
            benachrichtigung.fertig(mandant, id);
        }
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        auftrag.nutzlast().uuid(EXPORT_ID).ifPresent(id -> {
            if (speichern.fehler(id, fehlerId)) {
                LOG.errorf("Export %s gescheitert (fehler-id=%s)", id, fehlerId);
            }
        });
    }
}
