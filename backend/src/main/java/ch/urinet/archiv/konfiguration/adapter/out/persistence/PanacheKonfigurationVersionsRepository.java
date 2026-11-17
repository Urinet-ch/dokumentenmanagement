package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.KonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public PanacheKonfigurationVersionsRepository(ObjectMapper json) {
        this.json = json;
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
                    json.readValue(eintrag.inhalt, Konfiguration.class), eintrag.sha256);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Konfigurationsversion nicht lesbar", e);
        }
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
