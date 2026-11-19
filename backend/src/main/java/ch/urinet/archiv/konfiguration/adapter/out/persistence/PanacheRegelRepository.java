package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.RegelRepository;
import ch.urinet.archiv.konfiguration.domain.Regel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PanacheRegelRepository implements RegelRepository {

    private static final TypeReference<Map<String, Object>> PARAMETER_TYP = new TypeReference<>() {
    };

    private final ObjectMapper json;
    private final Instance<EntityManager> em;

    public PanacheRegelRepository(ObjectMapper json, Instance<EntityManager> em) {
        this.json = json;
        this.em = em;
    }

    @Override
    public List<Regel> alle() {
        return RegelEintrag.<RegelEintrag>findAll(Sort.ascending("code")).list().stream()
                .map(this::domain).toList();
    }

    @Override
    public void ersetzen(List<Regel> regeln) {
        EntityManager manager = em.get();
        manager.createNativeQuery("delete from regel").executeUpdate();
        for (Regel regel : regeln) {
            try {
                manager.createNativeQuery("""
                        insert into regel (code, art, typ, parameter, schwere, meldung, aktiv)
                        values (:code, :art, :typ, cast(:parameter as jsonb), :schwere, :meldung, :aktiv)""")
                        .setParameter("code", regel.code())
                        .setParameter("art", regel.art())
                        .setParameter("typ", regel.typ())
                        .setParameter("parameter", json.writeValueAsString(regel.parameter()))
                        .setParameter("schwere", regel.schwere())
                        .setParameter("meldung", regel.meldung())
                        .setParameter("aktiv", regel.aktiv())
                        .executeUpdate();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Regelparameter nicht serialisierbar", e);
            }
        }
    }

    private Regel domain(RegelEintrag eintrag) {
        try {
            return new Regel(eintrag.code, eintrag.art, eintrag.typ,
                    json.readValue(eintrag.parameter, PARAMETER_TYP), eintrag.schwere, eintrag.meldung, eintrag.aktiv);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Regelparameter nicht lesbar", e);
        }
    }

}
