package ch.urinet.archiv.protokoll.adapter.out.json;

import ch.urinet.archiv.protokoll.application.JsonLeser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.Map;

/** Liest Manifeste mit Jackson: doppelte Schlüssel, nachfolgende Zeichen und tiefe Schachtelung brechen ab. */
@ApplicationScoped
public class JacksonJsonLeser implements JsonLeser {

    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
                    .build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String, Object>> BAUM = new TypeReference<>() {
    };

    @Override
    public Map<String, Object> objekt(byte[] json) {
        try {
            Map<String, Object> baum = JSON.readValue(json, BAUM);
            if (baum == null) {
                throw new IllegalArgumentException("Manifest ist kein JSON-Objekt");
            }
            return baum;
        } catch (IOException e) {
            throw new IllegalArgumentException("Manifest ist kein gültiges JSON", e);
        }
    }
}
