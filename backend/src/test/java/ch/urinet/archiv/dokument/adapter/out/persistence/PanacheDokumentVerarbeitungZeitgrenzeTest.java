package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.gemeinsam.ProdZeitgrenzen;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PanacheDokumentVerarbeitungZeitgrenzeTest {

    /**
     * Der {@code statement_timeout} gilt für {@code VERARBEITEN}, {@code KI_VORSCHLAG} und {@code ARCHIVIEREN}
     * ({@link PanacheDokumentArchivierung}); verglichen wird darum mit der kürzesten Auftragszeitgrenze (Review
     * PR #266, S1).
     */
    @Test
    void statementTimeoutLiegtUnterJederAuftragszeitgrenze() throws IOException {
        Map.Entry<String, Duration> kuerzeste = ProdZeitgrenzen.laden().kuerzesteAuftragszeitgrenze();

        assertThat(ProdZeitgrenzen.dauerAus(PanacheDokumentVerarbeitung.ZEITGRENZE)).as(kuerzeste.getKey())
                .isLessThan(kuerzeste.getValue());
    }
}
