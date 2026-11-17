package ch.urinet.archiv.integration.application.webseite;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Driven Port für die mandantenspezifische Prüfung und Speicherung alter URL-Zuordnungen. */
public interface AlteLinksImportPort {

    /** Dokumente mit ihrem Status; nicht vorhandene IDs fehlen in der Map. */
    Map<UUID, String> dokumentstatus(List<UUID> ids);

    /** Bestehende Zuordnungen für normalisierte URLs. */
    Map<String, UUID> zuordnungen(Set<String> urls);

    /** Fügt alle Zuordnungen in der laufenden Transaktion ein. */
    void anlegen(List<Zuordnung> zuordnungen);

    record Zuordnung(String url, UUID dokumentId) {
    }
}
