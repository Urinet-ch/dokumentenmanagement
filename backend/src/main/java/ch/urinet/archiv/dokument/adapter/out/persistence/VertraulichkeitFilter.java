package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Übersetzt die {@link Sichtregel} in ein HQL-Prädikat auf {@link DokumentEintrag} (ohne Alias, für Panache) mit genau
 * den benutzten Parametern (Req 2.3, 2.21, 2.24; design.md «Sicherheit»). Jede Abfrage für einen Benutzer – Direktzugriff,
 * Liste, Zähler, Paging – hängt das Prädikat an; ein unsichtbares Dokument fehlt so überall.
 *
 * @param bedingung HQL-Bedingung, {@code "1 = 1"} bei uneingeschränkter Sicht
 * @param parameter benannte Parameter der Bedingung
 */
record VertraulichkeitFilter(String bedingung, Map<String, Object> parameter) {

    VertraulichkeitFilter {
        parameter = Map.copyOf(parameter);
    }

    static VertraulichkeitFilter fuer(Sichtregel regel) {
        Map<String, Object> parameter = new HashMap<>();
        if (regel.uneingeschraenkt()) {
            return new VertraulichkeitFilter("1 = 1", parameter);
        }
        // Unbestätigte Art wirkt als PERSONAL (Req 2.21, Fussnote ², design.md «Sicherheit»)
        String unbestaetigt = switch (regel.unbestaetigt()) {
            case ALLE -> "bestaetigt is null";
            case EIGENE -> {
                parameter.put("benutzer", regel.benutzer());
                yield "(bestaetigt is null and (personBenutzer = :benutzer or erstelltVon = :benutzer))";
            }
            case KEINE -> "1 = 0";
        };
        List<String> stufen = new ArrayList<>();
        if (regel.normal()) {
            stufen.add("vertraulichkeit = 'NORMAL'");
        }
        if (regel.intern()) {
            stufen.add("vertraulichkeit = 'INTERN'");
        }
        switch (regel.personal()) {
            case ALLE -> stufen.add("vertraulichkeit = 'PERSONAL'");
            case NUR_EIGENE -> {
                stufen.add("(vertraulichkeit = 'PERSONAL' and personBenutzer = :benutzer)");
                parameter.put("benutzer", regel.benutzer());
            }
            case KEINE -> {
                // keine bestätigten PERSONAL-Dokumente
            }
        }
        String bestaetigt = stufen.isEmpty() ? "1 = 0"
                : "(bestaetigt is not null and (" + String.join(" or ", stufen) + "))";
        return new VertraulichkeitFilter("(" + unbestaetigt + " or " + bestaetigt + ")", parameter);
    }

    /** Verknüpft das Prädikat mit einer weiteren Bedingung und deren Parametern. */
    VertraulichkeitFilter und(String weitere, Map<String, Object> weitereParameter) {
        Map<String, Object> alle = new HashMap<>(parameter);
        alle.putAll(weitereParameter);
        return new VertraulichkeitFilter(bedingung + " and " + weitere, alle);
    }
}
