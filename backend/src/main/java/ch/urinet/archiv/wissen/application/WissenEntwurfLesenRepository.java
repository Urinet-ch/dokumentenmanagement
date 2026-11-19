package ch.urinet.archiv.wissen.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WissenEntwurfLesenRepository {
    List<Kopf> liste(String status, String eigenerAnalyseVorschlag);

    Optional<Kopf> kopf(UUID id);

    List<WissenIngestPort.Kernaussage> kernaussagen(UUID id);

    List<Aenderung> aenderungen(UUID id);

    List<WissenEntwurfLesen.Entscheidung> entscheidungen(UUID id);

    record Kopf(UUID id, String anlass, String status, String erstelltVon, Instant erstellt,
                UUID dokumentId, boolean unvollstaendig, String hinweis) {
    }

    record Aenderung(long id, int nr, String aktion, UUID seiteId, String pfad, Integer basisVersion,
                     Map<String, Object> daten, String inhalt, Map<String, Object> diff, String begruendung,
                     List<Map<String, Object>> widersprueche, List<Map<String, Object>> entfernteAussagen,
                     List<Map<String, Object>> befunde, List<Map<String, Object>> hinweise,
                     String vertraulichkeitKontext, String status, String entschiedenVon, Instant entschiedenAm,
                     String grund, Integer ergebnisVersion, int etag) {
    }
}
