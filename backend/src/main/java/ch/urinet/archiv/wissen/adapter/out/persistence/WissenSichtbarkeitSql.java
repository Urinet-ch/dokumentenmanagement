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
        String seitenAlias = alias.equals("wi") ? "wii" : "wi";
        String stufenAlias = alias.equals("st") ? "sti" : "st";
        String dokumentAlias = alias.equals("d") ? "dd" : "d";
        SichtregelSql.Ausdruck dokumente = SichtregelSql.dokument(regel, dokumentAlias);
        return new Ausdruck("""
                exists (
                    with recursive erreichbar(start_id, seite_id) as (
                        select %2$s.id, %2$s.id from wissensseite %2$s where %2$s.id = %1$s.id
                        union
                        select e.start_id, q.quell_seite_id
                        from erreichbar e
                        join wissensseite_quelle q on q.seite_id = e.seite_id
                        where q.quell_seite_id is not null
                    ),
                    live(seite_id, dokument_id) as (
                        select distinct e.start_id, q.dokument_id
                        from erreichbar e
                        join wissensseite_quelle q on q.seite_id = e.seite_id
                        where q.dokument_id is not null
                    ),
                    huelle(seite_id, dokument_id) as (
                        select h.seite_id, h.dokument_id
                        from wissensseite_dokument h
                        where h.seite_id = %1$s.id
                        union
                        select l.seite_id, l.dokument_id from live l
                    ),
                    stufe(seite_id, wert) as (
                        select %2$s.id,
                               case when %2$s.vertraulichkeit_mindest = 'NORMAL'
                                          and %2$s.vertraulichkeit = 'NORMAL'
                                          and (exists (select 1 from wissensseite_dokument h
                                                       where h.seite_id = %2$s.id)
                                               or not exists (select 1 from wissensseite_quelle q
                                                              where q.seite_id = %2$s.id))
                                          and not exists (
                                              select 1 from live l
                                              where l.seite_id = %2$s.id
                                                and not exists (
                                                    select 1 from wissensseite_dokument h
                                                    where h.seite_id = %2$s.id
                                                      and h.dokument_id = l.dokument_id
                                                )
                                          )
                                    then 'NORMAL' else 'INTERN' end
                        from wissensseite %2$s
                        where %2$s.id = %1$s.id
                    )
                    select 1
                    from stufe %3$s
                    where %5$s
                      and not exists (
                          select 1
                          from huelle h
                          left join dokument %4$s on %4$s.id = h.dokument_id
                          where h.seite_id = %3$s.seite_id
                            and (%4$s.id is null or not (%6$s))
                      )
                )
                """.formatted(alias, seitenAlias, stufenAlias, dokumentAlias, mindeststufe(stufenAlias + ".wert", regel),
                dokumente.bedingung()),
                dokumente.parameter());
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
