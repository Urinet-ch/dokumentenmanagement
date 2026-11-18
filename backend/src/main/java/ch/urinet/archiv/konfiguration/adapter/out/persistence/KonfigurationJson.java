package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Die Typen der Zusatzfelder leitet die Anwendung beim Lesen aus dem Zusatz-Schema ab
 * ({@code KonfigurationVerwaltung}); sie gehören weder ins gespeicherte Abbild noch in dessen Ausgabe.
 */
@Singleton
public class KonfigurationJson implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper json) {
        json.addMixIn(Konfiguration.Art.class, ArtOhneFeldTypen.class);
    }

    @JsonIgnoreProperties({"zusatzFeldTypen"})
    private abstract static class ArtOhneFeldTypen {
    }
}
