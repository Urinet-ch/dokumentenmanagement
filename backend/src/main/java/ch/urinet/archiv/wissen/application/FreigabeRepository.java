package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.Wissensseite;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FreigabeRepository {
    Optional<Aenderung> sperren(UUID entwurfId, int nummer);

    Optional<Integer> seitenVersionSperren(UUID seiteId);

    void alsVeraltetMarkieren(long aenderungId, int etag);

    Vorschlag vorschlagLaden(Aenderung aenderung);

    void verwerfen(long aenderungId, int etag, String benutzer, String grund);

    boolean entwurfAbschliessen(UUID entwurfId);

    void abschlussProtokollieren(UUID entwurfId);

    record Aenderung(long id, UUID seiteId, String entwurfStatus, String anlass, String status, int etag,
                     String aktion, String pfad, Integer basisVersion) {
    }

    record Vorschlag(Wissensseite fassung, Set<String> entfernteAussagen, String kontextstufe,
                     UUID wissensquelleId, String modell) {
        public Vorschlag {
            entfernteAussagen = Set.copyOf(entfernteAussagen);
        }
    }
}
