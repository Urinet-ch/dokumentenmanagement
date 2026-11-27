package ch.urinet.archiv.abnahme;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenPrueflisteTest {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final String TOKEN = "synthetisches-token";
    private HttpServer server;
    private URI basis;
    private final List<String> anfragen = new ArrayList<>();
    private int status = 200;
    private String strom = "event: text\ndata: {\"delta\":\"Erfundene Antwort\"}\n\n"
            + "event: quellen\ndata: [{\"wissen\":{\"titel\":\"Test\"},"
            + "\"dokumente\":[{\"link\":\"/dokument/00000000-0000-4000-8000-000000000001\","
            + "\"seiten\":\"3\"}]}]\n\n"
            + "event: fertig\ndata: {}\n\n";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/chat", this::antworten);
        server.start();
        basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void antworten(HttpExchange x) throws IOException {
        try (x) {
            if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
                x.sendResponseHeaders(401, -1);
                return;
            }
            String koerper = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            anfragen.add(x.getRequestURI().getPath() + " " + koerper);
            if (status != 200) {
                x.sendResponseHeaders(status, -1);
                return;
            }
            String antwort = x.getRequestURI().getPath().endsWith("/gespraeche")
                    ? "{\"id\":\"00000000-0000-4000-8000-000000000099\"}" : strom;
            byte[] daten = antwort.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, daten.length);
            x.getResponseBody().write(daten);
        }
    }

    private ObjectNode beispiel() {
        ObjectNode liste = YAML.createObjectNode();
        var quellen = liste.putArray("quellen");
        for (int i = 1; i <= 5; i++) {
            quellen.addObject().put("nr", i).put("dokument", "00000000-0000-4000-8000-%012d".formatted(i));
        }
        var fussnoten = liste.putArray("fussnoten");
        for (int i = 1; i <= 50; i++) {
            fussnoten.addObject().put("nr", i).put("quelle", (i - 1) % 5 + 1).put("seite", 3)
                    .put("fundstelle", "Synthetische Fussnote " + i);
        }
        var fragen = liste.putArray("fragen");
        for (int i = 1; i <= 15; i++) {
            fragen.addObject().put("nr", i).put("frage", "Synthetische Frage " + i)
                    .put("antwort", "Erfundene Antwort").put("quelle", 1).put("seite", 3);
        }
        return liste;
    }

    private Path speichern(Path tmp, ObjectNode liste) throws IOException {
        Path datei = tmp.resolve("pruefliste.yaml");
        Files.writeString(datei, YAML.writeValueAsString(liste));
        return datei;
    }

    @Test
    void synthetischerLaufVerwendetWissensmodusUndErzeugtManuellenBericht(@TempDir Path tmp) throws Exception {
        var liste = WissenPrueflisteIT.lesen(speichern(tmp, beispiel()));
        var antworten = WissenPrueflisteIT.laufen(basis, TOKEN, liste, Duration.ZERO);

        assertThat(antworten).hasSize(15);
        assertThat(anfragen).hasSize(30);
        assertThat(anfragen.getFirst()).contains("/api/v1/chat/gespraeche", "\"modus\":\"WISSEN\"");
        assertThat(anfragen.get(1)).contains("\"gespraechId\":\"00000000-0000-4000-8000-000000000099\"",
                "Synthetische Frage 1");
        String bericht = WissenPrueflisteIT.bericht(liste, antworten);
        assertThat(bericht).contains("Synthetische Fussnote 50", "Synthetische Frage 15", "Erfundene Antwort",
                "Seite 3", "Manuell: richtig mit Seite und Quelldokument").doesNotContain(TOKEN);
        Path datei = tmp.resolve("bericht.md");
        WissenPrueflisteIT.schreiben(datei, liste, antworten);
        assertThat(Files.readString(datei)).isEqualTo(bericht);
        assertThat(Files.getPosixFilePermissions(datei)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> WissenPrueflisteIT.schreiben(datei, liste, antworten))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    void schemaUndNummernWerdenOhneInhalteAbgelehnt(@TempDir Path tmp) throws Exception {
        ObjectNode zuWenige = beispiel();
        zuWenige.withArray("fussnoten").remove(49);
        assertThatThrownBy(() -> WissenPrueflisteIT.lesen(speichern(tmp, zuWenige)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Synthetische");
        ObjectNode doppelt = beispiel();
        ((ObjectNode) doppelt.withArray("fragen").get(1)).put("nr", 1);
        assertThatThrownBy(() -> WissenPrueflisteIT.lesen(speichern(tmp, doppelt)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nummern");
        ObjectNode gleicheQuelle = beispiel();
        ((ObjectNode) gleicheQuelle.withArray("quellen").get(1))
                .put("dokument", "00000000-0000-4000-8000-000000000001");
        assertThatThrownBy(() -> WissenPrueflisteIT.lesen(speichern(tmp, gleicheQuelle)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verschieden");
    }

    @Test
    void fehlerUndUnvollstaendigerStromSindKeinErfolg(@TempDir Path tmp) throws Exception {
        var liste = WissenPrueflisteIT.lesen(speichern(tmp, beispiel()));
        status = 403;
        assertThatThrownBy(() -> WissenPrueflisteIT.laufen(basis, TOKEN, liste, Duration.ZERO))
                .isInstanceOf(IOException.class).hasMessageContaining("403").hasMessageNotContaining(TOKEN);
        status = 200;
        strom = "event: text\ndata: {\"delta\":\"Geheimer Inhalt\"}\n\n";
        assertThatThrownBy(() -> WissenPrueflisteIT.laufen(basis, TOKEN, liste, Duration.ZERO))
                .isInstanceOf(IOException.class).hasMessageContaining("unvollständig")
                .hasMessageNotContaining("Geheimer Inhalt");
    }
}
