package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.FesterFilter;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WissenWebseiteSql {
    private static final Sichtregel WEBSEITE = Sichtregel.fuerWebseite(
            new Sichtregel.Webseite(List.of("oeffentlich"), null, null));

    private WissenWebseiteSql() {
    }

    static WissenSichtbarkeitSql.Ausdruck freigabefaehig(String alias) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell(alias, WEBSEITE);
        Map<String, Object> parameter = new HashMap<>(sicht.parameter());
        parameter.put(FesterFilter.PARAMETER, new String[]{"oeffentlich"});
        return new WissenSichtbarkeitSql.Ausdruck("""
                (%1$s and exists (select 1 from wissensseite_dokument h where h.seite_id = %2$s.id)
                 and not exists (
                     select 1 from wissensseite_dokument h
                     left join dokument d on d.id = h.dokument_id
                     where h.seite_id = %2$s.id and (%3$s) is not true
                 )
                 and not exists (
                     with recursive erreichbar(id) as (
                         select %2$s.id
                         union
                         select q.quell_seite_id from erreichbar e
                         join wissensseite_quelle q on q.seite_id = e.id
                         where q.quell_seite_id is not null
                     )
                     select 1 from erreichbar e
                     join wissensseite quelle on quelle.id = e.id
                     where quelle.typ = 'Analyse' or quelle.vertraulichkeit <> 'NORMAL'
                           or quelle.vertraulichkeit_mindest <> 'NORMAL'
                 ))
                """.formatted(sicht.bedingung(), alias, FesterFilter.bedingung("d")), parameter);
    }
}
