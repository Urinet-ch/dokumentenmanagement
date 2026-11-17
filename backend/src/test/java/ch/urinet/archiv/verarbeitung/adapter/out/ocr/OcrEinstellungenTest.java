package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.gemeinsam.ProdZeitgrenzen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * {@code archiv.ocr.zeitgrenze} nutzt nur {@code VERARBEITEN} ({@code KI_VORSCHLAG} gibt sein Restbudget mit);
     * {@code quarkus.s3.api-call-timeout} gilt für jeden Auftragstyp, darum gegen die kürzeste Auftragszeitgrenze
     * (Review PR #266, S1).
     */
    @Test
    void produktiveOcrUndS3ZeitgrenzenLiegenUnterDenAuftragszeitgrenzen() throws IOException {
        ProdZeitgrenzen grenzen = ProdZeitgrenzen.laden();
        Map.Entry<String, Duration> kuerzeste = grenzen.kuerzesteAuftragszeitgrenze();

        assertThat(grenzen.dauer("archiv.ocr.zeitgrenze")).isLessThan(grenzen.auftraege().get("VERARBEITEN"));
        assertThat(grenzen.dauer("quarkus.s3.api-call-timeout")).as(kuerzeste.getKey())
                .isLessThan(kuerzeste.getValue());
    }

    /** Leerzeichen, {@code :} oder {@code ,} würden {@code docker -v} bzw. Argumente aufteilen. */
    @ParameterizedTest
    @ValueSource(strings = {"/tmp/mit leerzeichen", "/tmp/a:b", "/tmp/a,b", "/tmp/a;b", "/tmp/$x"})
    void arbeitsverzeichnisNurMitSicherenZeichen(String pfad) {
        assertThatThrownBy(() -> new OcrEinstellungen(Duration.ofMinutes(1), 1, Path.of(pfad), Optional.empty(),
                "docker", "/usr/bin")).isInstanceOf(IllegalArgumentException.class);
    }
}
