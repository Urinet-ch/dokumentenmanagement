package ch.urinet.archiv.verarbeitung.adapter.in.start;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.file.FileSystemConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SMTP-Passwort in prod nur aus der Secret-Datei (Aufgabe 5.6, Req 16.9, tech.md Regel 7; Review PR #133): Die Datei
 * ist optional, eine Umgebungsvariable darf weder sie noch den Zugang still übersteuern. Aufbau wie in prod:
 * {@code application.properties}, Dateiquelle (Ordinal 100) und Umgebung (300).
 */
class SmtpPasswortPruefungTest {

    private static final String GEHEIM = "nicht-in-der-meldung";

    @TempDir
    Path secrets;

    @Test
    void passwortAusDerDatei() throws IOException {
        Files.writeString(secrets.resolve(SmtpPasswortPruefung.DATEI), GEHEIM);
        SmallRyeConfig config = prod(Map.of());

        assertThat(SmtpPasswortPruefung.pruefen(config::getConfigValue)).isTrue();
        assertThat(config.getValue(SmtpPasswortPruefung.ZUGANG, String.class)).isEqualTo(GEHEIM);
    }

    @Test
    void leereOderFehlendeDateiStartetOhnePasswort() throws IOException {
        assertThat(SmtpPasswortPruefung.pruefen(prod(Map.of())::getConfigValue)).isFalse();

        Files.writeString(secrets.resolve(SmtpPasswortPruefung.DATEI), "");
        SmallRyeConfig config = prod(Map.of());
        assertThat(SmtpPasswortPruefung.pruefen(config::getConfigValue)).isFalse();
        assertThat(config.getOptionalValue(SmtpPasswortPruefung.ZUGANG, String.class)).isEmpty();
    }

    @Test
    void umgebungsvariableStattDateiBrichtAb() throws IOException {
        SmallRyeConfig ohneDatei = prod(Map.of("ARCHIV_SMTP_PASSWORT", GEHEIM));
        assertThatThrownBy(() -> SmtpPasswortPruefung.pruefen(ohneDatei::getConfigValue))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv_smtp_passwort")
                .hasMessageNotContaining(GEHEIM);

        // Die Umgebung (300) läge über der Datei (100) und übersteuerte sie still
        Files.writeString(secrets.resolve(SmtpPasswortPruefung.DATEI), "aus-der-datei");
        SmallRyeConfig mitDatei = prod(Map.of("ARCHIV_SMTP_PASSWORT", GEHEIM));
        assertThatThrownBy(() -> SmtpPasswortPruefung.pruefen(mitDatei::getConfigValue))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(GEHEIM);
    }

    @Test
    void zugangDirektAusDerUmgebungBrichtAb() throws IOException {
        Files.writeString(secrets.resolve(SmtpPasswortPruefung.DATEI), "aus-der-datei");
        SmallRyeConfig config = prod(Map.of("QUARKUS_MAILER_PASSWORD", GEHEIM));

        assertThatThrownBy(() -> SmtpPasswortPruefung.pruefen(config::getConfigValue))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("quarkus.mailer.password")
                .hasMessageNotContaining(GEHEIM);
    }

    @Test
    void inProdEingeschaltet() throws IOException {
        SmallRyeConfig config = prod(Map.of());
        assertThat(config.getValue("archiv.secrets.smtp-passwort-nur-datei", Boolean.class)).isTrue();
    }

    private SmallRyeConfig prod(Map<String, String> umgebung) throws IOException {
        return new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(SmtpPasswortPruefungTest.class.getClassLoader()
                        .getResource("application.properties")))
                .withSources(new FileSystemConfigSource(secrets.toFile(), 100))
                .withSources(new EnvConfigSource(umgebung, 300) {
                })
                .build();
    }
}
