package ch.urinet.archiv.integration.adapter.in.kommando;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archiv alte-links …} über denselben Einstieg wie der Dienst ({@code ArchivMain.main}): Der Zweig
 * «alte-links» in Validierung und Verteilung ist verdrahtet. Ein falscher Aufruf endet vor dem Start mit Exit-Code 2
 * und dem Aufrufhinweis des Kommandos, ein gültiger Aufruf kommt bis zum Kommando (kein Aufrufhinweis, kein Code 2).
 */
@Tag("start-4")
@QuarkusMainTest
class AlteLinksKommandoStartTest {

    @AfterEach
    void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
        ArchivMain.OHNE_DATENBANK.keySet().forEach(System::clearProperty);
    }

    @Test
    void falscherAufrufEndetMitCode2UndAufrufhinweis(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("alte-links", "importieren");

        assertThat(ergebnis.exitCode()).isEqualTo(2);
        assertThat(ergebnis.getErrorOutput()).contains("archiv alte-links importieren");
    }

    @Test
    void gueltigerAufrufErreichtDasKommando(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("alte-links", "importieren", "/nicht/vorhanden.csv",
                "--mandant", "urinet", "--dry-run");

        assertThat(ergebnis.exitCode()).isNotEqualTo(2);
        assertThat(ergebnis.getErrorOutput()).doesNotContain("Aufruf: archiv alte-links");
    }
}
