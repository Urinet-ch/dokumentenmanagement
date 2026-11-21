package ch.urinet.archiv.sicherheit.adapter.in.start;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.file.FileSystemConfigSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class SecretPruefungTestzugang {

    private SecretPruefungTestzugang() {
    }

    public static SmallRyeConfig config(Map<String, String> umgebung, Path dateien) throws IOException {
        return new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .addDiscoveredInterceptors()
                .withInterceptors(new SecretPruefung(() -> true))
                .withSources(new PropertiesConfigSource(SecretPruefungTestzugang.class.getClassLoader()
                                .getResource("application.properties")),
                        new EnvConfigSource(umgebung, 300) {},
                        new FileSystemConfigSource(dateien.toFile()))
                .build();
    }
}
