package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.DateinamenRegelRepository;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.DateinamenRegel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PanacheDateinamenRegelRepository implements DateinamenRegelRepository {

    private static final TypeReference<Map<String, String>> FELDER_TYP = new TypeReference<>() {
    };

    private final ObjectMapper json;
    private final Instance<EntityManager> em;

    public PanacheDateinamenRegelRepository(ObjectMapper json, Instance<EntityManager> em) {
        this.json = json;
        this.em = em;
    }

    @Override
    public List<DateinamenRegel> alle() {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery(
                "select nr, muster, art, felder::text from dateinamen_regel order by nr").getResultList();
        return zeilen.stream().map(this::domain).toList();
    }

    @Override
    public void ersetzen(List<DateinamenRegel> regeln) {
        em.get().createNativeQuery("delete from dateinamen_regel").executeUpdate();
        regeln.forEach(regel -> em.get().createNativeQuery("""
                insert into dateinamen_regel (nr, muster, art, felder)
                values (:nr, :muster, :art, cast(:felder as jsonb))""")
                .setParameter("nr", regel.nr()).setParameter("muster", regel.muster()).setParameter("art", regel.art())
                .setParameter("felder", serialisieren(regel.felder())).executeUpdate());
    }

    private DateinamenRegel domain(Object[] zeile) {
        try {
            return new DateinamenRegel(((Number) zeile[0]).intValue(), (String) zeile[1], (String) zeile[2],
                    json.readValue((String) zeile[3], FELDER_TYP));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dateinamen-Regel nicht lesbar", e);
        }
    }

    private String serialisieren(Map<String, String> felder) {
        try {
            return json.writeValueAsString(felder);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dateinamen-Regel nicht serialisierbar", e);
        }
    }
}
