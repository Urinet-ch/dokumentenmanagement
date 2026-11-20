package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenEntwurfLesenRepository;
import ch.urinet.archiv.wissen.application.WissenEntwurfLesen.Entscheidung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Kernaussage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenEntwurfLesen implements WissenEntwurfLesenRepository {
    private static final String KOPF = """
            id, anlass, status, erstellt_von, erstellt, dokument_id, unvollstaendig, hinweis
            """;
    private static final TypeReference<Map<String, Object>> OBJEKT = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> OBJEKTE = new TypeReference<>() { };
    private static final TypeReference<List<Kernaussage>> AUSSAGEN = new TypeReference<>() { };
    private final Instance<EntityManager> em;
    private final ObjectMapper json;

    public JdbcWissenEntwurfLesen(Instance<EntityManager> em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    @Override
    public List<Kopf> liste(String status, String eigenerAnalyseVorschlag) {
        String filter = " where true";
        if (status != null) {
            filter += " and status = :status";
        }
        if (eigenerAnalyseVorschlag != null) {
            filter += " and anlass = 'ANALYSE' and erstellt_von = :erstelltVon";
        }
        Query query = em.get().createNativeQuery("select " + KOPF + " from wissens_entwurf" + filter
                + " order by erstellt desc, id desc");
        if (status != null) {
            query.setParameter("status", status);
        }
        if (eigenerAnalyseVorschlag != null) {
            query.setParameter("erstelltVon", eigenerAnalyseVorschlag);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(JdbcWissenEntwurfLesen::kopf).toList();
    }

    @Override
    public Optional<Kopf> kopf(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("select " + KOPF + " from wissens_entwurf where id = :id")
                .setParameter("id", id).getResultList();
        return rows.stream().findFirst().map(JdbcWissenEntwurfLesen::kopf);
    }

    @Override
    public List<Kernaussage> kernaussagen(UUID id) {
        @SuppressWarnings("unchecked")
        List<String> rows = em.get().createNativeQuery(
                        "select cast(kernaussagen as text) from wissens_entwurf where id = :id")
                .setParameter("id", id).getResultList();
        return rows.isEmpty() || rows.getFirst() == null ? List.of() : lesen(rows.getFirst(), AUSSAGEN);
    }

    @Override
    public List<Aenderung> aenderungen(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("""
                select id, nr, aktion, seite_id, pfad, basis_version, cast(daten as text), inhalt,
                       cast(diff as text), begruendung, cast(widersprueche as text), cast(entfernte_aussagen as text),
                       cast(befunde as text), cast(hinweise as text), vertraulichkeit_kontext, status,
                       entschieden_von, entschieden_am, grund, ergebnis_version, etag
                from wissens_aenderung where entwurf_id = :id order by nr
                """).setParameter("id", id).getResultList();
        return rows.stream().map(row -> new Aenderung(((Number) row[0]).longValue(), ((Number) row[1]).intValue(),
                (String) row[2], uuid(row[3]), (String) row[4], integer(row[5]), lesen((String) row[6], OBJEKT),
                (String) row[7], lesen((String) row[8], OBJEKT), (String) row[9], liste(row[10]),
                liste(row[11]), liste(row[12]), liste(row[13]), (String) row[14], (String) row[15],
                (String) row[16], zeit(row[17]), (String) row[18], integer(row[19]), ((Number) row[20]).intValue()))
                .toList();
    }

    @Override
    public List<Entscheidung> entscheidungen(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("""
                select nr, status, grund from wissens_aenderung where entwurf_id = :id order by nr
                """).setParameter("id", id).getResultList();
        return rows.stream().map(row -> new Entscheidung(((Number) row[0]).intValue(), (String) row[1],
                (String) row[2])).toList();
    }

    private List<Map<String, Object>> liste(Object wert) {
        return wert == null ? List.of() : lesen((String) wert, OBJEKTE);
    }

    private <T> T lesen(String wert, TypeReference<T> typ) {
        try {
            return json.readValue(wert, typ);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gespeicherte Entwurfsdaten sind nicht lesbar", e);
        }
    }

    private static Kopf kopf(Object[] row) {
        return new Kopf(uuid(row[0]), (String) row[1], (String) row[2], (String) row[3], zeit(row[4]),
                uuid(row[5]), Boolean.TRUE.equals(row[6]), (String) row[7]);
    }

    private static UUID uuid(Object wert) {
        return wert == null ? null : wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }

    private static Integer integer(Object wert) {
        return wert == null ? null : ((Number) wert).intValue();
    }

    private static Instant zeit(Object wert) {
        return switch (wert) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> OffsetDateTime.parse(wert.toString()).toInstant();
        };
    }
}
