package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WebseitenfreigabePort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWebseitenfreigabe implements WebseitenfreigabePort {
    private final Instance<EntityManager> em;

    public JdbcWebseitenfreigabe(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public Optional<Stand> lesen(UUID seiteId, boolean sperren) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenWebseiteSql.freigabefaehig("s");
        Query abfrage = em.get().createNativeQuery("""
                select s.version, s.quellen_revision, s.fuer_webseite,
                    %s,
                    coalesce((with recursive erreichbar(id) as (
                                  select s.id
                                  union
                                  select q.quell_seite_id from erreichbar e
                                  join wissensseite_quelle q on q.seite_id = e.id
                                  where q.quell_seite_id is not null
                              ), dokumente(id) as (
                                  select h.dokument_id from wissensseite_dokument h where h.seite_id = s.id
                                  union
                                  select q.dokument_id from erreichbar e
                                  join wissensseite_quelle q on q.seite_id = e.id
                                  where q.dokument_id is not null
                              )
                              select case max(case d.zusatz ->> 'zugriff'
                                  when 'oeffentlich' then 0 when 'mitglieder' then 1
                                  when 'vorstand' then 2 else 3 end)
                              when 0 then 'oeffentlich' when 1 then 'mitglieder'
                              when 2 then 'vorstand' else 'unbekannt' end
                              from dokumente h left join dokument d on d.id = h.id), 'unbekannt')
                from wissensseite s where s.id = :id
                %s
                """.formatted(sicht.bedingung(), sperren ? "for update of s" : ""));
        abfrage.setParameter("id", seiteId);
        sicht.parameter().forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        return zeilen.stream().findFirst().map(z -> new Stand(((Number) z[0]).intValue(),
                ((Number) z[1]).longValue(), (boolean) z[2], (boolean) z[3], (String) z[4]));
    }

    @Override
    public void setzen(UUID seiteId, Stand stand, boolean freigeben, String benutzer) {
        int geaendert = em.get().createNativeQuery("""
                update wissensseite set fuer_webseite = :freigeben,
                    fuer_webseite_von = :akteur, fuer_webseite_am = now(),
                    quellen_revision = quellen_revision + 1
                where id = :id and version = :version and quellen_revision = :revision
                  and fuer_webseite <> :freigeben
                """).setParameter("id", seiteId).setParameter("version", stand.version())
                .setParameter("revision", stand.quellenRevision()).setParameter("freigeben", freigeben)
                .setParameter("akteur", "human:" + benutzer).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrte Webseitenfreigabe konnte nicht geändert werden");
        }
        em.get().createNativeQuery("""
                insert into wissens_log (zeit, aktion, akteur, seiten, angaben)
                values (now(), 'UPDATE', :akteur, array[cast(:id as uuid)],
                        jsonb_build_object('webseitenfreigabe', :freigeben, 'webseitenfreigabe_aktion', :aktion))
                """).setParameter("akteur", "human:" + benutzer).setParameter("id", seiteId)
                .setParameter("freigeben", freigeben)
                .setParameter("aktion", freigeben ? "WISSEN_WEBSEITE_FREIGEGEBEN" : "WISSEN_WEBSEITE_ENTZOGEN")
                .executeUpdate();
    }
}
