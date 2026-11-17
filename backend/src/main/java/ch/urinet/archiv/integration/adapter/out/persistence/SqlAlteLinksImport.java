package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.AlteLinksImportPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mandantenspezifische Datenbankzugriffe für den Import alter URL-Zuordnungen. */
@ApplicationScoped
public class SqlAlteLinksImport implements AlteLinksImportPort {

    private final Instance<EntityManager> em;

    public SqlAlteLinksImport(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public Map<UUID, String> dokumentstatus(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery(
                "select id, status from dokument where id = any(:ids)")
                .setParameter("ids", ids.toArray(UUID[]::new)).getResultList();
        Map<UUID, String> status = new HashMap<>();
        for (Object[] zeile : zeilen) {
            status.put((UUID) zeile[0], (String) zeile[1]);
        }
        return status;
    }

    @Override
    public Map<String, UUID> zuordnungen(Set<String> urls) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery(
                "select url, dokument_id from alte_url where url = any(:urls)")
                .setParameter("urls", urls.toArray(String[]::new)).getResultList();
        Map<String, UUID> zuordnungen = new HashMap<>();
        for (Object[] zeile : zeilen) {
            zuordnungen.put((String) zeile[0], (UUID) zeile[1]);
        }
        return zuordnungen;
    }

    @Override
    public void anlegen(List<Zuordnung> zuordnungen) {
        var abfrage = em.get().createNativeQuery(
                "insert into alte_url (url, dokument_id) values (:url, :dokumentId)");
        for (Zuordnung zuordnung : zuordnungen) {
            abfrage.setParameter("url", zuordnung.url());
            abfrage.setParameter("dokumentId", zuordnung.dokumentId());
            abfrage.executeUpdate();
        }
    }
}
