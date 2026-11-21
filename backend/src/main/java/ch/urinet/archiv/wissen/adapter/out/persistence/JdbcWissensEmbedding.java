package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.domain.Chunker;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.wissen.application.WissensEmbeddingPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcWissensEmbedding implements WissensEmbeddingPort {
    private final Instance<EntityManager> em;

    public JdbcWissensEmbedding(Instance<EntityManager> em) { this.em = em; }

    @Override
    public Optional<Stand> laden(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("""
                select id, version, titel, typ, inhalt from wissensseite where id = :id
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Object[] row = rows.getFirst();
        return Optional.of(new Stand((UUID) row[0], ((Number) row[1]).intValue(),
                new Chunker.Quelle((String) row[2], (String) row[3], null, (String) row[4], null, true)));
    }

    @Override
    public boolean ersetzen(UUID id, int version, List<ChunkPort.Eingebettet> chunks) {
        var versionen = em.get().createNativeQuery("select version from wissensseite where id = :id for update")
                .setParameter("id", id).getResultList();
        if (versionen.isEmpty() || ((Number) versionen.getFirst()).intValue() != version) return false;
        if (chunks.stream().anyMatch(c -> c.vektor() == null)
                && !em.get().createNativeQuery("""
                    select 1 from wissensseite_chunk where seite_id = :id and version = :version
                    and embedding is not null limit 1
                    """).setParameter("id", id).setParameter("version", version).getResultList().isEmpty()) {
            return false;
        }
        em.get().createNativeQuery("delete from wissensseite_chunk where seite_id = :id")
                .setParameter("id", id).executeUpdate();
        for (var chunk : chunks) {
            em.get().createNativeQuery("""
                    insert into wissensseite_chunk(seite_id, version, nr, text, embedding)
                    values (:id, :version, :nr, :text, cast(:vektor as public.halfvec))
                    """).setParameter("id", id).setParameter("version", version)
                    .setParameter("nr", chunk.chunk().nr()).setParameter("text", chunk.chunk().text())
                    .setParameter("vektor", chunk.vektor() == null ? null : Vektortext.text(chunk.vektor()))
                    .executeUpdate();
        }
        return true;
    }

    @Override
    public List<SeitenVersion> nachberechnen(Optional<UUID> nach, int groesse) {
        var query = em.get().createNativeQuery("select id, version from wissensseite"
                + (nach.isPresent() ? " where id > :nach" : "") + " order by id limit :groesse");
        nach.ifPresent(id -> query.setParameter("nach", id));
        query.setParameter("groesse", groesse);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new SeitenVersion((UUID) row[0], ((Number) row[1]).intValue())).toList();
    }
}
