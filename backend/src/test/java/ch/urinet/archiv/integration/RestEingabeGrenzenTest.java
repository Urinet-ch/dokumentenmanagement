package ch.urinet.archiv.integration;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Die globale Body-Grenze muss für jede Operation mit Request-Body vor der REST-Ressource greifen. */
@QuarkusTest
class RestEingabeGrenzenTest {

    @TempDir
    Path tempDir;

    private static final Path OPENAPI = Path.of("../frontend/openapi/openapi.json");
    private static final Pattern PFAD_PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "GRENZTEST-EINGABE-NICHT-ECHO-4711";
    private static final int TEST_GRENZE = 1024 * 1024;
    private static final String MULTIPART = "multipart/form-data";
    private static final List<String> HTTP_METHODEN = List.of("get", "post", "put", "patch", "delete", "head");

    @Test
    void jedeJsonOperationMitBodyLehntUeberlangeAnfrageOhneEchoOderStacktraceAb() throws IOException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        byte[] body = uebergrosseAnfrage();
        String token = Testanmeldung.token("inhaber-urinet");
        List<String> geprueft = new ArrayList<>();

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            openApi.path("paths").fields().forEachRemaining(pfad -> pfad.getValue().fields().forEachRemaining(operation -> {
                if (!HTTP_METHODEN.contains(operation.getKey())
                        || !enthaeltJsonBody(operation.getValue().path("requestBody"))) {
                    return;
                }

                String ziel = pfadMitBeispielwerten(pfad.getKey());
                Response antwort = given()
                        .auth().oauth2(token)
                        .config(io.restassured.RestAssured.config().httpClient(
                                io.restassured.config.HttpClientConfig.httpClientConfig()
                                        .setParam("http.protocol.expect-continue", true)))
                        .contentType("application/json")
                        .body(body)
                        .when().request(operation.getKey().toUpperCase(), ziel);

                String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey();
                geprueft.add(endpoint);
                assertThat(antwort.statusCode()).as(endpoint + " muss die Body-Grenze anwenden")
                        .isEqualTo(413);
                assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                        .doesNotContain(MARKER, "Exception", "at ch.urinet");
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER);
        }
    }

    @Test
    void jedeMultipartOperationLehntUebergrosseAnfrageOhneEchoOderStacktraceAb()
            throws IOException, InterruptedException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        List<String> geprueft = new ArrayList<>();
        String token = Testanmeldung.token("inhaber-urinet");
        long grenze = ConfigProvider.getConfig().getValue("archiv.upload.max-anfrage", MemorySize.class)
                .asLongValue();
        Path body = sparseAnfrage(grenze + 1);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            var pfade = openApi.path("paths").fields();
            while (pfade.hasNext()) {
                var pfad = pfade.next();
                var operationen = pfad.getValue().fields();
                while (operationen.hasNext()) {
                    var operation = operationen.next();
                    if (!HTTP_METHODEN.contains(operation.getKey())
                            || !operation.getValue().path("requestBody").path("content").has(MULTIPART)) {
                        continue;
                    }

                    String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey();
                    HttpRequest anfrage = HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port
                                    + pfadMitBeispielwerten(pfad.getKey())))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", MULTIPART + "; boundary=grenztest")
                            .expectContinue(true)
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofFile(body))
                            .build();
                    HttpResponse<String> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofString());

                    geprueft.add(endpoint);
                    assertThat(antwort.statusCode()).as(endpoint + " muss die Multipart-Grenze anwenden")
                            .isEqualTo(413);
                    assertThat(antwort.body()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");
                }
            }

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER);
        }
    }

    @Test
    void jsonOperationenLehnenFalschenBodyTypOhneEchoOderStacktraceAb() throws IOException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        List<String> geprueft = new ArrayList<>();
        String token = Testanmeldung.token("inhaber-urinet");
        String body = "[\"" + MARKER + "\"]";

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            openApi.path("paths").fields().forEachRemaining(pfad -> pfad.getValue().fields().forEachRemaining(operation -> {
                if (!HTTP_METHODEN.contains(operation.getKey())) {
                    return;
                }
                JsonNode requestBody = operation.getValue().path("requestBody");
                if (!requestBody.path("content").has("application/json")) {
                    return;
                }

                String ziel = pfadMitBeispielwerten(pfad.getKey());
                Response antwort = given()
                        .auth().oauth2(token)
                        .contentType("application/json")
                        .body(body)
                        .when().request(operation.getKey().toUpperCase(), ziel);

                String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey();
                geprueft.add(endpoint);
                assertThat(antwort.statusCode()).as(endpoint + " muss den JSON-Typ ablehnen")
                        .isIn(400, 403, 413, 422);
                assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                        .doesNotContain(MARKER, "Exception", "at ch.urinet");
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER);
        }
    }

    @Test
    void jsonFelderUeberschreitenIhreOpenApiMaxLengthGrenzeNichtUnbemerkt() throws IOException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        List<String> geprueft = new ArrayList<>();
        String token = Testanmeldung.token("inhaber-urinet");

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            openApi.path("paths").fields().forEachRemaining(pfad -> pfad.getValue().fields().forEachRemaining(operation -> {
                if (!HTTP_METHODEN.contains(operation.getKey())) {
                    return;
                }
                JsonNode content = operation.getValue().path("requestBody").path("content").path("application/json");
                if (content.isMissingNode()) {
                    return;
                }
                JsonNode schema = aufgeloestesSchema(openApi, content.path("schema"));
                JsonNode eigenschaften = schema.path("properties");
                eigenschaften.fields().forEachRemaining(feld -> {
                    JsonNode grenze = feld.getValue().path("maxLength");
                    if (!grenze.canConvertToInt()) {
                        return;
                    }

                    int maxLength = grenze.asInt();
                    String body = JSON.valueToTree(Map.of(feld.getKey(), MARKER + "x".repeat(maxLength))).toString();
                    Response antwort = given()
                            .auth().oauth2(token)
                            .contentType("application/json")
                            .body(body)
                            .when().request(operation.getKey().toUpperCase(), pfadMitBeispielwerten(pfad.getKey()));

                    String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey() + " (" + feld.getKey() + ")";
                    geprueft.add(endpoint);
                    assertThat(antwort.statusCode()).as(endpoint + " muss die Zeichenlänge ablehnen")
                            .isIn(400, 403, 413, 422);
                    assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");
                });
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER);
        }
    }

    @Test
    void jsonListenLehnenUngueltigeElementtypenUndUeberschreitungenVonMaxItemsAb() throws IOException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        List<String> geprueft = new ArrayList<>();
        String token = Testanmeldung.token("inhaber-urinet");

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            openApi.path("paths").fields().forEachRemaining(pfad -> pfad.getValue().fields().forEachRemaining(operation -> {
                if (!HTTP_METHODEN.contains(operation.getKey())) {
                    return;
                }
                JsonNode content = operation.getValue().path("requestBody").path("content").path("application/json");
                if (content.isMissingNode()) {
                    return;
                }
                JsonNode schema = aufgeloestesSchema(openApi, content.path("schema"));
                List<ArrayEingabe> arrays = new ArrayList<>();
                sammleArrays(openApi, schema, List.of(), arrays);
                for (ArrayEingabe array : arrays) {
                    String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey() + " ("
                            + String.join(".", array.eltern()) + (array.eltern().isEmpty() ? "" : ".")
                            + array.name() + ")";
                    JsonNode items = aufgeloestesSchema(openApi, array.schema().path("items"));

                    ObjectNode falscherTyp = bodyMitArray(array, JSON);
                    if ("string".equals(items.path("type").asText())) {
                        arrayFeld(falscherTyp, array).addObject().put(MARKER, "falscher Typ");
                    } else {
                        arrayFeld(falscherTyp, array).add(MARKER);
                    }
                    Response typAntwort = jsonAnfrage(token, operation.getKey(), pfad.getKey(), falscherTyp);
                    geprueft.add(endpoint + " Typ");
                    assertThat(typAntwort.statusCode()).as(endpoint + " muss den Elementtyp ablehnen")
                            .isIn(400, 403, 413, 422);
                    assertThat(typAntwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");

                    JsonNode maxItems = array.schema().path("maxItems");
                    if (maxItems.canConvertToInt()) {
                        ObjectNode zuViele = bodyMitArray(array, JSON);
                        ArrayNode werte = arrayFeld(zuViele, array);
                        for (int i = 0; i <= maxItems.asInt(); i++) {
                            werte.add(gueltigerArraywert(items));
                        }
                        Response mengeAntwort = jsonAnfrage(token, operation.getKey(), pfad.getKey(), zuViele);
                        geprueft.add(endpoint + " maxItems");
                        assertThat(mengeAntwort.statusCode()).as(endpoint + " muss maxItems anwenden")
                                .isIn(400, 403, 413, 422);
                        assertThat(mengeAntwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                                .doesNotContain(MARKER, "Exception", "at ch.urinet");
                    }
                }
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER);
        }
    }

    @Test
    void numerischeQueryParameterLehnenUngueltigeTypenOhneStacktraceAb() throws IOException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        List<String> geprueft = new ArrayList<>();
        String token = Testanmeldung.token("inhaber-urinet");

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            openApi.path("paths").fields().forEachRemaining(pfad -> {
                JsonNode get = pfad.getValue().path("get");
                if (get.isMissingNode()) {
                    return;
                }
                get.path("parameters").forEach(parameter -> {
                    if (!"query".equals(parameter.path("in").asText())
                            || !List.of("integer", "number").contains(parameter.path("schema").path("type").asText())) {
                        return;
                    }

                    String name = parameter.path("name").asText();
                    String ziel = pfadMitBeispielwerten(pfad.getKey());
                    Response antwort = given()
                            .auth().oauth2(token)
                            .queryParam(name, MARKER)
                            .when().get(ziel);

                    String endpoint = "GET " + pfad.getKey() + "?" + name;
                    geprueft.add(endpoint);
                    assertThat(antwort.statusCode()).as(endpoint + " muss den numerischen Typ ablehnen")
                            .isIn(400, 403);
                    assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");

                    JsonNode maximum = parameter.path("schema").path("maximum");
                    if (maximum.canConvertToLong()) {
                        long zuGross = maximum.asLong() + 1;
                        Response grenzAntwort = given()
                                .auth().oauth2(token)
                                .queryParam(name, zuGross)
                                .when().get(ziel);

                        geprueft.add(endpoint + " max");
                        assertThat(grenzAntwort.statusCode()).as(endpoint + " muss den Maximalwert anwenden")
                                .isIn(400, 403, 404, 413, 422);
                        assertThat(grenzAntwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                                .doesNotContain("Exception", "at ch.urinet");
                    }
                });
            });

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER);
        }
    }

    private static byte[] uebergrosseAnfrage() {
        byte[] body = new byte[TEST_GRENZE + 1];
        java.util.Arrays.fill(body, (byte) ' ');
        byte[] marker = MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, body, 0, marker.length);
        return body;
    }

    private Path sparseAnfrage(long laenge) throws IOException {
        Path body = Files.createTempFile(tempDir, "grenztest-", ".bin");
        try (FileChannel kanal = FileChannel.open(body, StandardOpenOption.WRITE)) {
            kanal.write(ByteBuffer.wrap(MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            kanal.position(laenge - 1);
            kanal.write(ByteBuffer.wrap(new byte[]{' '}));
        }
        return body;
    }

    private static boolean enthaeltJsonBody(JsonNode requestBody) {
        JsonNode content = requestBody.path("content");
        return content.has("application/json") || content.has("application/merge-patch+json");
    }

    private static JsonNode aufgeloestesSchema(JsonNode openApi, JsonNode schema) {
        JsonNode aktuelles = schema;
        while (aktuelles.has("$ref")) {
            aktuelles = openApi.at(aktuelles.path("$ref").asText().substring(1));
        }
        return aktuelles;
    }

    private static void sammleArrays(JsonNode openApi, JsonNode schema, List<String> eltern,
                                     List<ArrayEingabe> gefunden) {
        JsonNode objekt = aufgeloestesSchema(openApi, schema);
        objekt.path("properties").fields().forEachRemaining(feld -> {
            JsonNode eigenschaft = aufgeloestesSchema(openApi, feld.getValue());
            if ("array".equals(eigenschaft.path("type").asText())) {
                gefunden.add(new ArrayEingabe(eltern, feld.getKey(), eigenschaft));
            } else if ("object".equals(eigenschaft.path("type").asText())) {
                List<String> verschachtelt = new ArrayList<>(eltern);
                verschachtelt.add(feld.getKey());
                sammleArrays(openApi, eigenschaft, verschachtelt, gefunden);
            }
        });
    }

    private static ObjectNode bodyMitArray(ArrayEingabe array, ObjectMapper json) {
        ObjectNode body = json.createObjectNode();
        ObjectNode objekt = body;
        for (String eltern : array.eltern()) {
            objekt = objekt.putObject(eltern);
        }
        objekt.putArray(array.name());
        return body;
    }

    private static ArrayNode arrayFeld(ObjectNode body, ArrayEingabe array) {
        JsonNode objekt = body;
        for (String eltern : array.eltern()) {
            objekt = objekt.path(eltern);
        }
        return (ArrayNode) objekt.path(array.name());
    }

    private static JsonNode gueltigerArraywert(JsonNode schema) {
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            return schema.path("enum").get(0);
        }
        return switch (schema.path("type").asText()) {
            case "integer", "number" -> JSON.getNodeFactory().numberNode(1);
            case "boolean" -> JSON.getNodeFactory().booleanNode(true);
            case "object" -> JSON.createObjectNode();
            case "array" -> JSON.createArrayNode();
            default -> JSON.getNodeFactory().textNode("grenztest");
        };
    }

    private static Response jsonAnfrage(String token, String methode, String pfad, ObjectNode body) {
        return given()
                .auth().oauth2(token)
                .contentType("application/json")
                .body(body.toString())
                .when().request(methode.toUpperCase(), pfadMitBeispielwerten(pfad));
    }

    private record ArrayEingabe(List<String> eltern, String name, JsonNode schema) {
    }

    private static String pfadMitBeispielwerten(String pfad) {
        Matcher matcher = PFAD_PARAMETER.matcher(pfad);
        StringBuffer ergebnis = new StringBuffer();
        while (matcher.find()) {
            String wert = switch (matcher.group(1)) {
                case "code", "art", "wert", "kennung" -> "grenztest";
                case "nr", "nummer" -> "1";
                default -> "00000000-0000-4000-8000-000000000001";
            };
            matcher.appendReplacement(ergebnis, Matcher.quoteReplacement(wert));
        }
        matcher.appendTail(ergebnis);
        return ergebnis.toString();
    }
}
