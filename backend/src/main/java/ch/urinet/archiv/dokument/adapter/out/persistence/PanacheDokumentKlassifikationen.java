package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.dokument.application.DokumentKlassifikationenPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentKlassifikationen implements DokumentKlassifikationenPort {

    private final EntityManager em;

    public PanacheDokumentKlassifikationen(EntityManager em) {
        this.em = em;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, List<String>> laden(UUID dokumentId) {
        List<Object[]> rows = em.createNativeQuery("""
                select klassifikation, wert from dokument_klassifikation
                where dokument_id = :id order by klassifikation, wert""")
                .setParameter("id", dokumentId).getResultList();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.computeIfAbsent((String) row[0], ignored -> new ArrayList<>()).add((String) row[1]);
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        result.forEach((code, values) -> immutable.put(code, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    @Override
    public void ersetzen(UUID dokumentId, Map<String, List<String>> klassifikationen) {
        em.createNativeQuery("delete from dokument_klassifikation where dokument_id = :id")
                .setParameter("id", dokumentId).executeUpdate();
        klassifikationen.forEach((code, values) -> values.forEach(wert ->
                em.createNativeQuery("""
                        insert into dokument_klassifikation (dokument_id, klassifikation, wert)
                        values (:id, :klasse, :wert)""")
                        .setParameter("id", dokumentId).setParameter("klasse", code).setParameter("wert", wert)
                        .executeUpdate()));
    }
}
