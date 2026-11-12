package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;

/** Reiht {@code CSV_VORSCHAU} über die Auftragsschlange ein; in einer laufenden Transaktion gehört er zu ihr. */
@ApplicationScoped
public class CsvVorschauEinreihen implements VorschauAuftraege {

    private final AuftragService auftraege;

    public CsvVorschauEinreihen(AuftragService auftraege) {
        this.auftraege = auftraege;
    }

    @Override
    public void einreihen(UUID laufId) {
        auftraege.einreihen(CsvVorschauHandler.TYP, new Nutzlast(Map.of(CsvVorschauHandler.LAUF_ID, laufId)));
    }
}
