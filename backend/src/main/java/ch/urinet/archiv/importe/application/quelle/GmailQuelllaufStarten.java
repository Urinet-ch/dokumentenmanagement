package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellenTyp;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Map;
import java.util.Optional;

/** Startet Gmail-Quellläufe als idempotenten Worker-Auftrag (Spec 09, Requirement 7.1, 7.3). */
@ApplicationScoped
public class GmailQuelllaufStarten {

    private static final AuftragTyp GMAIL_IMPORT = new AuftragTyp("QUELLLAUF_GMAIL");

    private final QuellenVerwaltung quellen;
    private final AuftragService auftraege;

    public GmailQuelllaufStarten(QuellenVerwaltung quellen, AuftragService auftraege) {
        this.quellen = quellen;
        this.auftraege = auftraege;
    }

    @Transactional
    public QuellLauf starten(String code, String art, Optional<String> benutzer) {
        QuellenTyp typ = quellen.typ(code);
        QuellLauf lauf = quellen.laufStarten(code, art, benutzer);
        if (typ == QuellenTyp.GMAIL_LABEL) {
            auftraege.einreihen(GMAIL_IMPORT, new Nutzlast(Map.of("laufId", lauf.id(),
                    "trocken", lauf.art() == LaufArt.TROCKEN)));
        }
        return lauf;
    }
}
