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
        assertThat(ReqAbdeckung.kriterien("1.7, 16.20–16.22, 9.1 – 9.2, 4.3–4"))
                .containsExactly("1.7", "16.20", "16.21", "16.22", "9.1", "9.2", "4.3", "4.4");
        assertThatThrownBy(() -> ReqAbdeckung.kriterien("1.1–2.3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parserBrichtBeiUngueltigerListeAb() {
        for (String liste : List.of("1.7, Gate", "1.7 (teilweise)", "9.1-9.2", "16.22–16.20", "1.7,, 1.8", "")) {
            assertThatThrownBy(() -> ReqAbdeckung.kriterien(liste)).as(liste)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void kommaAmEndeBrichtAb() {
        assertThatThrownBy(() -> ReqAbdeckung.kriterien("1.7,")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anforderungszeileMussGanzPassen() {
        for (String zeile : List.of("  - _Anforderungen: 1.7, 2.1", "  - _Anforderungen_: 1.7", "  - _Anforderungen: 1.7_ und 2.1")) {
            assertThatThrownBy(() -> ReqAbdeckung.gefordert(List.of("- [x] 1.1 A", zeile))).as(zeile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void checkboxMitUngueltigerKennungBrichtAb() {
        for (String zeile : List.of("- [x] 1.1a Gerüst", "- [ ] Gerüst", "- [x] X.1 Gerüst")) {
            assertThatThrownBy(() -> ReqAbdeckung.gefordert(List.of(zeile))).as(zeile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ReqAbdeckung.gefordert(List.of("- [ ] O.1 Optional", "- [x]* Frei 1"))).isEmpty();
    }

    @Test
    void labelOhneUnterstrichBrichtAb() {
        for (String zeile : List.of("  - Anforderungen: 2.1", "*Anforderungen: 2.1*", "  - **Anforderungen**: 2.1")) {
            assertThatThrownBy(() -> ReqAbdeckung.gefordert(List.of("- [x] 1.1 A", zeile))).as(zeile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ReqAbdeckung.gefordert(List.of("Jede Aufgabe liefert für ihre Anforderungen Szenarien."))).isEmpty();
    }

    @Test
    void checkboxAusserhalbDesMustersBrichtAb() {
        for (String zeile : List.of("- [x]  1.2 Zwei Leerzeichen", "* [x] 1.2 Stern", "  - [X]\t1.2 Tabulator")) {
            assertThatThrownBy(() -> ReqAbdeckung.gefordert(List.of(zeile))).as(zeile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ueberschriftUndOptionaleAufgabeSetzenZaehlungZurueck() {
        List<String> tasks = List.of(
                "- [x] 1.1 Gerüst",
                "  - _Anforderungen: 1.7_",
                "## Optionale Aufgaben",
                "  - _Anforderungen: 2.1_",
                "- [x] 1.2 Umgebung",
                "  - _Anforderungen: 2.2_",
                "- [x]* O.1 Optional",
                "  - _Anforderungen: 3.1_");
        assertThat(ReqAbdeckung.gefordert(tasks)).containsExactly("1.7", "2.2");
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
        Files.writeString(features.resolve("a.feature"),
                "@spec-01 @req-1.7\nFeature: a\n  # @req-16.14 im Kommentar zählt nicht\n");
        assertThat(ReqAbdeckung.luecken(repo)).containsExactly("01-test: 16.14");
    }

    @Test
    void szenarioDecktNurDieEigeneSpecAb(@TempDir Path repo) throws IOException {
        for (String name : List.of("01-kern", "02-chat")) {
            Path spec = Files.createDirectories(repo.resolve(".kiro/specs/" + name));
            Files.writeString(spec.resolve("tasks.md"), "- [x] 1.1 A\n  - _Anforderungen: 1.7_\n");
            Files.writeString(spec.resolve("requirements.md"), "### Requirement 1: M\n7. Schema.\n");
        }
        Path features = Files.createDirectories(repo.resolve("backend/src/test/resources/features/mandant"));
        Files.writeString(features.resolve("a.feature"), "@spec-01\nFeature: a\n  @req-1.7\n  Scenario: s\n");
        assertThat(ReqAbdeckung.luecken(repo)).containsExactly("02-chat: 1.7");
    }

    @Test
    void featureOhneOderMitMehrerenSpecTagsBrichtAb(@TempDir Path repo) throws IOException {
        Path features = Files.createDirectories(repo.resolve("features"));
        Path datei = features.resolve("a.feature");
        for (String inhalt : List.of(
                "@req-1.7\nFeature: a\n",
                "@spec-01 @spec-02\nFeature: a\n",
                "Feature: a\n  @spec-01\n  Scenario: s\n")) {
            Files.writeString(datei, inhalt);
            assertThatThrownBy(() -> ReqAbdeckung.tags(features)).as(inhalt)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("a.feature");
        }
    }

    @Test
    void attrappeZaehltNichtFuerBackendAufgaben(@TempDir Path repo) throws IOException {
        Path spec = Files.createDirectories(repo.resolve(".kiro/specs/01-test"));
        Files.writeString(spec.resolve("tasks.md"), """
                - [x] 2.4 Upload-Endpunkt [Backend]
                  - _Anforderungen: 3.1, 3.3_
                - [x] 2.7 Upload-Seite [Frontend]
                  - _Anforderungen: 3.1, 3.3, 8.6_
                """);
        Files.writeString(spec.resolve("requirements.md"), "### Requirement 3: U\n1. Upload.\n3. Typ.\n");
        Path features = Files.createDirectories(repo.resolve("backend/src/test/resources/features/dokument"));
        Files.writeString(features.resolve("upload-pwa.feature"),
                "@spec-01 @browser @attrappe\nFeature: a\n  @req-3.1 @req-3.3 @req-8.6\n  Scenario: s\n");
        Files.writeString(features.resolve("upload.feature"), "@spec-01\nFeature: b\n  @req-3.1\n  Scenario: s\n");
        assertThat(ReqAbdeckung.luecken(repo)).containsExactly("01-test: 3.3 (nur @attrappe)");
    }

    @Test
    void attrappeUnterhalbDerFeatureEbeneBrichtAb(@TempDir Path repo) throws IOException {
        Path features = Files.createDirectories(repo.resolve("features"));
        Files.writeString(features.resolve("a.feature"), "@spec-01\nFeature: a\n  @attrappe @req-3.1\n  Scenario: s\n");
        assertThatThrownBy(() -> ReqAbdeckung.tags(features)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@attrappe");
    }
}
