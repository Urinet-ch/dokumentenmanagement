package ch.urinet.archiv;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Gegenproben zu {@link ArchitekturTest}: Die Regeln schlagen auf bewusst falschen Klassen an. */
class ArchitekturGegenprobeTest {

    @Test
    void klasseImWurzelpaketVerstoesst() {
        JavaClasses klassen = new ClassFileImporter().importClasses(WurzelKlasse.class, ArchivMain.class);

        var ergebnis = ArchitekturTest.schichtenUndFachbereiche.evaluate(klassen);

        assertThat(ergebnis.hasViolation()).isTrue();
        assertThat(ergebnis.getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains(WurzelKlasse.class.getName()))
                .noneMatch(zeile -> zeile.startsWith(ArchivMain.class.getName() + " "));
    }

    @Test
    void methodenreferenzAufGetenvVerstoesst() {
        JavaClasses klassen = new ClassFileImporter().importClasses(UmgebungAlsReferenz.class);

        assertThat(ArchitekturTest.keineUmgebungsvariablen.evaluate(klassen).hasViolation()).isTrue();
    }

    @Test
    void methodenreferenzAufBypassVerstoesst() {
        JavaClasses klassen = new ClassFileImporter().importClasses(BypassAlsReferenz.class);

        assertThat(ArchitekturTest.keinBypassGovernance.evaluate(klassen).hasViolation()).isTrue();
    }

    @Test
    void ganzeDateiImHeapVerstoesstAusserImStub() {
        JavaClasses klassen = new ClassFileImporter().importClasses(
                ch.urinet.archiv.dokument.application.GanzeDateiGegenprobe.class,
                ch.urinet.archiv.ablage.adapter.out.stub.GanzeDateiGegenprobeStub.class);

        var ergebnis = ArchitekturTest.dateienNieGanzImHeap.evaluate(klassen);

        assertThat(ergebnis.hasViolation()).isTrue();
        assertThat(ergebnis.getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains("GanzeDateiGegenprobe.lesen"))
                .noneMatch(zeile -> zeile.contains("GanzeDateiGegenprobeStub"));
    }

    @Test
    void seedFristAusserhalbDesSeedsVerstoesst() {
        JavaClasses klassen = new ClassFileImporter().importClasses(SeedFristImApiWeg.class);

        var ergebnis = ArchitekturTest.seedFristNurImSeed.evaluate(klassen);

        assertThat(ergebnis.getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains("seedSchemaPruefen"))
                .anyMatch(zeile -> zeile.contains("DokumentartPruefung.seedPruefen"))
                .anyMatch(zeile -> zeile.contains("SeedPruefung.pruefen"))
                .hasSize(3);
    }

    static final class WurzelKlasse {
    }

    static final class UmgebungAlsReferenz {
        Supplier<Map<String, String>> umgebung() {
            return System::getenv;
        }
    }

    static final class BypassAlsReferenz {
        java.util.function.Function<Boolean, Object> sperre(Attrappe attrappe) {
            return attrappe::bypassGovernanceRetention;
        }
    }

    /** Ein API-Weg, der die Seed-Frist nutzt (Mutation M2 aus Review PR #81). */
    static final class SeedFristImApiWeg {
        void pruefen(ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer pruefer,
                     ch.urinet.archiv.dokumentart.application.DokumentartPruefung pruefung,
                     ch.urinet.archiv.mandant.application.SeedPruefung seed) {
            pruefer.seedSchemaPruefen("{}");
            pruefung.seedPruefen(null);
            seed.pruefen(null, null);
        }
    }

    interface Attrappe {
        Object bypassGovernanceRetention(Boolean wert);
    }
}
