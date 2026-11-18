package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenSichtbarkeitRepository;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PanacheWissenSichtbarkeitRepository implements WissenSichtbarkeitRepository {

    private final Instance<EntityManager> em;

    public PanacheWissenSichtbarkeitRepository(Instance<EntityManager> em) {
        this.em = em;
    }

    private EntityManager entityManager() {
        return em.get();
    }

    @Override
    public List<UUID> sichtbareSeiten(Sichtregel regel) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell("s", regel);
        Query query = entityManager().createNativeQuery("select s.id from wissensseite s where " + sicht.bedingung()
                + " order by s.pfad");
        sicht.parameter().forEach(query::setParameter);
        return uuidZeilen(query);
    }

    @Override
    public boolean aktuelleSeiteSichtbar(UUID seiteId, Sichtregel regel) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell("s", regel);
        Query query = entityManager().createNativeQuery("select exists (select 1 from wissensseite s where s.id = "
                + ":seiteId and " + sicht.bedingung() + ")");
        query.setParameter("seiteId", seiteId);
        sicht.parameter().forEach(query::setParameter);
        return Boolean.TRUE.equals(query.getSingleResult());
    }

    @Override
    public boolean versionSichtbar(UUID seiteId, int nummer, Sichtregel regel) {
        WissenSichtbarkeitSql.Ausdruck aktuelle = WissenSichtbarkeitSql.aktuell("s", regel);
        WissenSichtbarkeitSql.Ausdruck version = WissenSichtbarkeitSql.version("v", regel);
        Query query = entityManager().createNativeQuery("""
                select exists (
                    select 1
                    from wissensseite s
                    join wissensseite_version v on v.seite_id = s.id
                    where s.id = :seiteId and v.version = :version
                      and %s and %s
                )
                """.formatted(aktuelle.bedingung(), version.bedingung()));
        query.setParameter("seiteId", seiteId);
        query.setParameter("version", nummer);
        WissenSichtbarkeitSql.parameter(aktuelle, version).forEach(query::setParameter);
        return Boolean.TRUE.equals(query.getSingleResult());
    }

    private static List<UUID> uuidZeilen(Query query) {
        @SuppressWarnings("unchecked")
        List<Object> zeilen = query.getResultList();
        return zeilen.stream().map(PanacheWissenSichtbarkeitRepository::uuid).toList();
    }

    private static UUID uuid(Object wert) {
        return wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }
}
