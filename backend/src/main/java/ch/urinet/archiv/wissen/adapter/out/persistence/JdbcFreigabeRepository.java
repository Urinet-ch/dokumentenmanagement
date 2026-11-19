package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.FreigabeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcFreigabeRepository implements FreigabeRepository {
    private final Instance<EntityManager> em;

    public JdbcFreigabeRepository(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public Optional<Aenderung> sperren(UUID entwurfId, int nummer) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery("""
                select a.id, a.seite_id, e.status, e.anlass, a.status, a.etag
                from wissens_entwurf e join wissens_aenderung a on a.entwurf_id = e.id
                where e.id = :entwurf and a.nr = :nummer
                for update of e, a
                """).setParameter("entwurf", entwurfId).setParameter("nummer", nummer).getResultList();
        return zeilen.stream().findFirst().map(z -> new Aenderung(((Number) z[0]).longValue(),
                z[1] == null ? null : (UUID) z[1], (String) z[2], (String) z[3], (String) z[4],
                ((Number) z[5]).intValue()));
    }

    @Override
    public void verwerfen(long aenderungId, int etag, String benutzer, String grund) {
        int geaendert = em.get().createNativeQuery("""
                update wissens_aenderung
                set status = 'VERWORFEN', entschieden_von = :benutzer, entschieden_am = clock_timestamp(),
                    grund = :grund, etag = etag + 1
                where id = :id and status = 'OFFEN' and etag = :etag
                """).setParameter("benutzer", benutzer).setParameter("grund", grund)
                .setParameter("id", aenderungId).setParameter("etag", etag).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrte Wissensänderung konnte nicht verworfen werden");
        }
    }

    @Override
    public boolean entwurfAbschliessen(UUID entwurfId) {
        return em.get().createNativeQuery("""
                update wissens_entwurf e set status = 'ERLEDIGT', abgeschlossen = clock_timestamp()
                where e.id = :id and e.status = 'OFFEN'
                  and not exists (select 1 from wissens_aenderung a where a.entwurf_id = e.id
                                  and a.status in ('OFFEN', 'VERALTET'))
                """).setParameter("id", entwurfId).executeUpdate() == 1;
    }

    @Override
    public void abschlussProtokollieren(UUID entwurfId) {
        em.get().createNativeQuery("""
                insert into wissens_log (zeit, aktion, akteur, seiten, dokument_id, entwurf_id, angaben)
                select clock_timestamp(), e.anlass,
                       case when e.anlass = 'INGEST' then 'process:wissen-ingest'
                            else 'human:' || e.erstellt_von end,
                       array(select a.seite_id from wissens_aenderung a
                             where a.entwurf_id = e.id and a.status = 'ANGENOMMEN'
                               and a.seite_id is not null order by a.nr),
                       e.dokument_id, e.id,
                       case when e.anlass = 'ANALYSE'
                            then jsonb_build_object('vorschlagender', e.erstellt_von) else null end
                from wissens_entwurf e
                where e.id = :id and (e.anlass = 'INGEST' or
                      (e.anlass = 'ANALYSE' and exists (
                          select 1 from wissens_aenderung a
                          where a.entwurf_id = e.id and a.status = 'ANGENOMMEN')))
                """).setParameter("id", entwurfId).executeUpdate();
    }
}
