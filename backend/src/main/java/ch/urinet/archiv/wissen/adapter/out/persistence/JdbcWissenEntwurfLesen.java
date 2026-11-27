package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenEntwurfLesenRepository;
import ch.urinet.archiv.wissen.application.WissenEntwurfLesen.Entscheidung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Kernaussage;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Fortschritt;
import ch.urinet.archiv.wissen.application.DiffDienst;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.paket.PaketKopf;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenEntwurfLesen implements WissenEntwurfLesenRepository {
    private static final String KOPF = """
            id, anlass, status, erstellt_von, erstellt, dokument_id, unvollstaendig, hinweis,
            paket_sha256, cast(paket_angaben as text)
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
        return rows.stream().map(this::kopf).toList();
    }

    @Override
    public Optional<Kopf> kopf(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("select " + KOPF + " from wissens_entwurf where id = :id")
                .setParameter("id", id).getResultList();
        return rows.stream().findFirst().map(this::kopf);
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
    public Optional<Fortschritt> ingestFortschritt(UUID id) {
        @SuppressWarnings("unchecked")
        List<String> rows = em.get().createNativeQuery("""
                        select cast(ingest_daten as text) from wissens_entwurf
                        where id = :id and anlass = 'INGEST'
                        """).setParameter("id", id).getResultList();
        if (rows.isEmpty() || rows.getFirst() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(rows.getFirst(), Fortschritt.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ingest-Fortschritt nicht lesbar", e);
        }
    }

    @Override
    public List<Aenderung> aenderungen(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("""
                select a.id, a.nr, a.aktion, a.seite_id, a.pfad, a.basis_version, cast(a.daten as text), a.inhalt,
                       cast(a.diff as text), a.begruendung, cast(a.widersprueche as text),
                       cast(a.entfernte_aussagen as text), cast(a.befunde as text), cast(a.hinweise as text),
                       a.vertraulichkeit_kontext, a.status, a.entschieden_von, a.entschieden_am,
                       a.grund, a.ergebnis_version, a.etag, v.inhalt
                from wissens_aenderung a
                left join wissensseite_version v on v.seite_id = a.seite_id and v.version = a.basis_version
                where a.entwurf_id = :id order by a.nr
                """).setParameter("id", id).getResultList();
        return rows.stream().map(row -> new Aenderung(((Number) row[0]).longValue(), ((Number) row[1]).intValue(),
                (String) row[2], uuid(row[3]), (String) row[4], integer(row[5]), lesen((String) row[6], OBJEKT),
                (String) row[7], diff(row), (String) row[9], liste(row[10]),
                liste(row[11]), liste(row[12]), liste(row[13]), (String) row[14], (String) row[15],
                (String) row[16], zeit(row[17]), (String) row[18], integer(row[19]), ((Number) row[20]).intValue()))
                .toList();
    }

    private Map<String, Object> diff(Object[] row) {
        Map<String, Object> diff = new HashMap<>(lesen((String) row[8], OBJEKT));
        if (diff.get("widersprueche") != null && diff.get("entfernteWidersprueche") != null) {
            return diff;
        }
        String basis = (String) row[21];
        if ("AENDERN".equals(row[2]) && basis == null) {
            throw new IllegalStateException("Gespeicherte Entwurfsbasis fehlt");
        }
        String alt = basis == null ? "" : basis;
        String neu = (String) row[7];
        diff.putIfAbsent("widersprueche", new DiffDienst.Feld<>(
                WissensMarkdown.analysieren(alt, WissensMarkdown.MAX_EINGABE_ZEICHEN).widersprueche(),
                WissensMarkdown.analysieren(neu, WissensMarkdown.MAX_EINGABE_ZEICHEN).widersprueche()));
        diff.putIfAbsent("entfernteWidersprueche", WissensMarkdown.entfernteWidersprueche(alt, neu));
        return diff;
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

    private Kopf kopf(Object[] row) {
        ImportHerkunft herkunft = null;
        if (row[8] != null || row[9] != null) {
            if (row[8] == null || row[9] == null) {
                throw new IllegalStateException("Gespeicherte Importherkunft ist unvollständig");
            }
            try {
                ImportAngaben angaben = json.readValue((String) row[9], ImportAngaben.class);
                herkunft = new ImportHerkunft((String) row[8], angaben.kopf(), angaben.unveraendert(), angaben.ignoriert());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Gespeicherte Importherkunft ist nicht lesbar", e);
            }
        }
        return new Kopf(uuid(row[0]), (String) row[1], (String) row[2], (String) row[3], zeit(row[4]),
                uuid(row[5]), Boolean.TRUE.equals(row[6]), (String) row[7], herkunft);
    }

    private record ImportAngaben(PaketKopf kopf, int unveraendert, int ignoriert) { }

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
