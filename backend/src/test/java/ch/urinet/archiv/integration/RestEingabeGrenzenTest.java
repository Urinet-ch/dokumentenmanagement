package ch.urinet.archiv.integration;

import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die globale Body-Grenze muss für jede Operation mit Request-Body vor der REST-Ressource greifen.
 * Die Prüfung auf Eingaben und Stacktraces in Antwort und Log ergänzt den Nachweis zu {@code @req-16.7}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestEingabeGrenzenTest {

    @jakarta.inject.Inject
    StepUp stepUp;

    @jakarta.inject.Inject
    MandantProvisionierung provisionierung;

    @jakarta.inject.Inject
    AgroalDataSource datenbank;

    @TempDir
    Path tempDir;

    @TestHTTPResource
    URI basis;

    private StepUp originalStepUp;
    private boolean testDokumentartAngelegt;
    private UUID testDokumentId;
    private String testKontaktId;

    private static final Path OPENAPI = Path.of("../frontend/openapi/openapi.json");
    private static final Pattern PFAD_PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "GRENZTEST-EINGABE-NICHT-ECHO-4711";
    private static final String TEST_DOKUMENTART = "GRENZTEST";
    private static final String MULTIPART = "multipart/form-data";
    private static final List<String> HTTP_METHODEN = List.of("get", "post", "put", "patch", "delete", "head");

    @BeforeAll
    void testMandantenSicherstellenUndStepUpErmoeglichen() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
        Testdokumente.probeDokumenteAnlegen(datenbank);
        testDokumentId = Testdokumente.anlegen(datenbank,
                new Testdokumente.Angaben("urinet", null, "NORMAL", false, "probe", null, "EINGANGSKORB"));
        originalStepUp = ClientProxy.unwrap(stepUp);
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() {
            }
        }, StepUp.class);
        testDokumentartSicherstellen();
        testKontaktId = kontaktAnlegen();
    }

    @AfterAll
    void testdatenLoeschenUndStepUpWiederherstellen() throws SQLException {
        kontaktLoeschen();
        if (testDokumentId != null) {
            Testdokumente.loeschen(datenbank, "urinet", List.of(testDokumentId));
        }
        testDokumentartLoeschen();
        if (originalStepUp != null) {
            QuarkusMock.installMockForType(originalStepUp, StepUp.class);
        }
    }

    @Test
    void jedeJsonOperationMitBodyLehntUeberlangeAnfrageOhneEchoOderStacktraceAb()
            throws IOException, InterruptedException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        long grenze = ConfigProvider.getConfig().getValue("archiv.http.max-anfrage", MemorySize.class)
                .asLongValue();
        Path body = sparseAnfrage(Math.addExact(grenze, 1));
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        List<String> geprueft = new ArrayList<>();

        try (LogMitschnitt logs = LogMitschnitt.starten()) {
            var pfade = openApi.path("paths").fields();
            while (pfade.hasNext()) {
                var pfad = pfade.next();
                var operationen = pfad.getValue().fields();
                while (operationen.hasNext()) {
                    var operation = operationen.next();
                    if (!HTTP_METHODEN.contains(operation.getKey())
                            || !enthaeltJsonBody(operation.getValue().path("requestBody"))) {
                        continue;
                    }

                    String ziel = pfadMitBeispielwerten(pfad.getKey());
                    String token = Testanmeldung.token("inhaber-urinet");
                    HttpRequest anfrage = HttpRequest.newBuilder(basis.resolve(ziel))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .expectContinue(true)
                            .timeout(Duration.ofSeconds(30))
                            .method(operation.getKey().toUpperCase(), HttpRequest.BodyPublishers.ofFile(body))
                            .build();
                    HttpResponse<String> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofString());

                    String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey();
                    geprueft.add(endpoint);
                    assertThat(antwort.statusCode()).as(endpoint + " muss die Body-Grenze anwenden")
                            .isEqualTo(413);
                    assertThat(antwort.body()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");
                }
            }

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER, "NumberFormatException", "\tat ");
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
                    HttpRequest anfrage = HttpRequest.newBuilder(basis.resolve(pfadMitBeispielwerten(pfad.getKey())))
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
            assertThat(logs.volltext()).doesNotContain(MARKER, "NumberFormatException", "\tat ");
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
                Response antwort = anfrage(token, operation.getKey(), ziel)
                        .contentType("application/json")
                        .body(body)
                        .when().request(operation.getKey().toUpperCase(), ziel);

                String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey();
                geprueft.add(endpoint);
                assertThat(antwort.statusCode()).as(endpoint + " muss den JSON-Typ ablehnen; fehlercode="
                                + antwort.path("fehlercode"))
                        .isIn(400, 413, 422);
                assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                        .doesNotContain(MARKER, "Exception", "at ch.urinet");
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER, "NumberFormatException", "\tat ");
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
                    Response antwort = anfrage(token, operation.getKey(), pfad.getKey())
                            .contentType("application/json")
                            .body(body)
                            .when().request(operation.getKey().toUpperCase(), pfadMitBeispielwerten(pfad.getKey()));

                    String endpoint = operation.getKey().toUpperCase() + " " + pfad.getKey() + " (" + feld.getKey() + ")";
                    geprueft.add(endpoint);
                    assertThat(antwort.statusCode()).as(endpoint + " muss die Zeichenlänge ablehnen; fehlercode="
                                    + antwort.path("fehlercode"))
                            .isIn(400, 413, 422);
                    assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");
                });
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER, "NumberFormatException", "\tat ");
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
                    assertThat(typAntwort.statusCode()).as(endpoint + " muss den Elementtyp ablehnen; fehlercode="
                                    + typAntwort.path("fehlercode"))
                            .isIn(400, 413, 422);
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
                                .isIn(400, 413, 422);
                        assertThat(mengeAntwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                                .doesNotContain(MARKER, "Exception", "at ch.urinet");
                    }
                }
            }));

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER, "NumberFormatException", "\tat ");
        }
    }

    @Test
    void numerischeQueryParameterLehnenUngueltigeTypenOhneStacktraceAb() throws IOException {
        JsonNode openApi = JSON.readTree(OPENAPI.toFile());
        List<String> geprueft = new ArrayList<>();
        String token = Testanmeldung.token("inhaber-urinet");

        Response kontrollAntwort = http().auth().oauth2(token)
                .queryParam("groesse", 50).when().get("/api/v1/benutzer");
        assertThat(kontrollAntwort.statusCode())
                .as("Gültige Anmeldung und gültiger Query-Parameter für Benutzerliste")
                .isEqualTo(200);

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
                    Response antwort = http()
                            .auth().oauth2(token)
                            .queryParam(name, MARKER)
                            .when().get(ziel);

                    String endpoint = "GET " + pfad.getKey() + "?" + name;
                    geprueft.add(endpoint);
                    assertThat(antwort.statusCode()).as(endpoint + " muss den numerischen Typ ablehnen")
                            .isIn(400, 413, 422);
                    assertThat(antwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                            .doesNotContain(MARKER, "Exception", "at ch.urinet");

                    JsonNode maximum = parameter.path("schema").path("maximum");
                    if (maximum.canConvertToLong()) {
                        long zuGross = maximum.asLong() + 1;
                        Response grenzAntwort = http()
                                .auth().oauth2(token)
                                .queryParam(name, zuGross)
                                .when().get(ziel);

                        geprueft.add(endpoint + " max");
                        assertThat(grenzAntwort.statusCode()).as(endpoint + " muss den Maximalwert anwenden")
                                .isIn(400, 413, 422);
                        assertThat(grenzAntwort.asString()).as(endpoint + " darf Eingaben nicht zurückgeben")
                                .doesNotContain("Exception", "at ch.urinet");
                    }
                });
            });

            assertThat(geprueft).isNotEmpty();
            assertThat(logs.volltext()).doesNotContain(MARKER, "NumberFormatException", "\tat ");
        }
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

    private Response jsonAnfrage(String token, String methode, String pfad, ObjectNode body) {
        return anfrage(token, methode, pfad)
                .contentType("application/json")
                .body(body.toString())
                .when().request(methode.toUpperCase(), pfadMitBeispielwerten(pfad));
    }

    private RequestSpecification anfrage(String token, String methode, String pfad) {
        RequestSpecification anfrage = http().auth().oauth2(token);
        if ("get".equalsIgnoreCase(methode)) {
            return anfrage;
        }
        String pfadBeispiel = pfadMitBeispielwerten(pfad);
        if (pfad.startsWith("/api/v1/dokumentarten")) {
            anfrage.header("If-Match", etag(token, "/api/v1/dokumentarten"));
        } else if (pfad.startsWith("/api/v1/mandant/einstellungen")) {
            anfrage.header("If-Match", etag(token, "/api/v1/mandant/einstellungen"));
        } else if (pfad.startsWith("/api/v1/dokumente/")) {
            String dokumentPfad = dokumentPfad(pfadBeispiel);
            if (dokumentPfad != null) {
                anfrage.header("If-Match", etag(token, dokumentPfad));
            }
        } else if (pfad.startsWith("/api/v1/kontakte/")) {
            String kontaktPfad = kontaktPfad(pfadBeispiel);
            if (kontaktPfad != null) {
                anfrage.header("If-Match", etag(token, kontaktPfad));
            }
        } else if (pfad.startsWith("/api/v1/regeln")) {
            anfrage.header("If-Match", etag(token, "/api/v1/regeln"));
        } else if (pfad.startsWith("/api/v1/wissensbasis/einstellungen")) {
            anfrage.header("If-Match", etag(token, "/api/v1/wissensbasis/einstellungen"));
        }
        return anfrage;
    }

    private static String dokumentPfad(String pfad) {
        String praefix = "/api/v1/dokumente/";
        String id = pfad.substring(praefix.length()).split("/", 2)[0];
        return id.matches("[0-9a-fA-F-]{36}") ? praefix + id : null;
    }

    private static String kontaktPfad(String pfad) {
        String praefix = "/api/v1/kontakte/";
        String id = pfad.substring(praefix.length()).split("/", 2)[0];
        return id.matches("[0-9a-fA-F-]{36}") ? praefix + id : null;
    }

    private void testDokumentartSicherstellen() {
        String token = Testanmeldung.token("inhaber-urinet");
        Response stand = http().auth().oauth2(token).get("/api/v1/dokumentarten");
        assertThat(stand.statusCode())
                .as("Dokumentarten für Grenztest abrufen: " + stand.body().asString())
                .isEqualTo(200);
        if (stand.jsonPath().<List<String>>getList("code").contains(TEST_DOKUMENTART)) {
            http().auth().oauth2(token).header("If-Match", stand.getHeader("ETag"))
                    .delete("/api/v1/dokumentarten/" + TEST_DOKUMENTART).then().statusCode(204);
            stand = http().auth().oauth2(token).get("/api/v1/dokumentarten");
        }
        Response erstellt = http().auth().oauth2(token).header("If-Match", stand.getHeader("ETag"))
                .contentType("application/json")
                .body("""
                        {"code":"GRENZTEST","name":"Grenztest","gruppen":["frei"],"pflichtfelder":[],
                        "vertraulichkeit":"NORMAL","aufbewahrungJahre":null,"gesetzlichePflicht":false}
                        """)
                .post("/api/v1/dokumentarten");
        assertThat(erstellt.statusCode()).as("Dokumentart für Grenztest anlegen: " + erstellt.path("fehlercode"))
                .isEqualTo(201);
        testDokumentartAngelegt = true;
    }

    private void testDokumentartLoeschen() {
        if (!testDokumentartAngelegt) {
            return;
        }
        String token = Testanmeldung.token("inhaber-urinet");
        Response stand = http().auth().oauth2(token).get("/api/v1/dokumentarten");
        assertThat(stand.statusCode()).as("Dokumentarten nach Grenztest abrufen").isEqualTo(200);
        http().auth().oauth2(token).header("If-Match", stand.getHeader("ETag"))
                .delete("/api/v1/dokumentarten/" + TEST_DOKUMENTART).then().statusCode(204);
        testDokumentartAngelegt = false;
    }

    private String kontaktAnlegen() {
        Response erstellt = http().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json")
                .body("""
                        {"typ":"ORGANISATION","name":"Grenztest"}
                        """)
                .post("/api/v1/kontakte");
        assertThat(erstellt.statusCode()).as("Kontakt für Grenztest anlegen").isEqualTo(201);
        return erstellt.path("id");
    }

    private void kontaktLoeschen() throws SQLException {
        if (testKontaktId == null) {
            return;
        }
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "delete from " + new MandantCode("urinet").schema() + ".kontakt where id = ?")) {
            loeschen.setObject(1, UUID.fromString(testKontaktId));
            loeschen.executeUpdate();
        }
        testKontaktId = null;
    }

    private RequestSpecification http() {
        return given().baseUri(basis.toString());
    }

    private String etag(String token, String pfad) {
        Response stand = http().auth().oauth2(token).get(pfad);
        assertThat(stand.statusCode()).as("ETag für " + pfad + " abrufen").isEqualTo(200);
        String etag = stand.getHeader("ETag");
        assertThat(etag).as("ETag für " + pfad).isNotBlank();
        return etag;
    }

    private record ArrayEingabe(List<String> eltern, String name, JsonNode schema) {
    }

    private String pfadMitBeispielwerten(String pfad) {
        Matcher matcher = PFAD_PARAMETER.matcher(pfad);
        StringBuffer ergebnis = new StringBuffer();
        while (matcher.find()) {
            String wert = switch (matcher.group(1)) {
                case "code", "art" -> TEST_DOKUMENTART;
                case "kontaktId" -> testKontaktId;
                case "id" -> pfad.startsWith("/api/v1/dokumente/") ? testDokumentId.toString()
                        : "00000000-0000-4000-8000-000000000001";
                case "wert", "kennung" -> "grenztest";
                case "nr", "nummer" -> "1";
                default -> "00000000-0000-4000-8000-000000000001";
            };
            matcher.appendReplacement(ergebnis, Matcher.quoteReplacement(wert));
        }
        matcher.appendTail(ergebnis);
        return ergebnis.toString();
    }
}
