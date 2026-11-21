package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.wissen.application.WissenLesenRepository;
import ch.urinet.archiv.wissen.application.WissensSuchePort;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PanacheWissensSuche implements WissensSuchePort {
    private static final String FELDER = "s.id, s.typ, s.pfad, s.titel, s.beschreibung, s.status, s.quelle_pruefen, s.version, s.vertraulichkeit";
    private static final String TEXT = "left(s.titel || ' – ' || s.typ || chr(10) || s.inhalt, :zeichen)";
    private final Instance<EntityManager> em;

    public PanacheWissensSuche(Instance<EntityManager> em) { this.em = em; }

    @Override
    public List<Treffer> volltext(String frage, Filter filter, int groesse) {
        // Ingest-Kontext (ohne Sichtregel): Kernaussagen mit ODER verknüpfen, sonst findet UND kaum eine Seite (Req 5.4)
        boolean ingest = filter.regel() == null;
        String suchtext = ingest ? JdbcWissenIngest.oderSuche(frage) : frage;
        if (suchtext.isEmpty()) return List.of();
        String tsquery = ingest ? "websearch_to_tsquery('german', :frage)" : "plainto_tsquery('german', :frage)";
        return lesen("select " + FELDER + ", " + TEXT + " from wissensseite s where %s"
                + " and s.inhalt_tsv @@ " + tsquery
                + " order by ts_rank_cd(s.inhalt_tsv, " + tsquery + ") desc, s.id limit :groesse",
                filter, Map.of("frage", suchtext, "groesse", groesse, "zeichen", HybridSuche.TEXT_ZEICHEN));
    }

    @Override
    public List<Treffer> vektor(float[] frage, Filter filter, int groesse) {
        em.get().createNativeQuery("set local hnsw.ef_search = 100").executeUpdate();
        em.get().createNativeQuery("set local hnsw.iterative_scan = 'strict_order'").executeUpdate();
        return lesen("select " + FELDER + ", left(c.text, :zeichen) from wissensseite_chunk c"
                + " join wissensseite s on s.id = c.seite_id and s.version = c.version where %s"
                + " and c.embedding is not null order by c.embedding OPERATOR(public.<=>)"
                + " cast(:frage as public.halfvec), c.id limit :groesse", filter,
                Map.of("frage", Vektortext.text(frage), "groesse", groesse, "zeichen", HybridSuche.TEXT_ZEICHEN));
    }

    @Override
    public List<Treffer> sichtbar(List<UUID> ids, Filter filter) {
        if (ids.isEmpty()) return List.of();
        return lesen("select " + FELDER + ", " + TEXT + " from wissensseite s where %s and s.id in (:ids)",
                filter, Map.of("ids", ids, "zeichen", HybridSuche.TEXT_ZEICHEN));
    }

    private List<Treffer> lesen(String sql, Filter filter, Map<String, Object> werte) {
        if (filter.erlaubteSeiten() != null && filter.erlaubteSeiten().isEmpty()) return List.of();
        var bedingungen = new StringBuilder();
        var parameter = new HashMap<String, Object>();
        if (filter.regel() != null) {
            var sicht = WissenSichtbarkeitSql.aktuell("s", filter.regel());
            bedingungen.append(sicht.bedingung());
            parameter.putAll(sicht.parameter());
        } else {
            bedingungen.append("s.id in (:erlaubteSeiten)");
            parameter.put("erlaubteSeiten", filter.erlaubteSeiten());
        }
        if (filter.regel() != null && filter.erlaubteSeiten() != null) {
            bedingungen.append(" and s.id in (:erlaubteSeiten)");
            parameter.put("erlaubteSeiten", filter.erlaubteSeiten());
        }
        if (filter.typ() != null) {
            bedingungen.append(" and s.typ = :typ");
            parameter.put("typ", filter.typ().datenbankwert());
        }
        if (filter.tag() != null) {
            bedingungen.append(" and :tag = any(s.tags)");
            parameter.put("tag", filter.tag());
        }
        if (filter.dokumentId() != null) {
            bedingungen.append(" and exists (select 1 from wissensseite_quelle q"
                    + " where q.seite_id = s.id and q.dokument_id = :dokumentId)");
            parameter.put("dokumentId", filter.dokumentId());
        }
        parameter.putAll(werte);
        var query = em.get().createNativeQuery(sql.formatted(bedingungen));
        parameter.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new Treffer(new WissenLesenRepository.IndexZeile((UUID) row[0],
                Seitentyp.vonDatenbankwert((String) row[1])
                        .orElseThrow(() -> new IllegalStateException("Ungültiger Typ der Wissensseite")),
                (String) row[2], (String) row[3], (String) row[4], (String) row[5], (Boolean) row[6],
                Wissensseite.Vertraulichkeit.valueOf((String) row[8])),
                ((Number) row[7]).intValue(), (String) row[9])).toList();
    }
}
