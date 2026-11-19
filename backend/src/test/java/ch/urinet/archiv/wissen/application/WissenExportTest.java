package ch.urinet.archiv.wissen.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenExportTest {
    @Test
    void downloadIstNurBis24StundenNachAbschlussVerfuegbar() {
        Instant fertig = Instant.parse("2026-01-02T03:04:05Z");
        WissenExportRepository.Export export = new WissenExportRepository.Export(UUID.randomUUID(),
                WissenExportRepository.Status.FERTIG, "probe", "INHABER", fertig.minusSeconds(1), fertig,
                "exporte/probe.zip", "v1", "a".repeat(64), 100L, null);
        assertThat(export.herunterladbar(fertig.plus(Duration.ofHours(24)).minusNanos(1))).isTrue();
        assertThat(export.herunterladbar(fertig.plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void fehlgeschlagenerExportIstNichtHerunterladbar() {
        Instant jetzt = Instant.parse("2026-01-02T03:04:05Z");
        WissenExportRepository.Export export = new WissenExportRepository.Export(UUID.randomUUID(),
                WissenExportRepository.Status.FEHLER, "probe", "INHABER", jetzt, null,
                null, null, null, null, UUID.randomUUID());
        assertThat(export.herunterladbar(jetzt)).isFalse();
    }
}
