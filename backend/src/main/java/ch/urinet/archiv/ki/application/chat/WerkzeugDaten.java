package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driven Port der Chat-Werkzeuge auf {@code dokument} im Schema des Mandanten der laufenden Transaktion. Jede Abfrage
 * hängt den Sichtbarkeitsfilter der {@link Sichtregel} an (Spec 02, Req 5.2; Spec 01, Req 2.3, 2.21), auch in Anzahl und
 * Gesamtzahl. Der Adapter liegt bei {@code dokument}, weil nur dort der {@code VertraulichkeitFilter} gilt (Muster wie die
 * Vektorsuche in Aufgabe 6.4). Nur in einer Transaktion.
 */
public interface WerkzeugDaten {

    /** Macht die laufende Transaktion nur lesend (Req 5.3: die Werkzeuge haben keine Nebenwirkungen). */
    void nurLesend();

    /** Die Dokumente unter {@code ids} (Treffer einer Suche unter der Sichtregel), deren Art bestätigt ist. */
    Set<UUID> bestaetigte(Collection<UUID> ids);

    /** Anfang des Volltexts eines sichtbaren Dokuments, höchstens {@code zeichen} Zeichen; leer ohne Text. */
    Optional<String> inhalt(UUID id, Sichtregel regel, int zeichen);

    /**
     * Fristen sichtbarer, archivierter Dokumente mit Datum von {@code von} bis {@code bis} (beide einschliesslich),
     * nach Datum, Quelle und Dokument sortiert, höchstens {@code hoechstens}; dazu Gesamtzahl und Zahl der Dokumente.
     */
    Fristseite fristen(LocalDate von, LocalDate bis, Sichtregel regel, List<Zusatzfrist> zusatz, int hoechstens);

    /** Gültigkeit aus dem Zusatzfeld {@code feld} der Dokumente der Art {@code art} (Datum {@code JJJJ-MM-TT}). */
    record Zusatzfrist(String art, String feld) {
    }

    /** Woher ein Fristdatum stammt. */
    enum Quelle {
        /** {@code dokument_vertrag.naechste_frist} */
        KUENDIGUNGSFRIST,
        /** {@code dokument_vertrag.gueltig_bis} */
        GUELTIG_BIS,
        /** {@code dokument_finanz.faelligkeit} */
        FAELLIGKEIT,
        /** Zusatzfeld nach {@link Zusatzfrist} */
        ZUSATZ
    }

    /**
     * Eine Frist; {@code datum} ist der gespeicherte Text {@code JJJJ-MM-TT} (bei {@link Quelle#ZUSATZ} ungeprüft).
     * {@code artName} ist der Name der Dokumentart, {@code kuendigungsfrist} der Freitext des Vertrags.
     */
    record Frist(String datum, Quelle quelle, UUID id, String titel, String art, String artName, String partner,
                 BigDecimal betrag, String waehrung, String kuendigungsfrist) {
    }

    /** Eine Seite der Fristen mit der Zahl aller Fristen und aller betroffenen Dokumente unter derselben Sicht. */
    record Fristseite(List<Frist> fristen, long gesamt, long dokumente) {

        public Fristseite {
            fristen = List.copyOf(fristen);
        }
    }
}
