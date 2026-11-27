package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.paket.PaketKopf;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WissenEntwurfLesenRepository {
    List<Kopf> liste(String status, String eigenerAnalyseVorschlag);

    Optional<Kopf> kopf(UUID id);

    List<WissenIngestPort.Kernaussage> kernaussagen(UUID id);

    Optional<WissenIngestPort.Fortschritt> ingestFortschritt(UUID id);

    List<Aenderung> aenderungen(UUID id);

    List<WissenEntwurfLesen.Entscheidung> entscheidungen(UUID id);

    record Kopf(UUID id, String anlass, String status, String erstelltVon, Instant erstellt,
                UUID dokumentId, boolean unvollstaendig, String hinweis, ImportHerkunft herkunft) {
        public Kopf(UUID id, String anlass, String status, String erstelltVon, Instant erstellt,
                    UUID dokumentId, boolean unvollstaendig, String hinweis) {
            this(id, anlass, status, erstelltVon, erstellt, dokumentId, unvollstaendig, hinweis, null);
        }
    }

    record ImportHerkunft(String paketSha256, PaketKopf kopf, int unveraendert, int ignoriert) { }

    record Aenderung(long id, int nr, String aktion, UUID seiteId, String pfad, Integer basisVersion,
                     Map<String, Object> daten, String inhalt, Map<String, Object> diff, String begruendung,
                     List<Map<String, Object>> widersprueche, List<Map<String, Object>> entfernteAussagen,
                     List<Map<String, Object>> befunde, List<Map<String, Object>> hinweise,
                     String vertraulichkeitKontext, String status, String entschiedenVon, Instant entschiedenAm,
                     String grund, Integer ergebnisVersion, int etag) {
    }
}
