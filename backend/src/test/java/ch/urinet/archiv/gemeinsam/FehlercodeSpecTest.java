package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Enum {@link Fehlercode} entspricht Code für Code und Status für Status der Tabelle in design.md «Error Handling»;
 * die Tabelle ist die einzige Quelle. Ein fehlender, zusätzlicher oder abweichender Code macht den Test rot.
 */
class FehlercodeSpecTest {

    private static final Path DESIGN = Path.of("../.kiro/specs/01-archiv-kern/design.md");
    private static final String KOPF = "| HTTP | `fehlercode` | Wann |";
    private static final Pattern ZEILE = Pattern.compile("^\\| (\\d{3}) \\| ([^|]+) \\|.*");
    private static final Pattern CODE = Pattern.compile("`([A-Z][A-Z0-9_]*)`");

    @Test
    void enumEntsprichtDerTabelle() throws IOException {
        Map<String, Integer> spec = lesen(Files.readAllLines(DESIGN));
        Map<String, Integer> code = Arrays.stream(Fehlercode.values())
                .collect(Collectors.toMap(Fehlercode::name, Fehlercode::status, (a, b) -> a, LinkedHashMap::new));

        assertThat(spec).isNotEmpty().isEqualTo(code);
    }

    @Test
    void titelUndDetailOhneLeereTexte() {
        for (Fehlercode fehlercode : Fehlercode.values()) {
            assertThat(fehlercode.titel()).as(fehlercode.name()).isNotBlank();
            assertThat(fehlercode.detail()).as(fehlercode.name()).isNotBlank().doesNotContain("ß");
        }
    }

    static Map<String, Integer> lesen(List<String> zeilen) {
        int start = zeilen.indexOf(KOPF);
        assertThat(start).as("Tabelle der Fehlercodes in design.md").isNotNegative();
        Map<String, Integer> codes = new LinkedHashMap<>();
        for (String zeile : zeilen.subList(start + 2, zeilen.size())) {
            if (!zeile.startsWith("|")) {
                break;
            }
            Matcher treffer = ZEILE.matcher(zeile);
            assertThat(treffer.matches()).as(zeile).isTrue();
            Matcher code = CODE.matcher(treffer.group(2));
            int anzahl = 0;
            while (code.find()) {
                assertThat(codes.put(code.group(1), Integer.parseInt(treffer.group(1)))).as(code.group(1)).isNull();
                anzahl++;
            }
            assertThat(anzahl).as(zeile).isPositive();
        }
        return codes;
    }
}
