package ch.urinet.archiv.spike.golden;

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
        // Beim Build gibt es die Secret-Datei nicht; GoldenLauf prüft sie beim Start.
        if (!Files.isReadable(pfad)) {
            return List.of();
        }
        try {
            String token = Files.readString(pfad).strip();
            return List.of(new MapBackedConfigSource("infomaniak-ai-token-datei",
                    Map.of("quarkus.langchain4j.openai.api-key", token), 50) {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Token-Datei nicht lesbar", e);
        }
    }
}
