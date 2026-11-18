package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.SichtregelSql;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class WissenSichtbarkeitSql {

    record Ausdruck(String bedingung, Map<String, Object> parameter) {
        Ausdruck {
            parameter = Map.copyOf(parameter);
        }
    }

    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    private WissenSichtbarkeitSql() {
    }

    static Ausdruck aktuell(String alias, Sichtregel regel) {
        pruefen(alias);
        SichtregelSql.Ausdruck dokumente = SichtregelSql.dokument(regel, "d");
        return new Ausdruck("""
                (%s) and not exists (
                    select 1 from wissensseite_dokument h join dokument d on d.id = h.dokument_id
                    where h.seite_id = %s.id and not (%s)
                )
                """.formatted(mindeststufe(alias + ".vertraulichkeit_mindest", regel), alias,
                dokumente.bedingung()), dokumente.parameter());
    }

    static Ausdruck version(String alias, Sichtregel regel) {
        pruefen(alias);
        SichtregelSql.Ausdruck dokumente = SichtregelSql.dokument(regel, "d");
        return new Ausdruck("""
                (%s)
                and (exists (
                    select 1 from wissensseite_version_dokument vh
                    where vh.seite_id = %s.seite_id and vh.version = %s.version
                ) or %s)
                and not exists (
                    select 1 from wissensseite_version_dokument h join dokument d on d.id = h.dokument_id
                    where h.seite_id = %s.seite_id and h.version = %s.version and not (%s)
                )
                """.formatted(mindeststufe("coalesce(" + alias
                + ".daten ->> 'vertraulichkeit_mindest', 'INTERN')", regel), alias, alias, regel.intern(),
                alias, alias, dokumente.bedingung()), dokumente.parameter());
    }

    private static String mindeststufe(String ausdruck, Sichtregel regel) {
        if (regel.normal() && regel.intern()) {
            return "1 = 1";
        }
        if (regel.normal()) {
            return "coalesce(" + ausdruck + ", 'INTERN') = 'NORMAL'";
        }
        if (regel.intern()) {
            return "coalesce(" + ausdruck + ", 'INTERN') <> 'NORMAL'";
        }
        return "1 = 0";
    }

    private static void pruefen(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Ungültiger Alias");
        }
    }

    static Map<String, Object> parameter(Ausdruck... ausdruecke) {
        Map<String, Object> parameter = new HashMap<>();
        for (Ausdruck ausdruck : ausdruecke) {
            parameter.putAll(ausdruck.parameter());
        }
        return parameter;
    }
}
