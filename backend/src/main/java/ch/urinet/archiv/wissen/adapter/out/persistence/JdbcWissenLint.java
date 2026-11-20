package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenLintRepository;
import ch.urinet.archiv.wissen.application.WissenIngestPort;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenLint implements WissenLintRepository {
    private final Instance<EntityManager> entityManager;
    private final ObjectMapper mapper;
    private final WissenIngestPort ingest;

    public JdbcWissenLint(Instance<EntityManager> entityManager, ObjectMapper mapper, WissenIngestPort ingest) {
        this.entityManager = entityManager;
        this.mapper = mapper;
        this.ingest = ingest;
    }

    private EntityManager em() {
        return entityManager.get();
    }

    @Override
    public void budgetsperre() {
        em().createNativeQuery("select pg_advisory_xact_lock(hashtextextended(current_schema() "
                + "|| ':wissen-budget', 0))").getSingleResult();
    }

    @Override
    public long lintVerbrauch(UUID entwurfId) {
        return ((Number) em().createNativeQuery("""
                select least(coalesce(sum(tokens_ein::numeric + tokens_aus::numeric), 0),
                    9223372036854775807)::bigint from ki_verbrauch
                where zweck = 'WISSEN_LINT' and ingest_entwurf_id = :id
                """).setParameter("id", entwurfId).getSingleResult()).longValue();
    }

    @Override
    public List<Wissensseite> seitenLaden() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em().createNativeQuery("""
                select id, veraltet_ab, vertraulichkeit, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am,
                       quelle_pruefen, widerspruch_offen, quellen_revision from wissensseite order by id
                """).getResultList();
        List<Wissensseite> seiten = new ArrayList<>();
        for (Object[] r : rows) {
            UUID id = uuid(r[0]);
            var fassung = ingest.seiteLaden(id).orElseThrow(
                    () -> new IllegalStateException("Wissensseite während Lint-Ladung entfernt"));
            var meta = fassung.metadaten();
            var datum = r[1] instanceof java.sql.Date sqlDatum ? sqlDatum.toLocalDate()
                    : (java.time.LocalDate) r[1];
            seiten.add(new Wissensseite(id, meta.pfad(), Seitentyp.vonDatenbankwert(meta.typ()).orElseThrow(),
                    meta.titel(), meta.beschreibung(), meta.tags(), Wissensseite.Status.valueOf(meta.status()), datum,
                    fassung.quellen(), Wissensseite.Vertraulichkeit.valueOf((String) r[2]), fassung.inhalt(),
                    meta.version(), (String) r[3], zeit(r[4]), (String) r[5], zeit(r[6]),
                    Boolean.TRUE.equals(r[7]), Boolean.TRUE.equals(r[8]), fassung.wissensquelleId(),
                    ((Number) r[9]).longValue()));
        }
        return List.copyOf(seiten);
    }

    @Override
    public UUID anlegen(String akteur, String modell) {
        UUID lintId = UUID.randomUUID();
        UUID entwurfId = UUID.randomUUID();
        em().createNativeQuery("""
                insert into wissens_entwurf (id, anlass, status, schritt, ki_modell, erstellt_von, erstellt)
                values (:id, 'LINT', 'IN_ARBEIT', 'PLANEN', :modell, :akteur, clock_timestamp())
                """).setParameter("id", entwurfId).setParameter("modell", modell)
                .setParameter("akteur", akteur).executeUpdate();
        em().createNativeQuery("""
                insert into wissens_lint (id, gestartet, ausgeloest_von, befunde, entwurf_id)
                values (:id, clock_timestamp(), :akteur, '[]'::jsonb, :entwurf)
                """).setParameter("id", lintId).setParameter("akteur", akteur)
                .setParameter("entwurf", entwurfId).executeUpdate();
        return lintId;
    }

    @Override
    public boolean zeitplanBereits(Instant termin) {
        return ((Number) em().createNativeQuery("""
                select count(*) from wissens_lint
                where ausgeloest_von = 'process:wissen-lint' and gestartet >= :termin
                """).setParameter("termin", termin).getSingleResult()).longValue() > 0;
    }

    @Override
    public Optional<Lauf> laden(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em().createNativeQuery("""
                select l.id, e.id, l.ausgeloest_von, e.ki_modell, e.schritt,
                       (select least(coalesce(sum(v.tokens_ein::numeric), 0), 9223372036854775807)::bigint
                        from ki_verbrauch v where v.zweck = 'WISSEN_LINT' and v.ingest_entwurf_id = e.id),
                       (select least(coalesce(sum(v.tokens_aus::numeric), 0), 9223372036854775807)::bigint
                        from ki_verbrauch v where v.zweck = 'WISSEN_LINT' and v.ingest_entwurf_id = e.id),
                       cast(e.ingest_daten as text)
                from wissens_lint l join wissens_entwurf e on e.id = l.entwurf_id
                where l.id = :id and l.beendet is null and e.anlass = 'LINT' and e.status = 'IN_ARBEIT'
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.getFirst();
        String fortschritt = (String) r[7];
        return Optional.of(new Lauf(uuid(r[0]), uuid(r[1]), (String) r[2], (String) r[3], (String) r[4],
                ((Number) r[5]).longValue(), ((Number) r[6]).longValue(),
                "{}".equals(fortschritt) ? Fortschritt.leer() : lesen(fortschritt, Fortschritt.class)));
    }

    @Override
    public void fortschritt(UUID id, String schritt, Fortschritt fortschritt, long ein, long aus) {
        int n = em().createNativeQuery("""
                update wissens_entwurf e set schritt = :schritt, ingest_daten = cast(:daten as jsonb),
                    tokens_ein = :ein, tokens_aus = :aus
                from wissens_lint l
                where l.id = :id and l.beendet is null and e.id = l.entwurf_id and e.status = 'IN_ARBEIT'
                """).setParameter("id", id).setParameter("schritt", schritt)
                .setParameter("daten", json(fortschritt)).setParameter("ein", intWert(ein))
                .setParameter("aus", intWert(aus)).executeUpdate();
        if (n != 1) {
            throw new IllegalStateException("Lint-Fortschritt konnte nicht gespeichert werden");
        }
    }

    @Override
    public void abschliessen(UUID id, Fortschritt fortschritt, long ein, long aus, boolean unvollstaendig,
                             String hinweis, boolean hatAenderungen) {
        Optional<Lauf> lauf = laden(id);
        if (lauf.isEmpty()) {
            return;
        }
        em().createNativeQuery("""
                update wissens_entwurf set status = :status, schritt = null, ingest_daten = cast(:daten as jsonb),
                    tokens_ein = :ein, tokens_aus = :aus, unvollstaendig = :unvollstaendig,
                    hinweis = :hinweis, abgeschlossen = clock_timestamp() where id = :entwurf
                """).unwrap(NativeQuery.class)
                .setParameter("status", hatAenderungen ? "OFFEN" : "ERLEDIGT")
                .setParameter("daten", json(fortschritt)).setParameter("ein", intWert(ein))
                .setParameter("aus", intWert(aus)).setParameter("unvollstaendig", unvollstaendig)
                .setParameter("hinweis", hinweis, String.class).setParameter("entwurf", lauf.get().entwurfId())
                .executeUpdate();
        String befunde = json(fortschritt.befunde());
        em().createNativeQuery("""
                update wissens_lint set beendet = clock_timestamp(), befunde = cast(:befunde as jsonb)
                where id = :id and beendet is null
                """).setParameter("befunde", befunde).setParameter("id", id).executeUpdate();
        em().createNativeQuery("""
                insert into wissens_log (zeit, aktion, akteur, seiten, entwurf_id, angaben)
                select clock_timestamp(), 'LINT', :akteur,
                    array(select distinct (b->>'seiteId')::uuid from jsonb_array_elements(cast(:befunde as jsonb)) b
                          where b->>'seiteId' is not null), :entwurf,
                    coalesce((select jsonb_object_agg(code, anzahl) from
                        (select b->>'code' as code, count(*) as anzahl
                         from jsonb_array_elements(cast(:befunde as jsonb)) b group by b->>'code') x), '{}'::jsonb)
                """).unwrap(NativeQuery.class).setParameter("akteur", lauf.get().akteur())
                .setParameter("befunde", befunde)
                .setParameter("entwurf", lauf.get().entwurfId(), UUID.class)
                .executeUpdate();
    }

    @Override
    public Map<WissensLint.PaarId, Instant> paarZeiten() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em().createNativeQuery("""
                select p->>'erste', p->>'zweite', max(l.beendet)
                from wissens_lint l join wissens_entwurf e on e.id = l.entwurf_id
                cross join lateral jsonb_array_elements(coalesce(e.ingest_daten->'geprueft', '[]'::jsonb)) p
                where l.beendet is not null group by p->>'erste', p->>'zweite'
                """).getResultList();
        Map<WissensLint.PaarId, Instant> ergebnis = new HashMap<>();
        for (Object[] r : rows) {
            ergebnis.put(new WissensLint.PaarId(UUID.fromString((String) r[0]), UUID.fromString((String) r[1])),
                    zeit(r[2]));
        }
        return Map.copyOf(ergebnis);
    }

    private String json(Object wert) {
        try {
            return mapper.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Lint-Daten nicht serialisierbar", e);
        }
    }

    private <T> T lesen(String wert, Class<T> typ) {
        try {
            return mapper.readValue(wert, typ);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Lint-Daten nicht lesbar", e);
        }
    }

    private static UUID uuid(Object wert) {
        return wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }

    private static Instant zeit(Object wert) {
        return switch (wert) {
            case Instant instant -> instant;
            case java.time.OffsetDateTime offset -> offset.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case null -> null;
            default -> java.time.OffsetDateTime.parse(wert.toString()).toInstant();
        };
    }

    private static int intWert(long wert) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, wert));
    }
}
