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

    // Lesestrecke der aktuellen Seite (fail-closed). Die Hülle für die Dokumentprüfung ist die gespeicherte Hülle
    // vereinigt mit der live aus wissensseite_quelle berechneten (transitiv über Quellseiten, zyklensicher über
    // UNION). Die Untergrenze ist NORMAL nur, wenn Mindeststufe und gespeicherte wirksame Stufe NORMAL sind, die Seite
    // mit Quelleneinträgen gespeicherte Hüllenzeilen hat und jedes live erreichbare Dokument in der gespeicherten
    // Hülle steht. Sonst ist die gespeicherte Stufe veraltet (Freigabe oder Quelländerung ohne Neuberechnung), und die
    // Seite gilt als INTERN.
    private static final String AKTUELLE = """
            with recursive erreichbar(start_id, seite_id) as (
                select s.id, s.id from wissensseite s %1$s
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
                select d.seite_id, d.dokument_id from wissensseite_dokument d
                where d.seite_id in (select start_id from erreichbar)
                union
                select l.seite_id, l.dokument_id from live l
            )
            select s.id,
                   case when s.vertraulichkeit_mindest = 'NORMAL' and s.vertraulichkeit = 'NORMAL'
                             and (exists (select 1 from wissensseite_dokument d where d.seite_id = s.id)
                                  or not exists (select 1 from wissensseite_quelle q where q.seite_id = s.id))
                             and not exists (select 1 from live l where l.seite_id = s.id
                                             and not exists (select 1 from wissensseite_dokument d
                                                             where d.seite_id = l.seite_id
                                                               and d.dokument_id = l.dokument_id))
                        then 'NORMAL' else 'INTERN' end,
                   h.dokument_id
            from wissensseite s
            left join huelle h on h.seite_id = s.id
            %1$s
            order by s.id
            """;

    private static final String AKTUELLE_SEITEN = AKTUELLE.formatted("");

    private static final String AKTUELLE_SEITE = AKTUELLE.formatted("where s.id = :seiteId");

    private static final String VERSION = """
            select v.seite_id,
                   case when coalesce(v.daten ->> 'vertraulichkeit_mindest', 'INTERN') <> 'NORMAL'
                              or coalesce(s.vertraulichkeit_mindest, 'INTERN') <> 'NORMAL'
                        then 'INTERN' else 'NORMAL' end,
                   h.dokument_id
            from wissensseite_version v
            join wissensseite s on s.id = v.seite_id
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
