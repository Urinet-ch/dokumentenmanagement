package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.FesterFilter;
import ch.urinet.archiv.sicherheit.application.SichtregelSql;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Übersetzt die {@link Sichtregel} in ein HQL-Prädikat auf {@link DokumentEintrag} (ohne Alias, für Panache) oder in
 * ein SQL-Prädikat auf {@code dokument} mit Alias (Suche, Aufgabe 4.5) mit genau den benutzten Parametern (Req 2.3,
 * 2.21, 2.24; design.md «Sicherheit»). Jede Abfrage für einen Benutzer – Direktzugriff, Liste, Suche, Zähler, Paging –
 * hängt das Prädikat an; ein unsichtbares Dokument fehlt so überall. Beide Varianten entstehen aus derselben Logik.
 *
 * @param bedingung HQL-Bedingung, {@code "1 = 1"} bei uneingeschränkter Sicht
 * @param parameter benannte Parameter der Bedingung
 */
record VertraulichkeitFilter(String bedingung, Map<String, Object> parameter) {

    VertraulichkeitFilter {
        parameter = Map.copyOf(parameter);
    }

    /** Spaltennamen je Sprache: HQL-Attribute der Entität oder SQL-Spalten mit Alias. */
    private record Spalten(String bestaetigt, String erstelltVon, String vertraulichkeit, String personBenutzer) {
    }

    private static final Spalten HQL = new Spalten("bestaetigt", "erstelltVon", "vertraulichkeit", "personBenutzer");

    /** HQL-Prädikat auf {@link DokumentEintrag} ohne Alias. */
    static VertraulichkeitFilter fuer(Sichtregel regel) {
        if (regel.webseite() != null) {
            // der feste Filter der Webseiten-Schnittstelle ist nur als SQL gebaut: lieber Fehler als eine zu weite Sicht
            throw new IllegalStateException("Sichtregel der Webseite nur für SQL-Abfragen");
        }
        return bauen(regel, HQL);
    }

    /** SQL-Prädikat auf {@code dokument} unter dem Alias {@code alias} (fester Bezeichner, nie Benutzereingabe). */
    static VertraulichkeitFilter sql(Sichtregel regel, String alias) {
        SichtregelSql.Ausdruck ausdruck = SichtregelSql.ohneFestenFilter(regel, alias);
        VertraulichkeitFilter sicht = new VertraulichkeitFilter(ausdruck.bedingung(), ausdruck.parameter());
        return regel.webseite() == null ? sicht : webseite(sicht, regel.webseite(), alias);
    }

    /**
     * Webseiten-Schnittstelle: die Sicht der Regel und dazu der feste Filter ({@link FesterFilter}) samt Parameter
     * {@code stufen}; Jahr und Ausgabe als gebundene Werte.
     */
    private static VertraulichkeitFilter webseite(VertraulichkeitFilter sicht, Sichtregel.Webseite w, String alias) {
        Map<String, Object> parameter = new HashMap<>(sicht.parameter());
        StringBuilder bedingung = new StringBuilder(sicht.bedingung()).append(" and ")
                .append(FesterFilter.bedingung(alias));
        parameter.put(FesterFilter.PARAMETER, w.stufen().toArray(String[]::new));
        if (w.jahr() != null) {
            bedingung.append(" and ").append(alias).append(".zusatz ->> 'jahr' = :webJahr");
            parameter.put("webJahr", w.jahr());
        }
        if (w.ausgabe() != null) {
            bedingung.append(" and ").append(alias).append(".zusatz ->> 'ausgabe' = :webAusgabe");
            parameter.put("webAusgabe", w.ausgabe());
        }
        return new VertraulichkeitFilter("(" + bedingung + ")", parameter);
    }

    private static VertraulichkeitFilter bauen(Sichtregel regel, Spalten s) {
        Map<String, Object> parameter = new HashMap<>();
        if (regel.uneingeschraenkt()) {
            return new VertraulichkeitFilter("1 = 1", parameter);
        }
        // Unbestätigte Art wirkt als PERSONAL (Req 2.21, Fussnote ², design.md «Sicherheit»)
        String unbestaetigt = switch (regel.unbestaetigt()) {
            case ALLE -> s.bestaetigt() + " is null";
            case ERFASSTE -> {
                parameter.put("benutzer", regel.benutzer());
                yield "(" + s.bestaetigt() + " is null and " + s.erstelltVon() + " = :benutzer)";
            }
            case KEINE -> "1 = 0";
        };
        List<String> stufen = new ArrayList<>();
        if (regel.normal()) {
            stufen.add(s.vertraulichkeit() + " = 'NORMAL'");
        }
        if (regel.intern()) {
            stufen.add(s.vertraulichkeit() + " = 'INTERN'");
        }
        switch (regel.personal()) {
            case ALLE -> stufen.add(s.vertraulichkeit() + " = 'PERSONAL'");
            case NUR_EIGENE -> {
                stufen.add("(" + s.vertraulichkeit() + " = 'PERSONAL' and " + s.personBenutzer() + " = :benutzer)");
                parameter.put("benutzer", regel.benutzer());
            }
            case KEINE -> {
                // keine bestätigten PERSONAL-Dokumente
            }
        }
        String bestaetigt = stufen.isEmpty() ? "1 = 0"
                : "(" + s.bestaetigt() + " is not null and (" + String.join(" or ", stufen) + "))";
        return new VertraulichkeitFilter("(" + unbestaetigt + " or " + bestaetigt + ")", parameter);
    }

    /** Verknüpft das Prädikat mit einer weiteren Bedingung und deren Parametern. */
    VertraulichkeitFilter und(String weitere, Map<String, Object> weitereParameter) {
        Map<String, Object> alle = new HashMap<>(parameter);
        alle.putAll(weitereParameter);
        return new VertraulichkeitFilter(bedingung + " and " + weitere, alle);
    }
}
