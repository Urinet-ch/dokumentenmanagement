package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feste SQL-Stücke der Finanzwerkzeuge {@code kennzahl} und {@code offene_posten} (Spec 02, Req 3.2–3.4, 5.2; design.md
 * «Chat-Agent», Kennzahl-SQL). Jede Kombination aus Gruppierung und Basis ergibt ein festes Stück; jeder Wert (Richtung,
 * Datum, Arten, Zusatzfelder, Benutzer) ist ein gebundener Parameter, nie Teil des Textes. Immer nur archivierte und
 * bestätigte Belege (unbestätigte zählen nie, auch bei uneingeschränkter Sicht) unter dem {@link VertraulichkeitFilter}.
 *
 * @param zeilen  Zeilen je Gruppe: {@code gruppe, betrag, anzahl}, Währung CHF
 * @param summe   {@code summe, anzahl, fremdwaehrung} über alle Belege des Filters
 * @param belege  IDs der CHF-Belege
 * @param offen   Belege mit Fälligkeit: {@code gesamt, faelligkeit, id, belegnummer, partner, betrag, waehrung, zahlstatus}
 */
record KennzahlSql(String zeilen, String summe, String belege, String offen, Map<String, Object> parameter) {

    /** Höchstzahl der Zeilen je Gruppierung (10 Jahre als Monate sind 121); eine mehr wird zum Erkennen angefordert. */
    static final int ZEILEN_MAX = 200;
    /** Monatsangabe {@code JJJJ-MM} im Zusatzfeld (Lohnabrechnung ja, Lohnausweis mit {@code JJJJ} nein). */
    static final String MONAT = "'^[0-9]{4}-(0[1-9]|1[0-2])$'";
    private static final String VON = " from dokument d join dokument_finanz f on f.dokument_id = d.id where ";
    private static final String CHF = "f.waehrung = 'CHF'";

    /** Datumsspalte nach Basis. */
    static String datumsspalte(WerkzeugDaten.Basis basis) {
        return switch (basis) {
            case BELEGDATUM -> "d.datum";
            case ZAHLUNGSDATUM -> "f.bezahlt_am";
        };
    }

    /** Ausdruck der Gruppe, {@code null} ohne Gruppierung. */
    static String gruppe(WerkzeugDaten.Gruppierung gruppierung, String datum) {
        return switch (gruppierung) {
            case JAHR -> "to_char(" + datum + ", 'YYYY')";
            case MONAT -> "to_char(" + datum + ", 'YYYY-MM')";
            case PARTNER -> "d.partner_name";
            case ART -> "d.art";
            case KEINE -> null;
        };
    }

    static KennzahlSql kennzahl(WerkzeugDaten.KennzahlAnfrage a, Sichtregel regel) {
        Map<String, Object> parameter = new HashMap<>();
        String datum = datumsspalte(a.basis());
        String bedingung = bedingung(a.richtung(), a.arten(), datum + " between :von and :bis", regel, parameter);
        parameter.put("von", a.von());
        parameter.put("bis", a.bis());
        String gruppe = gruppe(a.gruppierung(), datum);
        String zeilen = "select " + (gruppe == null ? "cast(null as text)" : gruppe)
                + " as gruppe, sum(f.betrag) as betrag, count(*) as anzahl" + VON + bedingung + " and " + CHF
                + (gruppe == null ? "" : " group by 1 order by "
                + (a.gruppierung() == WerkzeugDaten.Gruppierung.JAHR || a.gruppierung() == WerkzeugDaten.Gruppierung.MONAT
                ? "1" : "2 desc, 1 nulls last"))
                + " limit " + (ZEILEN_MAX + 1);
        String summe = "select coalesce(sum(f.betrag) filter (where " + CHF + "), 0), count(*) filter (where " + CHF
                + "), count(*) filter (where f.waehrung <> 'CHF')" + VON + bedingung;
        String belege = "select d.id" + VON + bedingung + " and " + CHF + " order by " + datum + ", d.id limit "
                + a.hoechstensBelege();
        return new KennzahlSql(zeilen, summe, belege, null, Map.copyOf(parameter));
    }

    static KennzahlSql offenePosten(WerkzeugDaten.Richtung richtung, List<WerkzeugDaten.ArtRegel> arten, LocalDate von,
                                    LocalDate bis, Sichtregel regel) {
        Map<String, Object> parameter = new HashMap<>();
        String bedingung = bedingung(richtung, arten, "f.faelligkeit between :von and :bis", regel, parameter);
        parameter.put("von", von);
        parameter.put("bis", bis);
        String offen = "select count(*) over () as gesamt, f.faelligkeit, d.id, f.belegnummer, d.partner_name, f.betrag, "
                + "cast(f.waehrung as text), f.zahlstatus" + VON + bedingung
                + " order by f.faelligkeit, d.id limit :hoechstens";
        return new KennzahlSql(null, null, null, offen, Map.copyOf(parameter));
    }

    /**
     * Gemeinsame Bedingung: archiviert, bestätigt, Richtung, Betrag vorhanden, Arten (mit Monatsangabe, wo verlangt),
     * Zeitraum und Sicht. Trägt die benutzten Parameter in {@code parameter} ein.
     */
    private static String bedingung(WerkzeugDaten.Richtung richtung, List<WerkzeugDaten.ArtRegel> arten, String zeitraum,
                                    Sichtregel regel, Map<String, Object> parameter) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        parameter.putAll(sicht.parameter());
        parameter.put("richtung", richtung.name());
        List<String> ohneRegel = new ArrayList<>();
        List<String> teile = new ArrayList<>();
        for (int i = 0; i < arten.size(); i++) {
            WerkzeugDaten.ArtRegel art = arten.get(i);
            if (art.periodeFeld() == null) {
                ohneRegel.add(art.art());
            } else {
                teile.add("(d.art = :periodeArt" + i + " and d.zusatz ->> :periodeFeld" + i + " ~ " + MONAT + ")");
                parameter.put("periodeArt" + i, art.art());
                parameter.put("periodeFeld" + i, art.periodeFeld());
            }
        }
        if (!ohneRegel.isEmpty()) {
            teile.add("d.art in (:arten)");
            parameter.put("arten", List.copyOf(ohneRegel));
        }
        String artBedingung = teile.isEmpty() ? "1 = 0" : "(" + String.join(" or ", teile) + ")";
        return "d.status = 'ARCHIVIERT' and d.bestaetigt is not null and f.richtung = :richtung and f.betrag is not null"
                + " and " + artBedingung + " and " + zeitraum + " and " + sicht.bedingung();
    }
}
