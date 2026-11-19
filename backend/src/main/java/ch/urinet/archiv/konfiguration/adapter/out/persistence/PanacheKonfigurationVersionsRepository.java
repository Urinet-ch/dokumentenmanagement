package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.KonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheKonfigurationVersionsRepository implements KonfigurationVersionsRepository {

    private static final String VERSION_ANLEGEN = """
            with abbild as (select cast(:inhalt as jsonb) as inhalt)
            insert into konfiguration_version (version, wer, grund, quelle, inhalt, sha256)
            select :version, :wer, :grund, :quelle, inhalt,
                encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')
            from abbild
            """;

    private final ObjectMapper json;

    private final ObjectMapper kanonisch;

    public PanacheKonfigurationVersionsRepository(ObjectMapper json) {
        this.json = json;
        this.kanonisch = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public String kanonisch(String text) {
        try {
            return kanonisch.writeValueAsString(kanonisch.readValue(text, Object.class));
        } catch (JsonProcessingException e) {
            return text;
        }
    }

    @Override
    public void sperren() {
        em().createNativeQuery("select pg_advisory_xact_lock(hashtextextended(current_schema(), 0))")
                .getSingleResult();
    }

    @Override
    public Optional<KonfigurationsVersion> aktuell() {
        return KonfigurationVersionEintrag.<KonfigurationVersionEintrag>findAll(Sort.descending("version"))
                .firstResultOptional().map(this::domain);
    }

    @Override
    public Optional<KonfigurationsVersion> version(int nummer) {
        return Optional.ofNullable(KonfigurationVersionEintrag.findById(nummer)).map(KonfigurationVersionEintrag.class::cast)
                .map(this::domain);
    }

    @Override
    public List<KonfigurationsVersion> alle() {
        return KonfigurationVersionEintrag.<KonfigurationVersionEintrag>findAll(Sort.ascending("version"))
                .list().stream().map(this::domain).toList();
    }

    @Override
    public KonfigurationsVersion anlegen(int nummer, String wer, String grund, KonfigurationQuelle quelle,
                                         Konfiguration inhalt) {
        em().createNativeQuery(VERSION_ANLEGEN)
                .setParameter("version", nummer)
                .setParameter("wer", wer)
                .setParameter("grund", grund)
                .setParameter("quelle", quelle.name())
                .setParameter("inhalt", json(inhalt))
                .executeUpdate();
        return version(nummer).orElseThrow(() -> new IllegalStateException("Konfigurationsversion nicht lesbar"));
    }

    private KonfigurationsVersion domain(KonfigurationVersionEintrag eintrag) {
        try {
            return new KonfigurationsVersion(eintrag.version, eintrag.zeit.toInstant(), eintrag.wer, eintrag.grund,
                    KonfigurationQuelle.valueOf(eintrag.quelle),
                    lesen(eintrag.inhalt), eintrag.sha256);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Konfigurationsversion nicht lesbar", e);
        }
    }

    /** Liest ein Abbild; {@code zusatzSchema} als Objekt (jsonb der Migration) oder als Text wird kanonisch. */
    Konfiguration lesen(String inhalt) throws JsonProcessingException {
        JsonNode baum = json.readTree(inhalt);
        for (JsonNode art : baum.path("arten")) {
            JsonNode schema = art.path("zusatzSchema");
            if (art instanceof ObjectNode knoten && !schema.isMissingNode() && !schema.isNull()) {
                knoten.put("zusatzSchema", kanonisch(schema.isTextual() ? schema.asText() : schema.toString()));
            }
        }
        return json.treeToValue(baum, Konfiguration.class);
    }

    private String json(Konfiguration inhalt) {
        try {
            return json.writeValueAsString(inhalt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Konfigurationsabbild nicht serialisierbar", e);
        }
    }

    private static EntityManager em() {
        return KonfigurationVersionEintrag.getEntityManager();
    }
}
