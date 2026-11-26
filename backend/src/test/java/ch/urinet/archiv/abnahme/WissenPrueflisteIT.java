package ch.urinet.archiv.abnahme;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Manueller Gate-Lauf durch Fabian auf Stufe B; kein Aufruf in CI oder regulären Tests. */
@EnabledIfSystemProperty(named = "archiv.wissen-pruefliste", matches = "true")
class WissenPrueflisteIT {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Schema SCHEMA = schema();

    @Test
    void pruefliste() throws Exception {
        Path golden = Path.of(vorgabe("ARCHIV_GOLDEN_DIR")).toRealPath();
        if (imRepository(golden)) {
            throw new IllegalStateException("Das Prüfverzeichnis muss ausserhalb des Repositorys liegen.");
        }
        Path datei = golden.resolve("wissen-pruefliste.yaml").toRealPath();
        if (!datei.getParent().equals(golden)) {
            throw new IllegalStateException("Die Prüfliste muss unmittelbar im Prüfverzeichnis liegen.");
        }
        URI basis = URI.create(vorgabe("ARCHIV_WISSEN_PRUEFLISTE_URL"));
        if (!"https".equals(basis.getScheme()) || basis.getHost() == null
                || basis.getUserInfo() != null || basis.getQuery() != null || basis.getFragment() != null
                || !(basis.getPath().isEmpty() || basis.getPath().equals("/"))) {
            throw new IllegalArgumentException("Die Archivadresse muss eine HTTPS-Basisadresse sein.");
        }
        Path tokenDatei = Path.of(vorgabe("ARCHIV_WISSEN_PRUEFLISTE_TOKEN_DATEI")).toRealPath();
        if (!Files.isRegularFile(tokenDatei)
                || !Files.getPosixFilePermissions(tokenDatei).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalArgumentException("Die Token-Datei muss eine reguläre Datei mit Rechten 600 sein.");
        }
        String token = Files.readString(tokenDatei).strip();
        if (token.isBlank()) {
            throw new IllegalArgumentException("Die Token-Datei ist leer.");
        }
        JsonNode liste = lesen(datei);
        List<Antwort> antworten = laufen(basis, token, liste, Duration.ofSeconds(5));
        Path bericht = golden.resolve("wissen-pruefliste-bericht-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".md");
        schreiben(bericht, liste, antworten);
        System.out.println("WISSEN-PRUEFLISTE: 15 Antworten zur manuellen Prüfung; Bericht unter ARCHIV_GOLDEN_DIR.");
        assertThat(antworten).hasSize(15);
    }

    static JsonNode lesen(Path datei) throws IOException {
        JsonNode liste;
        try {
            liste = YAML.readTree(Files.readString(datei));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Die Prüfliste ist kein gültiges YAML.");
        }
        if (liste == null || !SCHEMA.validate(liste).isEmpty()) {
            throw new IllegalArgumentException("Die Prüfliste verletzt das Schema.");
        }
        nummern(liste.path("quellen"), 5);
        nummern(liste.path("fussnoten"), 50);
        nummern(liste.path("fragen"), 15);
        Set<String> dokumente = new HashSet<>();
        liste.path("quellen").forEach(q -> dokumente.add(q.path("dokument").asString().toLowerCase()));
        if (dokumente.size() != 5) {
            throw new IllegalArgumentException("Die fünf Quelldokumente müssen verschieden sein.");
        }
        return liste;
    }

    private static void nummern(JsonNode eintraege, int anzahl) {
        Set<Integer> nummern = new HashSet<>();
        eintraege.forEach(e -> nummern.add(e.path("nr").asInt()));
        if (nummern.size() != anzahl) {
            throw new IllegalArgumentException("Die Nummern müssen eindeutig und lückenlos sein.");
        }
    }

    record Antwort(int nr, String text, String quellen) {
    }

    static List<Antwort> laufen(URI basis, String token, JsonNode liste, Duration pause)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String root = basis.toString().replaceAll("/+$", "");
        List<Antwort> antworten = new ArrayList<>();
        for (JsonNode frage : liste.path("fragen")) {
            if (!antworten.isEmpty()) {
                Thread.sleep(pause.toMillis());
            }
            HttpResponse<String> anlegen = senden(client, root + "/api/v1/chat/gespraeche", token,
                    "{\"modus\":\"WISSEN\"}", "application/json");
            if (anlegen.statusCode() != 200 && anlegen.statusCode() != 201) {
                throw new IOException("Gespräch konnte nicht angelegt werden (HTTP " + anlegen.statusCode() + ").");
            }
            String id;
            try {
                id = UUID.fromString(JSON.readTree(anlegen.body()).path("id").asString()).toString();
            } catch (RuntimeException e) {
                throw new IOException("Gesprächsantwort ist ungültig.");
            }
            String koerper = JSON.createObjectNode().put("frage", frage.path("frage").asString())
                    .put("gespraechId", id).toString();
            HttpResponse<String> antwort = senden(client, root + "/api/v1/chat", token, koerper, "text/event-stream");
            if (antwort.statusCode() != 200) {
                throw new IOException("Frage konnte nicht gestellt werden (HTTP " + antwort.statusCode() + ").");
            }
            antworten.add(strom(frage.path("nr").asInt(), antwort.body()));
        }
        return List.copyOf(antworten);
    }

    private static HttpResponse<String> senden(HttpClient client, String url, String token, String koerper, String accept)
            throws IOException, InterruptedException {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", accept).POST(HttpRequest.BodyPublishers.ofString(koerper, StandardCharsets.UTF_8)).build();
        try {
            return client.send(anfrage, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Die Verbindung zum Archiv ist fehlgeschlagen.");
        }
    }

    static Antwort strom(int nr, String strom) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder daten = new StringBuilder();
        String name = null;
        String quellen = null;
        boolean fertig = false;
        try {
            for (String zeile : strom.split("\\r?\\n", -1)) {
                if (zeile.isEmpty()) {
                    if (name != null) {
                        JsonNode wert = JSON.readTree(daten.toString());
                        switch (name) {
                            case "text" -> text.append(wert.path("delta").asString(""));
                            case "quellen" -> {
                                if (!wert.isArray()) {
                                    throw new IOException("Die Quellen im Antwortstrom sind ungültig.");
                                }
                                quellen = wert.toString();
                            }
                            case "fertig" -> fertig = true;
                            case "fehler" -> throw new IOException("Der Chat meldet einen Fehler.");
                            default -> { }
                        }
                    }
                    name = null;
                    daten.setLength(0);
                } else if (zeile.startsWith("event:")) {
                    name = zeile.substring(6).strip();
                } else if (zeile.startsWith("data:")) {
                    if (!daten.isEmpty()) {
                        daten.append('\n');
                    }
                    daten.append(zeile.substring(5).strip());
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Der Antwortstrom ist ungültig.");
        }
        if (!fertig || quellen == null || text.isEmpty()) {
            throw new IOException("Der Antwortstrom ist unvollständig.");
        }
        return new Antwort(nr, text.toString(), quellen);
    }

    static String bericht(JsonNode liste, List<Antwort> antworten) {
        StringBuilder b = new StringBuilder("# Phase 4: vertraulicher Prüfbericht\n\n"
                + "Nur lokal; jede Bewertung erfolgt manuell durch Fabian.\n\n## Fussnoten\n\n");
        for (JsonNode f : liste.path("fussnoten")) {
            b.append("- Nr. ").append(f.path("nr").asInt()).append(": Quelle ")
                    .append(f.path("quelle").asInt()).append(", Seite ").append(f.path("seite").asInt())
                    .append(", Fundstelle: ").append(f.path("fundstelle").asString()).append(" — belegt [ ] ja [ ] nein\n");
        }
        b.append("\n## Quellen\n\n");
        for (JsonNode q : liste.path("quellen")) {
            b.append("- Nr. ").append(q.path("nr").asInt()).append(": ").append(q.path("dokument").asString()).append('\n');
        }
        b.append("\n## Fragen\n\n");
        for (int i = 0; i < antworten.size(); i++) {
            JsonNode f = liste.path("fragen").get(i);
            Antwort a = antworten.get(i);
            b.append("### Nr. ").append(a.nr()).append("\n\nFrage: ").append(f.path("frage").asString())
                    .append("\n\nErwartet: ").append(f.path("antwort").asString()).append("\n\nQuelle ")
                    .append(f.path("quelle").asInt()).append(", Seite ").append(f.path("seite").asInt())
                    .append("\n\nAntwort: ").append(a.text()).append("\n\nQuellen: ").append(a.quellen())
                    .append("\n\nManuell: richtig mit Seite und Quelldokument [ ] ja [ ] nein\n\n");
        }
        return b.toString();
    }

    static void schreiben(Path datei, JsonNode liste, List<Antwort> antworten) throws IOException {
        Files.createFile(datei, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(datei, bericht(liste, antworten), StandardCharsets.UTF_8);
    }

    private static boolean imRepository(Path pfad) {
        for (Path p = pfad; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    private static String vorgabe(String name) {
        String wert = System.getenv(name);
        if (wert == null || wert.isBlank()) {
            throw new IllegalArgumentException("Umgebungsvariable " + name + " fehlt.");
        }
        return wert;
    }

    private static Schema schema() {
        try (InputStream in = WissenPrueflisteIT.class.getResourceAsStream("/abnahme/wissen-pruefliste.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Schema der Wissensprüfliste fehlt.");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                            b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()))
                    .getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                            com.networknt.schema.InputFormat.JSON);
        } catch (IOException e) {
            throw new IllegalStateException("Schema der Wissensprüfliste kann nicht gelesen werden.");
        }
    }
}
