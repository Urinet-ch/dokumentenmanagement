package ch.urinet.archiv.integration;

import ch.urinet.archiv.ArchivMain;
import ch.urinet.archiv.integration.webseite.gegenprobe.GegenprobeResource;
import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.jandex.IndexView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 11.2 (Spec 03): Die Pfade unter {@code /public/}, die der Code beschreibt, und der Vertrag
 * {@code docs/schnittstelle/public-v1.yaml} stimmen überein. Der Code wird mit dem Scanner von smallrye-open-api auf
 * einem Jandex-Index aller Klassen aus {@code src/main} beschrieben, ohne Quarkus-Start und ohne den Ausschluss der
 * App-OpenAPI ({@link PublicVertrag}). Die Gegenproben fügen nur dem Index dieses Tests eine Resource hinzu
 * ({@link GegenprobeResource}) bzw. lesen abgewandelte Dateien.
 */
class PublicVertragTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path VERTRAG = REPO.resolve("docs/schnittstelle/public-v1.yaml");

    private static IndexView hauptIndex;
    private static Map<String, String> einstellungen;
    private static String vertrag;
    private static String gegenprobe;

    @BeforeAll
    static void vorbereiten() throws IOException, URISyntaxException {
        Path klassen = Path.of(ArchivMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        hauptIndex = PublicVertrag.index(klassen);
        einstellungen = PublicVertrag.appEinstellungen(klassen.resolve("application.properties"), false);
        vertrag = Files.readString(VERTRAG, StandardCharsets.UTF_8);
        try (InputStream ein = PublicVertragTest.class.getResourceAsStream("/vertrag/gegenprobe.yaml")) {
            gegenprobe = new String(ein.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void dateiIstGueltigesOpenApi31() {
        JsonNode roh = PublicVertrag.roh(vertrag);
        assertThat(PublicVertrag.befunde(roh, 1)).isEmpty();
        JsonNode gelesen = PublicVertrag.ausDatei(vertrag);
        assertThat(gelesen.path("openapi").asText()).isEqualTo(roh.path("openapi").asText());
        for (String art : List.of("schemas", "responses", "headers", "securitySchemes")) {
            assertThat(namen(gelesen.path("components").path(art))).as(art)
                    .isNotEmpty().isEqualTo(namen(roh.path("components").path(art)));
        }
    }

    @Test
    void codeUndDateiBeschreibenDieselbenPfade() {
        Map<String, Object> code = PublicVertrag.pfade(PublicVertrag.ausCode(hauptIndex, einstellungen));
        Map<String, Object> datei = PublicVertrag.pfade(PublicVertrag.ausDatei(vertrag));
        assertThat(PublicVertrag.unterschiede(code, datei)).isEmpty();
    }

    @Test
    void scannerBeschreibtDieApp() {
        JsonNode app = PublicVertrag.ausCode(hauptIndex, einstellungen);
        assertThat(app.path("paths").size()).as("die App beschreibt Pfade (Scanner läuft)").isPositive();
    }

    @Test
    void gegenprobeGleicheBeschreibungIstGruen() throws IOException {
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class), datei(gegenprobe))).isEmpty();
    }

    @Test
    void gegenprobeEndpunktNurImCodeIstRot() throws IOException {
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class), datei(vertrag)))
                .containsExactly("/public/v1/gegenprobe/{id}: nur im Code");
    }

    @Test
    void gegenprobeEndpunktNurInDerDateiIstRot() {
        assertThat(PublicVertrag.unterschiede(PublicVertrag.pfade(PublicVertrag.ausCode(hauptIndex, einstellungen)),
                datei(gegenprobe))).containsExactly("/public/v1/gegenprobe/{id}: nur in der Datei");
    }

    @Test
    void gegenprobeAbweichenderStatuscodeIstRot() throws IOException {
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class),
                datei(ersetzen(gegenprobe, "\"404\":", "\"410\":"))))
                .contains("/public/v1/gegenprobe/{id}/get/antworten/404: nur im Code",
                        "/public/v1/gegenprobe/{id}/get/antworten/410: nur in der Datei");
    }

    @Test
    void gegenprobeAbweichenderParameterIstRot() throws IOException {
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class),
                datei(ersetzen(gegenprobe, "name: stufe", "name: ebene"))))
                .contains("/public/v1/gegenprobe/{id}/get/parameter/query:stufe: nur im Code");
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class),
                datei(ersetzen(gegenprobe, "in: query\n", "in: query\n          required: true\n"))))
                .containsExactly("/public/v1/gegenprobe/{id}/get/parameter/query:stufe/pflicht: Code false, Datei true");
    }

    @Test
    void gegenprobeAbweichendesSchemaIstRot() throws IOException {
        String integer = ersetzen(gegenprobe, "titel:\n          type: string", "titel:\n          type: integer");
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class), datei(integer))).singleElement()
                .asString().contains("/antworten/200/application/json/properties/titel/type");
        String ohneFeld = ersetzen(gegenprobe, "        titel:\n          type: string\n", "");
        assertThat(PublicVertrag.unterschiede(code(GegenprobeResource.class, GegenprobeResource.Eintrag.class), datei(ohneFeld))).singleElement()
                .asString().endsWith("/properties/titel: nur im Code");
    }

    @Test
    void gegenprobeUngueltigeDateiHatBefunde() {
        assertThat(PublicVertrag.befunde(PublicVertrag.roh(ersetzen(vertrag, "openapi: 3.1.0", "openapi: 3.0.3")), 1))
                .contains("openapi ist nicht 3.1.x");
        assertThat(PublicVertrag.befunde(PublicVertrag.roh(ersetzen(vertrag,
                "\"#/components/schemas/Problem\"", "\"#/components/schemas/Fehlt\"")), 1))
                .isNotEmpty().allSatisfy(b -> assertThat(b).startsWith("Referenz löst nicht auf"));
        assertThat(PublicVertrag.befunde(PublicVertrag.roh(gegenprobe), 2))
                .containsExactly("Pfad ausserhalb /public/v2/: /public/v1/gegenprobe/{id}");
        assertThat(PublicVertrag.befunde(PublicVertrag.roh(ersetzen(gegenprobe, "\"404\":", "\"4044\":")), 1))
                .containsExactly("/public/v1/gegenprobe/{id} get: Statuscode 4044");
    }

    private static Map<String, Object> code(Class<?>... zusaetzlich) throws IOException {
        return PublicVertrag.pfade(PublicVertrag.ausCode(PublicVertrag.mit(hauptIndex, zusaetzlich), einstellungen));
    }

    private static Map<String, Object> datei(String yaml) {
        return PublicVertrag.pfade(PublicVertrag.ausDatei(yaml));
    }

    private static String ersetzen(String text, String alt, String neu) {
        assertThat(text).contains(alt);
        return text.replace(alt, neu);
    }

    private static List<String> namen(JsonNode knoten) {
        List<String> namen = new java.util.ArrayList<>();
        knoten.fieldNames().forEachRemaining(namen::add);
        namen.sort(null);
        return namen;
    }
}
