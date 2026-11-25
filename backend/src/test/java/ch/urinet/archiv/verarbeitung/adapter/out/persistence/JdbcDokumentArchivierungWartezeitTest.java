package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.DokumentArchivierungSperre;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Teilnachweis @req-6.3: Ein wartender Handler belegt die Verbindung nicht unbegrenzt. */
@QuarkusTest
class JdbcDokumentArchivierungWartezeitTest {

    @Inject
    Instance<AgroalDataSource> datenquelle;

    @Test
    void belegteSperreLaeuftAbUndVerbindungWirdFreigegeben() throws Exception {
        var sperre = new JdbcDokumentArchivierungSperre(datenquelle, TimeUnit.MILLISECONDS.toNanos(200));
        var mandant = new MandantCode("urinet");
        var id = UUID.randomUUID();
        try (DokumentArchivierungSperre.Sperre gehalten = sperre.sperren(mandant, id)) {
            long beginn = System.nanoTime();
            assertThatThrownBy(() -> sperre.sperren(mandant, id))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Wartezeit für Dokumentarchivierungssperre überschritten");
            assertThat(System.nanoTime() - beginn).isBetween(TimeUnit.MILLISECONDS.toNanos(200),
                    TimeUnit.SECONDS.toNanos(5));
        }
        try (DokumentArchivierungSperre.Sperre erneut = sperre.sperren(mandant, id)) {
            assertThat(erneut).isNotNull();
        }
    }
}
