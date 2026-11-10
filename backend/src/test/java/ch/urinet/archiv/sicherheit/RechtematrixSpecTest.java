package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Die Rechtematrix der Domain stimmt Zelle für Zelle mit der Tabelle in Req 2.6 überein; {@code requirements.md} ist
 * die einzige Quelle. Unbekannter Zellentext, eine fehlende oder eine zusätzliche Zeile machen den Test rot.
 */
class RechtematrixSpecTest {

    private static final Path REQUIREMENTS = Path.of("../.kiro/specs/01-archiv-kern/requirements.md");
    private static final Pattern ZEILE = Pattern.compile("^\\| (A\\d+) \\|.*");
    private static final List<String> KOPF = List.of("Nr.", "Aktion", "`inhaber`", "`mitarbeiter`", "`treuhaender`",
            "`geschaeftsstelle` ¹");

    @Test
    void domainEntsprichtDerSpec() throws IOException {
        Map<Aktion, Map<Rolle, Ergebnis>> spec = lesen(Files.readAllLines(REQUIREMENTS));

        assertThat(spec.keySet()).containsExactly(Aktion.values());
        spec.forEach((aktion, zeile) -> zeile.forEach((rolle, ergebnis) ->
                assertThat(Rechtematrix.ergebnis(rolle, aktion)).as(aktion + " " + rolle.code()).isEqualTo(ergebnis)));
    }

    @Test
    void unbekannterZellentextIstRot() {
        assertThatThrownBy(() -> lesen(List.of(
                "| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |",
                "| A1 | Dokumente erfassen | erlaubt | erlaubt | vielleicht | erlaubt |")))
                .hasMessageContaining("vielleicht");
    }

    @Test
    void zusaetzlicheZeileIstRot() {
        assertThatThrownBy(() -> lesen(List.of(
                "| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |",
                "| A99 | Neu | erlaubt | erlaubt | erlaubt | erlaubt |")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Liest die Zeilen A1 … der ersten Tabelle mit dem Kopf von Req 2.6; spätere Tabellen (z. B. A15–A19) nicht. */
    static Map<Aktion, Map<Rolle, Ergebnis>> lesen(List<String> zeilen) {
        Map<Aktion, Map<Rolle, Ergebnis>> matrix = new java.util.LinkedHashMap<>();
        boolean inTabelle = false;
        for (String zeile : zeilen) {
            if (zeile.startsWith("| Nr. |")) {
                assertThat(zellen(zeile)).containsExactlyElementsOf(KOPF);
                inTabelle = matrix.isEmpty();
                continue;
            }
            if (!zeile.startsWith("|")) {
                inTabelle = false;
                continue;
            }
            if (inTabelle && ZEILE.matcher(zeile).matches()) {
                List<String> z = zellen(zeile);
                assertThat(z).as(zeile).hasSize(KOPF.size());
                Aktion aktion = Aktion.valueOf(z.getFirst());
                Map<Rolle, Ergebnis> ergebnisse = new EnumMap<>(Rolle.class);
                Rolle[] rollen = Rolle.values();
                for (int i = 0; i < rollen.length; i++) {
                    ergebnisse.put(rollen[i], RechtematrixZelle.ergebnis(z.get(2 + i)));
                }
                assertThat(matrix.put(aktion, ergebnisse)).as("doppelte Zeile " + aktion).isNull();
            }
        }
        return matrix;
    }

    private static List<String> zellen(String zeile) {
        String innen = zeile.strip().substring(1, zeile.strip().length() - 1);
        return Arrays.stream(innen.split("\\|")).map(String::strip).toList();
    }
}
