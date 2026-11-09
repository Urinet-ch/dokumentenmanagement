package ch.urinet.archiv.spike.golden;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Ein Golden-Beleg mit den Erwartungswerten der verglichenen Felder (Req 0.2). */
public record Erwartung(
        String id,
        Path datei,
        boolean echt,
        String teil,
        String kategorie,
        String art,
        LocalDate datum,
        String partner,
        BigDecimal betrag,
        String belegnummer,
        List<String> randfaelle) {

    public enum Teil {
        ABSTIMMEN("abstimmen"), ZURUECKGEHALTEN("zurueckgehalten");

        public final String wert;

        Teil(String wert) {
            this.wert = wert;
        }
    }

    /** Mandantenkennungen (design.md «Mandant», Req 7.5). */
    public record Mandant(String name, String uid, List<String> ibans) {
    }

    public record Datensatz(Mandant mandant, List<Erwartung> belege, int ausgeschlossen) {
    }
}
