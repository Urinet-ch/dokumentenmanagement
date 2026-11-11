package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Auftrag {@code CSV_VORSCHAU} (Aufgabe 4.2; Req 13.3, 13.4, 13.5): gleicht die Zeilen eines Kontakt-Laufs im Status
 * {@code VORSCHAU_AUSSTEHEND} blockweise mit den bestehenden Kontakten ab ({@link CsvVorschauSpeichern#abgleichen}),
 * schreibt Ergebnis, Kontakt und Kandidaten je Zeile und setzt am Ende die Zähler und den Status {@code VORSCHAU}.
 * Übernommen wird nichts. Jeder Block ist eine eigene kurze Transaktion; ändern sich Status oder Zuordnung des Laufs
 * unterwegs (neue Zuordnung), bricht der Auftrag ohne weitere Schreibvorgänge ab, der neue Auftrag rechnet von vorn.
 * Ein Wiederholungsversuch rechnet ebenfalls von vorn. Geloggt werden nur IDs und Zahlen.
 */
@ApplicationScoped
public class CsvVorschauHandler implements AuftragHandler {

    public static final AuftragTyp TYP = new AuftragTyp("CSV_VORSCHAU");
    /** Schlüssel der Nutzlast. */
    public static final String LAUF_ID = "laufId";

    private static final Logger LOG = Logger.getLogger(CsvVorschauHandler.class);

    private final CsvVorschauSpeichern speichern;

    public CsvVorschauHandler(CsvVorschauSpeichern speichern) {
        this.speichern = speichern;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID laufId = auftrag.nutzlast().uuid(LAUF_ID)
                .orElseThrow(() -> new IllegalStateException("Nutzlast ohne " + LAUF_ID));
        Optional<ImportLauf> gefunden = speichern.laden(laufId);
        if (gefunden.isEmpty() || !offen(gefunden.get())) {
            LOG.infof("CSV-Vorschau übersprungen: lauf=%s status=%s", laufId,
                    gefunden.map(l -> l.status().name()).orElse("fehlt"));
            return;
        }
        ImportLauf lauf = gefunden.get();
        int stapel = CsvImport.stapel(speichern.breite(laufId));
        int nachNr = 0;
        int bloecke = 0;
        CsvVorschauSpeichern.Block block;
        while (!(block = speichern.abgleichen(laufId, lauf.zuordnung(), nachNr, stapel)).leer()) {
            unterbrochen();
            if (!speichern.schreiben(auftrag, lauf, block.zeilen())) {
                LOG.infof("CSV-Vorschau abgebrochen: lauf=%s Status oder Zuordnung geändert", laufId);
                return;
            }
            nachNr = block.letzteNr();
            bloecke++;
            unterbrochen();
        }
        Optional<ImportLauf> fertig = speichern.abschliessen(auftrag, lauf);
        if (fertig.isEmpty()) {
            LOG.infof("CSV-Vorschau nicht abgeschlossen: lauf=%s Status, Zuordnung oder Zeilen geändert", laufId);
            return;
        }
        ImportLauf.Zaehler z = fertig.get().vorschau();
        LOG.infof("CSV-Vorschau fertig: lauf=%s bloecke=%d neu=%d geaendert=%d unveraendert=%d dubletten=%d "
                        + "fehler=%d uebersprungen=%d", laufId, bloecke, z.neu(), z.geaendert(), z.unveraendert(),
                z.dubletten(), z.fehler(), z.uebersprungen());
    }

    static boolean offen(ImportLauf lauf) {
        return lauf.art() == ImportArt.KONTAKT && lauf.status() == LaufStatus.VORSCHAU_AUSSTEHEND
                && lauf.zuordnung() != null;
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CSV-Vorschau unterbrochen");
        }
    }
}
