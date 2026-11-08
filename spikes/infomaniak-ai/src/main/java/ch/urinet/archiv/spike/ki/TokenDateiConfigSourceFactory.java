package ch.urinet.archiv.spike.ki;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.common.MapBackedConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Liest das API-Token aus der Secret-Datei (tech.md Regel 7), nie aus einer Umgebungsvariable. */
public class TokenDateiConfigSourceFactory implements ConfigSourceFactory {

    static final String TOKEN_DATEI = "archiv.ki.token-datei";

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        ConfigValue pfadWert = context.getValue(TOKEN_DATEI);
        if (pfadWert == null || pfadWert.getValue() == null) {
            return List.of();
        }
        Path pfad = Path.of(pfadWert.getValue());
        // Beim Build gibt es die Secret-Datei nicht; SpikeMain prüft sie beim Start.
        if (!Files.isReadable(pfad)) {
            return List.of();
        }
        String token = lies(pfad);
        Map<String, String> werte = Map.of(
                "quarkus.langchain4j.openai.api-key", token,
                "quarkus.langchain4j.openai.strukturiert.api-key", token,
                "quarkus.langchain4j.openai.jsonmodus.api-key", token);
        return List.of(new MapBackedConfigSource("infomaniak-ai-token-datei", werte, 50) {
        });
    }

    static String lies(Path pfad) {
        try {
            return Files.readString(pfad).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Token-Datei nicht lesbar", e);
        }
    }
}
