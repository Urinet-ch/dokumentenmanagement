package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 16.14: Abdeckungsprüfung für die CI. */
class ReqAbdeckungTest {

    @Test
    void bereicheWerdenAufgeloest() {
        assertThat(ReqAbdeckung.kriterien("1.7, 16.20–16.22, 9.1-9.2"))
                .containsExactly("1.7", "16.20", "16.21", "16.22", "9.1", "9.2");
        assertThatThrownBy(() -> ReqAbdeckung.kriterien("1.1–2.3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nurAbgehakteAufgabenAbWelle1() {
        List<String> tasks = List.of(
                "- [x] 0.5 Golden-Lauf",
                "  - _Anforderungen: 0.3_",
                "- [x] 1.1 Gerüst",
                "  - _Anforderungen: 1.7, 16.13–16.14_",
                "- [ ] 1.2 Entwicklungsumgebung",
                "  - _Anforderungen: 2.1_");
        assertThat(ReqAbdeckung.gefordert(tasks)).containsExactly("1.7", "16.13", "16.14");
    }

    @Test
    void nachweisVermerkJeKriterium() {
        List<String> requirements = List.of(
                "### Requirement 16: Betrieb",
                "#### Akzeptanzkriterien",
                "1. Das Archiv SOLL laufen. Nachweis: manuell [Fabian].",
                "13. Das Backend SOLL gegliedert sein.",
                "    Nachweis: JUnit (ArchUnit-Tests).",
                "14. Jedes Kriterium SOLL abgedeckt sein.");
        assertThat(ReqAbdeckung.mitNachweis(requirements)).containsExactly("16.1", "16.13");
    }

    @Test
    void lueckenNurOhneSzenarioUndOhneVermerk(@TempDir Path repo) throws IOException {
        Path spec = Files.createDirectories(repo.resolve(".kiro/specs/01-test"));
        Files.writeString(spec.resolve("tasks.md"), "- [x] 1.1 Gerüst\n  - _Anforderungen: 1.7, 16.1, 16.14_\n");
        Files.writeString(spec.resolve("requirements.md"),
                "### Requirement 1: M\n7. Schema.\n### Requirement 16: B\n1. Läuft. Nachweis: manuell\n14. Abdeckung.\n");
        Path features = Files.createDirectories(repo.resolve("backend/src/test/resources/features/x"));
        Files.writeString(features.resolve("a.feature"), "@req-1.7\nFeature: a\n  # @req-16.14 im Kommentar zählt nicht\n");
        assertThat(ReqAbdeckung.luecken(repo)).containsExactly("01-test: 16.14");
    }
}
