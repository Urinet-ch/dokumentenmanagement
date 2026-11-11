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

    interface Attrappe {
        Object bypassGovernanceRetention(Boolean wert);
    }
}
