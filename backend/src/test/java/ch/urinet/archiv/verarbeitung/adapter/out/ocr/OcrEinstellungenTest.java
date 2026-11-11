package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrEinstellungenTest {

    @Test
    void zeitbudgetMussUnterDerZeitgrenzeDesAuftragstypsLiegen() {
        assertThatCode(() -> OcrEinstellungen.pruefeZeitgrenze(Duration.ofMinutes(8), Duration.ofMinutes(10)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OcrEinstellungen.pruefeZeitgrenze(Duration.ofMinutes(10), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv.ocr.zeitgrenze");
    }

    /** Leerzeichen, {@code :} oder {@code ,} würden {@code docker -v} bzw. Argumente aufteilen. */
    @ParameterizedTest
    @ValueSource(strings = {"/tmp/mit leerzeichen", "/tmp/a:b", "/tmp/a,b", "/tmp/a;b", "/tmp/$x"})
    void arbeitsverzeichnisNurMitSicherenZeichen(String pfad) {
        assertThatThrownBy(() -> new OcrEinstellungen(Duration.ofMinutes(1), 1, Path.of(pfad), Optional.empty(),
                "docker", "/usr/bin")).isInstanceOf(IllegalArgumentException.class);
    }
}
