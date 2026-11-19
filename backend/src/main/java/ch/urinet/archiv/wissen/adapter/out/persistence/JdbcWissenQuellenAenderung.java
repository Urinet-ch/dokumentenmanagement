package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenQuellenAenderungPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenQuellenAenderung implements WissenQuellenAenderungPort {
    private final Instance<EntityManager> em;

    public JdbcWissenQuellenAenderung(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public void quellePruefenSetzen(Set<UUID> seiten) {
        for (UUID seite : seiten) {
            em.get().createNativeQuery("update wissensseite set quelle_pruefen = true where id = :id")
                    .setParameter("id", seite).executeUpdate();
        }
    }
}
