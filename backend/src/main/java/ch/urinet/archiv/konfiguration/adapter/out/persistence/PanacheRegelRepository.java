package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.RegelRepository;
import ch.urinet.archiv.konfiguration.domain.Regel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PanacheRegelRepository implements RegelRepository {

    private static final TypeReference<Map<String, Object>> PARAMETER_TYP = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public PanacheRegelRepository(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public List<Regel> alle() {
        return RegelEintrag.<RegelEintrag>findAll(Sort.ascending("code")).list().stream()
                .map(this::domain).toList();
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
