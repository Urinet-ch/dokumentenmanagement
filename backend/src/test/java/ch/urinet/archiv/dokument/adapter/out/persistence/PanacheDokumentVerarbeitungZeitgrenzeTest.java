package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PanacheDokumentVerarbeitungZeitgrenzeTest {

    @Test
    void statementTimeoutLiegtUnterDerAuftragszeitgrenze() throws IOException {
        SmallRyeConfig konfiguration = new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(getClass().getClassLoader().getResource("application.properties")))
                .withSources(new PropertiesConfigSource(Map.of("test.statement-timeout",
                        PanacheDokumentVerarbeitung.ZEITGRENZE), "test"))
                .build();
        Duration auftrag = Duration.parse("PT" + konfiguration.getValue(
                "archiv.auftrag.zeitgrenze.VERARBEITEN", String.class).toUpperCase(Locale.ROOT));
        Duration statement = Duration.parse("PT" + konfiguration.getValue("test.statement-timeout", String.class)
                .toUpperCase(Locale.ROOT));

        assertThat(statement).isLessThan(auftrag);
    }
}
