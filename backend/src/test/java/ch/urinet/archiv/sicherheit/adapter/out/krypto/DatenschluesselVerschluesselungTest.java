package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.file.FileSystemConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schutzregel für {@code archiv_data_key} (tech.md Regel 7; Spec 02, design.md «Verschluesselung»): optional, in prod
 * nur aus der Secret-Datei, ein ungültiger Schlüssel bricht ab, ohne den Inhalt zu nennen. Wie {@code SecretPruefungTest}
 * mit Datei-Quelle und einer Quelle, die die Umgebung nachbildet.
 */
class DatenschluesselVerschluesselungTest {

    @TempDir
    Path verzeichnis;

    @Test
    void ohneDateiOderMitLeererDateiNichtVerfuegbar() throws Exception {
        assertThat(new DatenschluesselVerschluesselung(config(Map.of()), true).verfuegbar()).isFalse();
        Files.writeString(verzeichnis.resolve("archiv_data_key"), "");
        DatenschluesselVerschluesselung leer = new DatenschluesselVerschluesselung(config(Map.of()), true);
        assertThat(leer.verfuegbar()).isFalse();
        assertThatThrownBy(() -> leer.verschluesseln(new byte[1], "b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void gueltigeDateiIstVerfuegbar() throws Exception {
        Files.writeString(verzeichnis.resolve("archiv_data_key"), schluessel() + "\n");
        DatenschluesselVerschluesselung krypto = new DatenschluesselVerschluesselung(config(Map.of()), true);
        assertThat(krypto.verfuegbar()).isTrue();
        byte[] chiffrat = krypto.verschluesseln("pw".getBytes(StandardCharsets.UTF_8), "b");
        assertThat(krypto.entschluesseln(chiffrat, "b")).isEqualTo("pw".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ausDerUmgebungNurWennNichtNurDateiVerlangt() {
        String wert = schluessel();
        assertThatThrownBy(() -> new DatenschluesselVerschluesselung(config(Map.of("archiv_data_key", wert)), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secret-Datei")
                .hasMessageNotContaining(wert);
        assertThat(new DatenschluesselVerschluesselung(config(Map.of("archiv_data_key", wert)), false).verfuegbar())
                .isTrue();
    }

    @Test
    void ungueltigeDateiBrichtAbOhneDenInhaltZuNennen() throws Exception {
        String kurz = Base64.getEncoder().encodeToString(new byte[16]);
        Files.writeString(verzeichnis.resolve("archiv_data_key"), kurz);
        assertThatThrownBy(() -> new DatenschluesselVerschluesselung(config(Map.of()), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv_data_key ungültig")
                .hasMessageNotContaining(kurz);
    }

    private SmallRyeConfig config(Map<String, String> umgebung) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(umgebung, "EnvConfigSource", 300),
                        new FileSystemConfigSource(verzeichnis.toFile()))
                .build();
    }

    private static String schluessel() {
        byte[] roh = new byte[32];
        new SecureRandom().nextBytes(roh);
        return Base64.getEncoder().encodeToString(roh);
    }
}
