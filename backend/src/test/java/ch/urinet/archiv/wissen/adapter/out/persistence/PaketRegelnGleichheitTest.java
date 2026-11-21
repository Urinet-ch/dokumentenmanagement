package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.WissenImportSchritte;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketBeispiele;
import ch.urinet.archiv.wissenpaket.PaketWerkzeugTestHelfer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PaketRegelnGleichheitTest {
    @Inject WissenImportSchritte schritte;
    @TempDir Path temp;
    private static final Set<String> ARCHIVABHAENGIG = Set.of(
            "QUELLE_UNBEKANNT", "QUELLE_PERSONAL", "AUSZUG_NICHT_PRUEFBAR", "AUSZUG_NICHT_GEFUNDEN",
            "SEITE_FEHLT", "CLOUD_STUFE_UNZULAESSIG", "STUFE_UEBERBLICK", "AUSSAGE_ENTFERNT");
    private static final Set<String> LOKALE_HINWEISE = Set.of("QUELLE_NICHT_GEPRUEFT", "PAKETKOPF_NICHT_GEPRUEFT",
            "BASIS_UNBEKANNT", "STUFE_STEIGT", "HOEHERE_STUFE_VORHANDEN");

    @BeforeEach
    void vorbereiten() throws Exception {
        schritte.vorbereiten();
    }

    @AfterEach
    void aufraeumen() throws Exception {
        schritte.aufraeumen();
    }

    static Stream<Arguments> beispiele() throws Exception {
        return WissensPaketBeispiele.alle().stream().map(b -> Arguments.of(b.name(), b));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beispiele")
    void gleicheRegeln(String name, WissensPaketBeispiele.Beispiel beispiel) throws Exception {
        Response antwort = schritte.regelgleichheit(beispiel);
        JsonNode offline = PaketWerkzeugTestHelfer.pruefen(beispiel.zip(), temp.resolve("paket.zip"));
        JsonNode rest = new ObjectMapper().readTree(antwort.asString());
        List<Meldung> restBefunde;
        if (antwort.statusCode() == 413 || antwort.statusCode() == 415) {
            assertThat(rest.path("code").isTextual()).as(name + " Fehlercode").isTrue();
            restBefunde = List.of(new Meldung(rest.path("code").asText(), ""));
        } else {
            restBefunde = meldungen(rest.path("befunde"), ARCHIVABHAENGIG);
        }
        assertThat(restBefunde).as(name + " Befunde")
                .containsExactlyElementsOf(meldungen(offline.path("befunde"), ARCHIVABHAENGIG));
        if (antwort.statusCode() == 201) {
            assertThat(meldungen(rest.path("hinweise"), LOKALE_HINWEISE)).as(name + " Hinweise")
                    .containsExactlyElementsOf(meldungen(offline.path("hinweise"), LOKALE_HINWEISE));
        }
    }

    private record Meldung(String code, String pfad) { }

    private static List<Meldung> meldungen(JsonNode knoten, Set<String> ausnahmen) {
        assertThat(knoten.isArray()).as("Meldungen müssen ein Array sein").isTrue();
        List<Meldung> meldungen = new ArrayList<>();
        for (JsonNode eintrag : knoten) {
            assertThat(eintrag.path("code").isTextual()).isTrue();
            assertThat(eintrag.path("pfad").isTextual()).isTrue();
            String code = eintrag.path("code").asText();
            if (!ausnahmen.contains(code)) meldungen.add(new Meldung(code, eintrag.path("pfad").asText()));
        }
        return meldungen.stream().sorted(Comparator.comparing(Meldung::code).thenComparing(Meldung::pfad)).toList();
    }
}
