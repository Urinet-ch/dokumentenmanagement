package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Die Rechtematrix der Domain stimmt Zelle für Zelle mit den Tabellen der Specs überein: Spec 01, Req 2.6 (A1–A14,
 * A24), Spec 04, Req 12.1 (A15–A19), Spec 06, Req 4.1 (A20–A23) und Spec 09, Req 15.1 (A25–A29); die
 * {@code requirements.md} dieser Specs sind die einzigen Quellen. Unbekannter Zellentext, eine fehlende oder eine zusätzliche Zeile, eine Zeile ohne Nummer
 * {@code A<n>} (z. B. {@code | B1 |} oder {@code |A15|}) und eine abweichende Aktion machen den Test rot.
 */
class RechtematrixSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REQUIREMENTS = Path.of("../.kiro/specs/01-archiv-kern/requirements.md");
    private static final Path WISSEN_REQUIREMENTS = Path.of("../.kiro/specs/04-wissensbasis/requirements.md");
    private static final Path VERWALTUNG_REQUIREMENTS =
            Path.of("../.kiro/specs/06-verwaltung-einstellungen-benutzer/requirements.md");
    /** Spec 09, Requirement 15.1: A25 bis A29 (Stapel, Quellen, Begründungen von Lücken; Aufgabe 27.2). */
    private static final Path PRODUKTIVIMPORT_REQUIREMENTS =
            Path.of("../.kiro/specs/09-produktivimport/requirements.md");
    private static final Path MASCHINENLESBARE_MATRIX = Path.of("src/test/resources/rechtematrix.json");
    private static final Pattern ZEILE = Pattern.compile("^\\| (A\\d+) \\|.*");
    private static final Pattern TRENNZEILE = Pattern.compile("^\\|( --- \\|)+$");
    private static final List<String> KOPF = List.of("Nr.", "Aktion", "`inhaber`", "`mitarbeiter`", "`treuhaender`",
            "`geschaeftsstelle` ¹");

    @Test
    void domainEntsprichtDerSpec() throws IOException {
        Map<Aktion, Map<Rolle, Ergebnis>> spec = new java.util.LinkedHashMap<>(
                lesen(Files.readAllLines(REQUIREMENTS)));
        Map<Aktion, Map<Rolle, Ergebnis>> wissen = lesen(Files.readAllLines(WISSEN_REQUIREMENTS));
        Map<Aktion, Map<Rolle, Ergebnis>> verwaltung = lesen(Files.readAllLines(VERWALTUNG_REQUIREMENTS));
        Map<Aktion, Map<Rolle, Ergebnis>> produktivimport = lesen(Files.readAllLines(PRODUKTIVIMPORT_REQUIREMENTS));
        List<Map<Aktion, Map<Rolle, Ergebnis>>> quellen = List.of(spec, wissen, verwaltung, produktivimport);
        for (int i = 0; i < quellen.size(); i++) {
            for (int j = i + 1; j < quellen.size(); j++) {
                assertThat(quellen.get(i).keySet()).doesNotContainAnyElementsOf(quellen.get(j).keySet());
            }
        }
        assertThat(produktivimport.keySet()).containsExactly(Aktion.A25, Aktion.A26, Aktion.A27, Aktion.A28,
                Aktion.A29);
        spec.putAll(wissen);
        spec.putAll(verwaltung);
        spec.putAll(produktivimport);

        assertThat(spec.keySet()).containsExactlyInAnyOrder(Aktion.values());
        spec.forEach((aktion, zeile) -> zeile.forEach((rolle, ergebnis) ->
                assertThat(Rechtematrix.ergebnis(rolle, aktion)).as(aktion + " " + rolle.code()).isEqualTo(ergebnis)));
    }

    @Test
    void maschinenlesbareMatrixEntsprichtSpecsUndHttpErgebnissen() throws IOException {
        JsonNode json = JSON.readTree(MASCHINENLESBARE_MATRIX.toFile());
        assertThat(json.path("nichtAngemeldet").asText()).isEqualTo("401");

        Map<Aktion, Map<Rolle, Ergebnis>> spec = new java.util.LinkedHashMap<>(
                lesen(Files.readAllLines(REQUIREMENTS)));
        spec.putAll(lesen(Files.readAllLines(WISSEN_REQUIREMENTS)));
        spec.putAll(lesen(Files.readAllLines(VERWALTUNG_REQUIREMENTS)));
        spec.putAll(lesen(Files.readAllLines(PRODUKTIVIMPORT_REQUIREMENTS)));

        Map<Aktion, JsonNode> dateizeilen = new java.util.LinkedHashMap<>();
        json.path("aktionen").forEach(zeile -> {
            Aktion aktion = Aktion.valueOf(zeile.path("id").asText());
            assertThat(zeile.path("quelle").asText()).as(aktion.toString()).isEqualTo(quelle(aktion));
            assertThat(dateizeilen.put(aktion, zeile)).as("doppelte Zeile " + aktion).isNull();
        });
        assertThat(dateizeilen.keySet()).containsExactlyInAnyOrderElementsOf(spec.keySet());

        spec.forEach((aktion, rollen) -> rollen.forEach((rolle, ergebnis) -> {
            JsonNode zelle = dateizeilen.get(aktion).path("rollen").path(rolle.code());
            assertThat(RechtematrixZelle.ergebnis(zelle.path("ergebnis").asText()))
                    .as(aktion + " " + rolle.code()).isEqualTo(ergebnis);
            assertThat(zelle.path("http")).as(aktion + " " + rolle.code() + " HTTP")
                    .isEqualTo(JSON.valueToTree(httpErgebnisse(ergebnis)));
        }));
    }

    @Test
    void unbekannterZellentextIstRot() {
        assertThatThrownBy(() -> lesen(List.of(
                "| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |",
                "| A1 | Dokumente erfassen (Upload, Handy) | erlaubt | erlaubt | vielleicht | erlaubt |")))
                .hasMessageContaining("vielleicht");
    }

    @Test
    void zusaetzlicheZeileIstRot() {
        assertThatThrownBy(() -> lesen(List.of(
                "| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |",
                "| A99 | Neu | erlaubt | erlaubt | erlaubt | erlaubt |")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeileOhneNummerIstRot() {
        for (String zeile : List.of("| B1 | Neu | erlaubt | erlaubt | erlaubt | erlaubt |",
                "|A15| Neu | erlaubt | erlaubt | erlaubt | erlaubt |")) {
            assertThatThrownBy(() -> lesen(List.of(
                    "| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |",
                    "| --- | --- | --- | --- | --- | --- |", zeile)))
                    .as(zeile).hasMessageContaining("ohne Nummer");
        }
    }

    @Test
    void abweichendeAktionIstRot() {
        assertThatThrownBy(() -> lesen(List.of(
                "| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |",
                "| A7 | Freigeben | erlaubt | 403 | 403 | erlaubt |")))
                .hasMessageContaining("Bestätigen");
    }

    /**
     * Liest die Zeilen der ersten Tabelle mit dem Kopf von Spec 01, Req 2.6 in einer {@code requirements.md}; spätere
     * Tabellen derselben Datei nicht. Jede der vier Quellen hat genau eine solche Tabelle.
     */
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
            if (!inTabelle || TRENNZEILE.matcher(zeile).matches()) {
                continue;
            }
            if (!ZEILE.matcher(zeile).matches()) {
                throw new IllegalArgumentException("Zeile der Rechtematrix ohne Nummer A<n>: " + zeile);
            }
            List<String> z = zellen(zeile);
            assertThat(z).as(zeile).hasSize(KOPF.size());
            Aktion aktion = Aktion.valueOf(z.getFirst());
            assertThat(z.get(1).replace("`", "")).as("Aktion " + aktion).isEqualTo(aktion.bezeichnung());
            Map<Rolle, Ergebnis> ergebnisse = new EnumMap<>(Rolle.class);
            Rolle[] rollen = Rolle.values();
            for (int i = 0; i < rollen.length; i++) {
                ergebnisse.put(rollen[i], RechtematrixZelle.ergebnis(z.get(2 + i)));
            }
            assertThat(matrix.put(aktion, ergebnisse)).as("doppelte Zeile " + aktion).isNull();
        }
        return matrix;
    }

    private static List<String> zellen(String zeile) {
        String innen = zeile.strip().substring(1, zeile.strip().length() - 1);
        return Arrays.stream(innen.split("\\|")).map(String::strip).toList();
    }

    private static String quelle(Aktion aktion) {
        if (aktion.compareTo(Aktion.A15) >= 0 && aktion.compareTo(Aktion.A19) <= 0) {
            return "Spec 04, Req 12.1";
        }
        if (aktion.compareTo(Aktion.A20) >= 0 && aktion.compareTo(Aktion.A23) <= 0) {
            return "Spec 06, Req 4.1";
        }
        if (aktion == Aktion.A24) {
            return "Spec 02, Req 15.5";
        }
        if (aktion.compareTo(Aktion.A25) >= 0 && aktion.compareTo(Aktion.A29) <= 0) {
            return "Spec 09, Req 15.1";
        }
        return "Spec 01, Req 2.6";
    }

    static List<String> httpErgebnisse(Ergebnis ergebnis) {
        return switch (ergebnis) {
            case ERLAUBT, NUR_LESEND, NUR_SELBST_ERFASSTE -> List.of("2XX");
            case VERBOTEN -> List.of("403");
            case NICHT_SICHTBAR -> List.of("404");
            case NUR_EIGENE -> List.of("2XX", "404");
        };
    }
}
