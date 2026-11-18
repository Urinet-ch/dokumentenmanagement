package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenSichtbarkeitRepository;
import ch.urinet.archiv.wissen.application.WissensHuelleZugriff;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class PanacheWissenSichtbarkeitRepository implements WissenSichtbarkeitRepository {

    private static final String AKTUELLE_SEITEN = """
            select s.id, s.vertraulichkeit_mindest, h.dokument_id
            from wissensseite s
            left join wissensseite_dokument h on h.seite_id = s.id
            order by s.id
            """;

    private static final String AKTUELLE_SEITE = """
            select s.id, s.vertraulichkeit_mindest, h.dokument_id
            from wissensseite s
            left join wissensseite_dokument h on h.seite_id = s.id
            where s.id = :seiteId
            """;

    private static final String VERSION = """
            select v.seite_id, coalesce(v.daten ->> 'vertraulichkeit_mindest', 'INTERN'), h.dokument_id
            from wissensseite_version v
            left join wissensseite_version_dokument h
              on h.seite_id = v.seite_id and h.version = v.version
            where v.seite_id = :seiteId and v.version = :version
            """;

    private final Instance<EntityManager> em;

    public PanacheWissenSichtbarkeitRepository(Instance<EntityManager> em) {
        this.em = em;
    }

    private EntityManager entityManager() {
        return em.get();
    }

    @Override
    public List<WissensHuelleZugriff> aktuelleSeiten() {
        return lesen(AKTUELLE_SEITEN, null, null).values().stream().toList();
    }

    @Override
    public Optional<WissensHuelleZugriff> aktuelleSeite(UUID seiteId) {
        return lesen(AKTUELLE_SEITE, seiteId, null).values().stream().findFirst();
    }

    @Override
    public Optional<WissensHuelleZugriff> version(UUID seiteId, int nummer) {
        return lesen(VERSION, seiteId, nummer).values().stream().findFirst();
    }

    private Map<UUID, WissensHuelleZugriff> lesen(String sql, UUID seiteId, Integer version) {
        Query query = entityManager().createNativeQuery(sql);
        if (seiteId != null) {
            query.setParameter("seiteId", seiteId);
        }
        if (version != null) {
            query.setParameter("version", version);
        }
        Map<UUID, Akkumulator> gesammelt = new LinkedHashMap<>();
        for (Object[] zeile : zeilen(query)) {
            UUID id = uuid(zeile[0]);
            WissensStufe mindeststufe = stufe(zeile[1]);
            Akkumulator huelle = gesammelt.computeIfAbsent(id, ignored -> new Akkumulator(mindeststufe));
            if (zeile[2] != null) {
                huelle.dokumente.add(uuid(zeile[2]));
            }
        }
        Map<UUID, WissensHuelleZugriff> result = new LinkedHashMap<>();
        gesammelt.forEach((id, huelle) -> result.put(id,
                new WissensHuelleZugriff(id, huelle.mindeststufe, huelle.dokumente)));
        return Collections.unmodifiableMap(result);
    }

    // Unbekannte Werte gelten fail-closed als INTERN.
    private static WissensStufe stufe(Object wert) {
        return WissensStufe.NORMAL.name().equals(wert) ? WissensStufe.NORMAL : WissensStufe.INTERN;
    }

    private static List<Object[]> zeilen(Query query) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = query.getResultList();
        return new ArrayList<>(zeilen);
    }

    private static UUID uuid(Object wert) {
        return wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }

    private static final class Akkumulator {
        private final WissensStufe mindeststufe;
        private final Set<UUID> dokumente = new LinkedHashSet<>();

        private Akkumulator(WissensStufe mindeststufe) {
            this.mindeststufe = mindeststufe;
        }
    }
}
