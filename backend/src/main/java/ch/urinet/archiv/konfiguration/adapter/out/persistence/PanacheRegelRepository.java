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
import java.util.Optional;

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
    public Optional<Regel> finden(String code) {
        return RegelEintrag.<RegelEintrag>findByIdOptional(code).map(this::domain);
    }

    @Override
    public void anlegen(Regel regel) {
        em.get().createNativeQuery("""
                insert into regel (code, art, typ, parameter, schwere, meldung, aktiv)
                values (:code, :art, :typ, cast(:parameter as jsonb), :schwere, :meldung, :aktiv)""")
                .setParameter("code", regel.code()).setParameter("art", regel.art()).setParameter("typ", regel.typ())
                .setParameter("parameter", serialisieren(regel.parameter())).setParameter("schwere", regel.schwere())
                .setParameter("meldung", regel.meldung()).setParameter("aktiv", regel.aktiv()).executeUpdate();
    }

    @Override
    public void speichern(Regel regel) {
        int aktualisiert = em.get().createNativeQuery("""
                update regel set art = :art, typ = :typ, parameter = cast(:parameter as jsonb),
                    schwere = :schwere, meldung = :meldung, aktiv = :aktiv
                where code = :code""")
                .setParameter("art", regel.art()).setParameter("typ", regel.typ())
                .setParameter("parameter", serialisieren(regel.parameter())).setParameter("schwere", regel.schwere())
                .setParameter("meldung", regel.meldung()).setParameter("aktiv", regel.aktiv())
                .setParameter("code", regel.code()).executeUpdate();
        if (aktualisiert != 1) {
            throw new IllegalStateException("Regel beim Speichern nicht gefunden");
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

    private String serialisieren(Map<String, Object> parameter) {
        try {
            return json.writeValueAsString(parameter);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Regelparameter nicht serialisierbar", e);
        }
    }
}
