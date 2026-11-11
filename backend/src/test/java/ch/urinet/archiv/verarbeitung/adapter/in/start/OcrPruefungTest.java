package ch.urinet.archiv.verarbeitung.adapter.in.start;

import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrEinstellungen;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrPruefungTest {

    @Test
    void containerModusNurAusserhalbVonProd() {
        var ocr = new OcrEinstellungen(Duration.ofMinutes(8), 1, Path.of("/tmp/archiv-ocr"),
                Optional.of("urinet-archiv-werkzeuge:lokal"), "docker", "/usr/bin");

        assertThatCode(() -> OcrPruefung.pruefe(ocr, Duration.ofMinutes(10), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> OcrPruefung.pruefe(ocr, Duration.ofMinutes(10), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("container-image");
    }

    @Test
    void fehlendeWerkzeugeWerdenGenannt() {
        assertThat(OcrPruefung.fehlendeWerkzeuge("/gibt/es/nicht")).containsExactlyElementsOf(OcrPruefung.WERKZEUGE);
    }
}
