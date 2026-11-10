package ch.urinet.archiv.mandant.adapter.in.start;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Nur API und Admin-Kommando migrieren {@code db/public}; der Worker nie (design.md «Mandant»). */
class MigrationNachRolleTest {

    @Test
    void nurApiUndKommandoMigrieren() {
        assertThat(migriert("api,worker", "true")).isTrue();
        assertThat(migriert("api", "true")).isTrue();
        assertThat(migriert("kommando", "true")).isTrue();
        assertThat(migriert("worker", "true")).isFalse();
        assertThat(migriert(" worker ", "true")).isFalse();
    }

    @Test
    void ausgeschaltetBleibtAus() {
        assertThat(migriert("api", "false")).isFalse();
    }

    @Test
    void profilwerteZaehlen() {
        Map<String, String> werte = new HashMap<>(Map.of(MigrationNachRolle.MIGRIEREN, "true", "archiv.rolle", "api,worker",
                "%worker.archiv.rolle", "worker"));
        SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors().withProfile("worker")
                .withInterceptors(new MigrationNachRolle()).withSources(new PropertiesConfigSource(werte, "probe", 100))
                .build();
        assertThat(config.getValue(MigrationNachRolle.MIGRIEREN, Boolean.class)).isFalse();
    }

    private static boolean migriert(String rolle, String migrieren) {
        SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors().withInterceptors(new MigrationNachRolle())
                .withSources(new PropertiesConfigSource(Map.of(MigrationNachRolle.MIGRIEREN, migrieren, "archiv.rolle", rolle),
                        "probe", 100))
                .build();
        return config.getValue(MigrationNachRolle.MIGRIEREN, Boolean.class);
    }
}
