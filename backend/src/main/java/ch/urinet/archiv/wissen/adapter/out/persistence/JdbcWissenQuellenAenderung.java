package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenQuellenAenderungPort;
import ch.urinet.archiv.wissen.application.QuellenPruefungPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenQuellenAenderung implements WissenQuellenAenderungPort, QuellenPruefungPort {
    private final Instance<EntityManager> em;

    public JdbcWissenQuellenAenderung(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public void quellePruefenSetzen(Set<UUID> seiten) {
        for (UUID seite : seiten) {
            em.get().createNativeQuery("update wissensseite set quelle_pruefen = true, "
                            + "quellen_revision = quellen_revision + 1 where id = :id")
                    .setParameter("id", seite).executeUpdate();
        }
    }

    @Override
    public Optional<SeitenStand> seiteSperren(UUID seiteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery("""
                select version, quellen_revision, quelle_pruefen
                from wissensseite where id = :id for update
                """).setParameter("id", seiteId).getResultList();
        return zeilen.stream().findFirst().map(z ->
                new SeitenStand(((Number) z[0]).intValue(), ((Number) z[1]).longValue(), (boolean) z[2]));
    }

    @Override
    public void bestaetigen(UUID seiteId, SeitenStand stand, String benutzer) {
        int geaendert = em.get().createNativeQuery("""
                update wissensseite set quelle_pruefen = false, quellen_revision = quellen_revision + 1
                where id = :id and version = :version and quellen_revision = :revision and quelle_pruefen
                """).setParameter("id", seiteId).setParameter("version", stand.version())
                .setParameter("revision", stand.quellenRevision()).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrter Quellenhinweis konnte nicht bestätigt werden");
        }
        em.get().createNativeQuery("""
                insert into wissens_log (zeit, aktion, akteur, seiten, angaben)
                values (now(), 'UPDATE', :akteur, array[cast(:id as uuid)],
                        jsonb_build_object('quelle_geprueft', true))
                """).setParameter("akteur", "human:" + benutzer).setParameter("id", seiteId).executeUpdate();
    }
}
