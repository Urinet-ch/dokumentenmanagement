package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sicht und Filter einer Suche (Spec 01, Req 2.3, 14.2) als SQL-Bedingungen auf {@code dokument d} und
 * {@code dokument_finanz f} (left join), ohne Suchbegriff: gemeinsam für die Volltextsuche ({@link PanacheDokumentSuche})
 * und die Datenbankteile der Hybridsuche ({@link PanacheVektorSuche}, Spec 02, Req 2.5). Ohne {@code status} keine
 * verworfenen Dokumente. Nur feste Stücke, jeder Wert gebunden.
 */
final class SuchFilter {

    private SuchFilter() {
    }

    /** Erste Bedingung ist die Sicht; die Werte landen in {@code parameter}. */
    static List<String> bedingungen(Suchanfrage a, Sichtregel regel, Map<String, Object> parameter) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        parameter.putAll(sicht.parameter());
        List<String> bedingungen = new ArrayList<>();
        bedingungen.add(sicht.bedingung());
        filter(bedingungen, parameter, "d.art = :art", "art", a.art());
        filter(bedingungen, parameter, "d.partner_id = :partner", "partner", a.partner());
        filter(bedingungen, parameter, "d.datum >= :datumVon", "datumVon", a.datumVon());
        filter(bedingungen, parameter, "d.datum <= :datumBis", "datumBis", a.datumBis());
        filter(bedingungen, parameter, "f.betrag >= :betragVon", "betragVon", a.betragVon());
        filter(bedingungen, parameter, "f.betrag <= :betragBis", "betragBis", a.betragBis());
        filter(bedingungen, parameter, "f.richtung = :richtung", "richtung",
                a.richtung() == null ? null : a.richtung().name());
        filter(bedingungen, parameter, "d.geschaeftsjahr = :geschaeftsjahr", "geschaeftsjahr", a.geschaeftsjahr());
        filter(bedingungen, parameter, "f.belegnummer = :belegnummer", "belegnummer", a.belegnummer());
        if (a.status() == null) {
            bedingungen.add("d.status <> 'VERWORFEN'");
        } else {
            filter(bedingungen, parameter, "d.status = :status", "status", a.status());
        }
        if (!a.tags().isEmpty()) {
            List<String> namen = new ArrayList<>();
            for (int i = 0; i < a.tags().size(); i++) {
                namen.add(":tag" + i);
                parameter.put("tag" + i, a.tags().get(i));
            }
            bedingungen.add("d.tags @> cast(array[" + String.join(", ", namen) + "] as text[])");
        }
        return bedingungen;
    }

    private static void filter(List<String> bedingungen, Map<String, Object> parameter, String bedingung, String name,
                               Object wert) {
        if (wert != null) {
            bedingungen.add(bedingung);
            parameter.put(name, wert);
        }
    }
}
