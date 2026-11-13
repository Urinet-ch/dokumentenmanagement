package ch.urinet.archiv.mandant;

import ch.urinet.archiv.ArchivMain;
import ch.urinet.archiv.verarbeitung.adapter.in.kommando.EmbeddingKommando;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin-Kommando {@code archiv mandant anlegen <code>} (ADR-002) über denselben Einstieg wie der Dienst, ohne Server. */
@Tag("start-2")
@QuarkusMainTest
class MandantKommandoTest {

    /** {@code ArchivMain.main} setzt sie als System-Properties; danach laufende Tests bräuchten den HTTP-Server. */
    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-kommando", "--geschaeftsjahr-ende", "03-31"})
    void anlegen(LaunchResult ergebnis) {
        assertThat(ergebnis.exitCode()).isZero();
        assertThat(ergebnis.getOutput()).contains("Mandant probe-kommando angelegt", "Schema m_probe_kommando",
                "Bucket urinet-archiv-probe-kommando", "Geschäftsjahresende 03-31");
        assertThat(ergebnis.getOutput()).as("Quarkus gestartet").contains("Installed features");
        assertThat(ergebnis.getOutput()).as("kein HTTP-Server, keine Start-Migration")
                .doesNotContain("Listening on: http", "aktive Mandanten migriert");
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "Ungueltig"}, exitCode = 1)
    void fachlicherFehler(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("UNGUELTIG");
    }

    /** Falscher Aufruf: Exit 2, ohne Quarkus zu starten (keine Migration von {@code db/public}). */
    @Test
    @Launch(value = {"mandant", "foo"}, exitCode = 2)
    void falscherAufruf(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Aufruf: archiv mandant anlegen");
        assertThat(ergebnis.getOutput()).as("ohne Start").doesNotContain("Installed features", "Liquibase");
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-x", "--name"}, exitCode = 2)
    void optionOhneWert(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Aufruf: archiv mandant anlegen");
        assertThat(ergebnis.getOutput()).as("ohne Start").doesNotContain("Installed features");
    }

    @Test
    @Launch(value = {"unbekannt"}, exitCode = 2)
    void unbekanntesKommando(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Unbekanntes Kommando")
                .contains("archiv embedding nachberechnen");
        assertThat(ergebnis.getOutput()).as("ohne Start").doesNotContain("Installed features");
    }

    /** Spec 02, Aufgabe 6.3: Ein falscher Aufruf von {@code embedding} endet mit Exit 2, bevor Quarkus startet. */
    @Test
    @Launch(value = {"embedding", "nachberechnen"}, exitCode = 2)
    void embeddingOhneMandant(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains(EmbeddingKommando.AUFRUF);
        assertThat(ergebnis.getOutput()).as("ohne Start").doesNotContain("Installed features");
    }

    @Test
    @Launch(value = {"embedding", "nachberechnen", "urinet", "--falsch"}, exitCode = 2)
    void embeddingFalscheOption(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains(EmbeddingKommando.AUFRUF);
        assertThat(ergebnis.getOutput()).as("ohne Start").doesNotContain("Installed features");
    }
}
