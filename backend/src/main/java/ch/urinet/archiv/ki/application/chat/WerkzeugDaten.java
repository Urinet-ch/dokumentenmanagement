package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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

    /** {@link Monatsquelle#bezug()}: das Belegdatum ({@code dokument.datum}). */
    String BELEGDATUM = "BELEGDATUM";
    /** {@link Monatsquelle#gruppe()}: der Partner des Dokuments. */
    String PARTNER = "PARTNER";

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

    /**
     * Belegnummern ({@code dokument_finanz.belegnummer}) der bestätigten, archivierten Dokumente der Art {@code art} im
     * Geschäftsjahr, unter der Sichtregel; ohne leere Nummern.
     */
    List<String> belegnummern(String art, int geschaeftsjahr, Sichtregel regel);

    /**
     * Wie ein Dokument einer Monatsreihe zugeordnet wird: {@code bezug} ist {@code BELEGDATUM} oder
     * {@code zusatz.<feld>} (Datum {@code JJJJ-MM-TT}); {@code gruppe} ist {@code null} (keine Gruppierung),
     * {@code PARTNER} oder {@code zusatz.<feld>}; {@code nurMonat} ist {@code null} oder ein Zusatzfeld, das im Format
     * {@code JJJJ-MM} stehen muss (andere Dokumente zählen nicht). Die Feldnamen prüft der Aufrufer gegen
     * {@code [a-z][a-z0-9_]*}.
     */
    record Monatsquelle(String art, String bezug, String gruppe, String nurMonat) {
    }

    /**
     * Ein Monat einer Gruppe mit mindestens einem Dokument; {@code gruppe} ist der Anzeigewert, {@code ""} ohne
     * Gruppierung und {@code null} für Dokumente ohne Wert im Gruppierungsfeld.
     */
    record Monatszeile(String gruppe, YearMonth monat, long belege) {
    }

    /** Die Monate mit Dokumenten (alle Jahre) der bestätigten, archivierten Dokumente der Art, unter der Sichtregel. */
    List<Monatszeile> monate(Monatsquelle quelle, Sichtregel regel);

    /** Zahl der bestätigten, archivierten Dokumente der Arten unter der Sichtregel. */
    long belege(Collection<String> arten, Sichtregel regel);

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

    /**
     * Summe, Zeilen und Belege der Kennzahl: nur archivierte, bestätigte, sichtbare Belege in {@code CHF} der Richtung
     * und der Arten; Fremdwährungsbelege im selben Filter werden nur gezählt.
     */
    KennzahlSeite kennzahl(KennzahlAnfrage anfrage, Sichtregel regel);

    /**
     * Archivierte, bestätigte, sichtbare Belege der Richtung und Arten mit Fälligkeit von {@code von} bis {@code bis}
     * (beide einschliesslich), nach Fälligkeit sortiert, höchstens {@code hoechstens}; dazu die Gesamtzahl.
     */
    OffenePostenSeite offenePosten(Richtung richtung, List<ArtRegel> arten, LocalDate von, LocalDate bis,
                                   Sichtregel regel, int hoechstens);

    /** Richtung eines Finanzbelegs ({@code dokument_finanz.richtung}). */
    enum Richtung {
        EINNAHME, AUSGABE
    }

    /** Gruppierung der Kennzahl. */
    enum Gruppierung {
        JAHR, MONAT, PARTNER, ART, KEINE
    }

    /** Datum, nach dem die Kennzahl zeitlich einordnet. */
    enum Basis {
        BELEGDATUM, ZAHLUNGSDATUM
    }

    /**
     * Eine Art, die zählt. {@code periodeFeld} (Zusatzfeld) ist {@code null} oder verlangt dort eine Monatsangabe
     * {@code JJJJ-MM}; ohne sie zählt der Beleg nicht (z. B. ein Lohnausweis mit {@code JJJJ}).
     */
    record ArtRegel(String art, String periodeFeld) {
    }

    record KennzahlAnfrage(Richtung richtung, List<ArtRegel> arten, LocalDate von, LocalDate bis, Gruppierung gruppierung,
                           Basis basis, int hoechstensBelege) {

        public KennzahlAnfrage {
            arten = List.copyOf(arten);
        }
    }

    /** Eine Zeile der Kennzahl; {@code gruppe} ist {@code null} ohne Gruppierung oder ohne Partner. */
    record KennzahlZeile(String gruppe, BigDecimal betrag, long anzahl) {
    }

    /**
     * Ergebnis der Kennzahl: {@code summe} und {@code anzahl} über alle Belege in CHF (nicht nur die Zeilen),
     * {@code belege} höchstens so viele IDs, {@code fremdwaehrung} die Zahl der ausgeschlossenen Belege.
     */
    record KennzahlSeite(List<KennzahlZeile> zeilen, BigDecimal summe, long anzahl, List<UUID> belege,
                         long fremdwaehrung, boolean gekuerzt) {

        public KennzahlSeite {
            zeilen = List.copyOf(zeilen);
            belege = List.copyOf(belege);
        }
    }

    /** Ein offener Posten; {@code zahlstatus} ist die gespeicherte Angabe (kann {@code null} sein). */
    record OffenerPosten(UUID id, String belegnummer, String partner, BigDecimal betrag, String waehrung,
                         LocalDate faelligkeit, String zahlstatus) {
    }

    record OffenePostenSeite(List<OffenerPosten> posten, long gesamt) {

        public OffenePostenSeite {
            posten = List.copyOf(posten);
        }
    }
}
