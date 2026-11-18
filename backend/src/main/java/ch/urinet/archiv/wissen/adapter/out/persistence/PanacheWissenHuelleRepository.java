package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenHuelleRepository;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class PanacheWissenHuelleRepository implements WissenHuelleRepository {

    private final Instance<EntityManager> em;

    public PanacheWissenHuelleRepository(Instance<EntityManager> em) {
        this.em = em;
    }

    private EntityManager entityManager() {
        return em.get();
    }

    @Override
    public void sperren() {
        entityManager().createNativeQuery(
                        "select 1 from pg_advisory_xact_lock(hashtextextended(current_schema() || ':wissen-huelle', 0))")
                .getResultList();
    }

    @Override
    public WissensGraph graphLaden() {
        Map<UUID, Set<UUID>> dokumenteJeSeite = new HashMap<>();
        Map<UUID, Set<UUID>> quellseitenJeSeite = new HashMap<>();
        for (Object[] zeile : zeilen("select seite_id, dokument_id, quell_seite_id from wissensseite_quelle")) {
            UUID seiteId = uuid(zeile[0]);
            if (zeile[1] != null) {
                dokumenteJeSeite.computeIfAbsent(seiteId, ignored -> new HashSet<>()).add(uuid(zeile[1]));
            }
            if (zeile[2] != null) {
                quellseitenJeSeite.computeIfAbsent(seiteId, ignored -> new HashSet<>()).add(uuid(zeile[2]));
            }
        }

        Map<UUID, WissensStufe> mindeststufen = new HashMap<>();
        Map<UUID, String> pfade = new HashMap<>();
        for (Object[] zeile : zeilen(
                "select id, pfad, vertraulichkeit_mindest from wissensseite")) {
            UUID seiteId = uuid(zeile[0]);
            pfade.put(seiteId, (String) zeile[1]);
            mindeststufen.put(seiteId, WissensStufe.valueOf((String) zeile[2]));
        }

        Map<UUID, WissensStufe> dokumentStufen = new HashMap<>();
        String dokumentStufenSql = """
                select distinct d.id,
                    case when coalesce(d.bestaetigt is null or d.vertraulichkeit <> 'NORMAL', true)
                         then 'INTERN' else 'NORMAL' end
                from dokument d
                join wissensseite_quelle q on q.dokument_id = d.id
                """;
        for (Object[] zeile : zeilen(dokumentStufenSql)) {
            dokumentStufen.put(uuid(zeile[0]), WissensStufe.valueOf((String) zeile[1]));
        }

        return new WissensGraph(dokumenteJeSeite, quellseitenJeSeite, dokumentStufen, mindeststufen, pfade);
    }

    @Override
    public Set<UUID> seitenMitDokument(UUID dokumentId) {
        List<?> ids = entityManager().createNativeQuery(
                        "select seite_id from wissensseite_dokument where dokument_id = :dokumentId")
                .setParameter("dokumentId", dokumentId)
                .getResultList();
        Set<UUID> seiten = new HashSet<>();
        ids.forEach(id -> seiten.add(uuid(id)));
        return Set.copyOf(seiten);
    }

    @Override
    public void aktuelleHuelleSetzen(UUID seiteId, Set<UUID> dokumente) {
        entityManager().createNativeQuery("delete from wissensseite_dokument where seite_id = :seiteId")
                .setParameter("seiteId", seiteId)
                .executeUpdate();
        for (UUID dokumentId : dokumente) {
            entityManager().createNativeQuery(
                            "insert into wissensseite_dokument (seite_id, dokument_id) values (:seiteId, :dokumentId)")
                    .setParameter("seiteId", seiteId)
                    .setParameter("dokumentId", dokumentId)
                    .executeUpdate();
        }
    }

    @Override
    public void vertraulichkeitSetzen(UUID seiteId, WissensStufe stufe) {
        entityManager().createNativeQuery("update wissensseite set vertraulichkeit = :stufe where id = :seiteId")
                .setParameter("stufe", stufe.name())
                .setParameter("seiteId", seiteId)
                .executeUpdate();
    }

    @Override
    public void versionsHuelleEinfuegen(UUID seiteId, int version, Set<UUID> dokumente) {
        for (UUID dokumentId : dokumente) {
            entityManager().createNativeQuery("""
                            insert into wissensseite_version_dokument (seite_id, version, dokument_id)
                            values (:seiteId, :version, :dokumentId)
                            """)
                    .setParameter("seiteId", seiteId)
                    .setParameter("version", version)
                    .setParameter("dokumentId", dokumentId)
                    .executeUpdate();
        }
    }

    private List<Object[]> zeilen(String sql) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = entityManager().createNativeQuery(sql).getResultList();
        return new ArrayList<>(zeilen);
    }

    private static UUID uuid(Object wert) {
        return wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }
}
