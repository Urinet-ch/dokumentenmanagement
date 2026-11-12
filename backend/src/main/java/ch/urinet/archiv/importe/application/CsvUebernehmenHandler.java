package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Auftrag {@code CSV_UEBERNEHMEN} (Aufgabe 4.3; Req 13.6–13.9): übernimmt einen Lauf im Status {@code VORSCHAU} in
 * einer einzigen Transaktion ({@link CsvUebernehmenSpeichern}). Deren Zeitgrenze ist
 * {@code archiv.import.uebernahme-transaktion-zeitgrenze}; sie liegt unter der Zeitgrenze des Auftrags, damit die
 * Datenbank zurückrollt, bevor der Arbeiter den Versuch aufgibt. Weil diese Zeitgrenze eine blockierte Anweisung nicht
 * beendet, sind jede Anweisung ({@code archiv.import.anweisung-zeitgrenze}) und jedes Warten auf eine Sperre
 * ({@code archiv.import.sperre-wartezeit}) zusätzlich begrenzt; eine Überschreitung ist ein gewöhnlicher Fehlversuch.
 * Ein Wiederholungsversuch beginnt von vorn; ein
 * schon übernommener Lauf wird übersprungen. Geloggt werden nur IDs und Zahlen.
 */
@ApplicationScoped
public class CsvUebernehmenHandler implements AuftragHandler {

    public static final AuftragTyp TYP = new AuftragTyp("CSV_UEBERNEHMEN");
    /** Schlüssel der Nutzlast. */
    public static final String LAUF_ID = "laufId";

    private static final Logger LOG = Logger.getLogger(CsvUebernehmenHandler.class);

    private final CsvUebernehmenSpeichern speichern;
    private final TransactionManager transaktionen;
    private final int zeitgrenzeSekunden;

    public CsvUebernehmenHandler(CsvUebernehmenSpeichern speichern, TransactionManager transaktionen,
                                 @ConfigProperty(name = "archiv.import.uebernahme-transaktion-zeitgrenze")
                                 Duration zeitgrenze,
                                 @ConfigProperty(name = "archiv.import.anweisung-zeitgrenze") Duration anweisung,
                                 @ConfigProperty(name = "archiv.import.sperre-wartezeit") Duration sperre) {
        if (zeitgrenze.isNegative() || zeitgrenze.toSeconds() < 1) {
            throw new IllegalArgumentException("archiv.import.uebernahme-transaktion-zeitgrenze muss mindestens 1 s sein");
        }
        if (anweisung.compareTo(zeitgrenze) >= 0 || sperre.compareTo(zeitgrenze) >= 0) {
            throw new IllegalArgumentException("archiv.import.anweisung-zeitgrenze und archiv.import.sperre-wartezeit "
                    + "müssen unter archiv.import.uebernahme-transaktion-zeitgrenze liegen");
        }
        this.speichern = speichern;
        this.transaktionen = transaktionen;
        this.zeitgrenzeSekunden = Math.toIntExact(zeitgrenze.toSeconds());
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID laufId = auftrag.nutzlast().uuid(LAUF_ID)
                .orElseThrow(() -> new IllegalStateException("Nutzlast ohne " + LAUF_ID));
        Optional<ImportLauf.Bilanz> bilanz;
        transaktionen.setTransactionTimeout(zeitgrenzeSekunden);
        try {
            bilanz = speichern.uebernehmen(auftrag, laufId);
        } finally {
            zuruecksetzen();
        }
        if (bilanz.isEmpty()) {
            LOG.infof("CSV-Übernahme übersprungen: lauf=%s nicht im Status VORSCHAU", laufId);
            return;
        }
        ImportLauf.Bilanz b = bilanz.get();
        LOG.infof("CSV-Übernahme fertig: lauf=%s angelegt=%d aktualisiert=%d ausgelassen=%d gekennzeichnet=%d",
                laufId, b.angelegt(), b.aktualisiert(), b.ausgelassen(), b.gekennzeichnet());
    }

    private void zuruecksetzen() {
        try {
            transaktionen.setTransactionTimeout(0);
        } catch (SystemException e) {
            LOG.warnf("Zeitgrenze der Transaktion nicht zurückgesetzt: %s", e.getClass().getSimpleName());
        }
    }
}
