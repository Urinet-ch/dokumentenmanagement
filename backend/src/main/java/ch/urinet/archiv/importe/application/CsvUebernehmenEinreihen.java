package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;

/** Reiht {@code CSV_UEBERNEHMEN} über die Auftragsschlange ein; in einer laufenden Transaktion gehört er zu ihr. */
@ApplicationScoped
public class CsvUebernehmenEinreihen implements UebernahmeAuftraege {

    private final AuftragService auftraege;

    public CsvUebernehmenEinreihen(AuftragService auftraege) {
        this.auftraege = auftraege;
    }

    @Override
    public void einreihen(UUID laufId) {
        auftraege.einreihen(CsvUebernehmenHandler.TYP,
                new Nutzlast(Map.of(CsvUebernehmenHandler.LAUF_ID, laufId)));
    }
}
