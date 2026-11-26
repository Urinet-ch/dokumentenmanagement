package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ki.chat.TestChatAgent;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Einmalige lokale HTTP-Messung, gestartet ausschliesslich mit dem Wegwerf-Stack aus wissenssuche.sh. */
@QuarkusTest
@Tag("start-3")
@TestProfile(WissenssucheLeistungIT.Isoliert.class)
@EnabledIfSystemProperty(named = "archiv.leistungsbasis", matches = "true")
class LeistungsbasisIT {
    private static final int DOKUMENTE = 5_000;
    private static final int PARALLEL = 20;
    private static final Path ROH = Path.of("target", "leistungsbasis-roh.csv");

    @Inject MandantProvisionierung provisionierung;
    @Inject Instance<AgroalDataSource> datenbank;
    @Inject TestChatAgent chat;
    @Inject ObjectMapper json;
    @TestHTTPResource URI basis;

    record Probe(String ablauf, boolean erfolg, double dauerMs, String fehler) {
    }

    @Test
    void messen() throws Exception {
        Files.deleteIfExists(ROH);
        assertThat(basis.getScheme()).isEqualTo("http");
        assertThat(basis.getHost()).isIn("localhost", "127.0.0.1");
        String jdbc = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
        assertThat(jdbc).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/wissen_leistung");
        try (Connection c = datenbank.get().getConnection(); Statement s = c.createStatement();
             var zeilen = s.executeQuery("select count(*) from public.mandant")) {
            assertThat(zeilen.next()).isTrue();
            assertThat(zeilen.getInt(1)).as("Leere Wegwerf-Datenbank").isZero();
        }
        provisionierung.anlegen("urinet", null, null);
        UUID dokumentId = fuellen();
        chat.verhalten(TestChatAgent.Verhalten.STUB, "");

        String issuer = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        var schluessel = FakeKeycloak.schluesselDerRessource();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            tokens.add(FakeKeycloak.signiertesJwt(schluessel, issuer, "leistungsprobe-" + i));
        }
        HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(10)).build();
        List<Probe> proben = new ArrayList<>();
        for (String ablauf : List.of("suche", "eingangskorb", "dokument", "chat")) {
            proben.addAll(messen(client, tokens, ablauf, dokumentId));
        }
        try (var writer = Files.newBufferedWriter(ROH, StandardCharsets.UTF_8)) {
            writer.write("ablauf,erfolg,dauer_ms,fehler\n");
            for (Probe probe : proben) {
                writer.write(String.format(Locale.ROOT, "%s,%s,%.3f,%s%n",
                        probe.ablauf(), probe.erfolg(), probe.dauerMs(), probe.fehler()));
            }
        }
        for (String ablauf : List.of("suche", "eingangskorb", "dokument", "chat")) {
            assertThat(proben.stream().filter(p -> p.ablauf().equals(ablauf) && p.erfolg()).count())
                    .as("Mindestens eine erfolgreiche Probe für " + ablauf).isPositive();
        }
    }

    private UUID fuellen() throws Exception {
        UUID erste = UUID.randomUUID();
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement insert = c.prepareStatement("insert into m_urinet.dokument "
                     + "(id, art, titel, quelle, vertraulichkeit, erstellt_von, status, bestaetigt, "
                     + "bestaetigt_von, volltext) values (?, 'KORRESPONDENZ', ?, 'UPLOAD', 'NORMAL', "
                     + "?, ?, now(), 'probe', ?)")) {
            for (int i = 0; i < DOKUMENTE; i++) {
                insert.setObject(1, i == 250 ? erste : UUID.randomUUID());
                insert.setString(2, "Synthetische Leistungsprobe " + i);
                insert.setString(3, "leistungsprobe-" + i % PARALLEL);
                insert.setString(4, i < 250 ? "EINGANGSKORB" : "ARCHIVIERT");
                insert.setString(5, "Leistungsprobe Rechnung Dokument " + i);
                insert.addBatch();
                if (i % 500 == 499) insert.executeBatch();
            }
            try (Statement s = c.createStatement()) {
                s.execute("analyze m_urinet.dokument");
            }
        }
        return erste;
    }

    private List<Probe> messen(HttpClient client, List<String> tokens, String ablauf, UUID dokumentId)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch bereit = new CountDownLatch(PARALLEL);
        try (var executor = Executors.newFixedThreadPool(PARALLEL)) {
            List<Future<Probe>> laeufe = new ArrayList<>();
            for (String token : tokens) {
                laeufe.add(executor.submit(() -> {
                    bereit.countDown();
                    start.await();
                    return anfrage(client, token, ablauf, dokumentId);
                }));
            }
            boolean alleBereit = bereit.await(30, TimeUnit.SECONDS);
            start.countDown();
            assertThat(alleBereit).as("Alle 20 Clients warten auf den Start").isTrue();
            List<Probe> proben = new ArrayList<>();
            for (Future<Probe> lauf : laeufe) proben.add(lauf.get());
            return proben;
        }
    }

    private Probe anfrage(HttpClient client, String token, String ablauf, UUID dokumentId)
            throws InterruptedException {
        String pfad = switch (ablauf) {
            case "suche" -> "/api/v1/dokumente?modus=volltext&q=Leistungsprobe";
            case "eingangskorb" -> "/api/v1/eingangskorb";
            case "dokument" -> "/api/v1/dokumente/" + dokumentId;
            case "chat" -> "/api/v1/chat";
            default -> throw new IllegalArgumentException(ablauf);
        };
        var builder = HttpRequest.newBuilder(basis.resolve(pfad)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token);
        if (ablauf.equals("chat")) {
            builder.header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"frage\":\"Synthetische Frage\"}"));
        } else {
            builder.GET();
        }
        long start = System.nanoTime();
        try {
            var antwort = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = antwort.body()) {
                if (antwort.statusCode() != 200) {
                    return new Probe(ablauf, false, 0, "HTTP-" + antwort.statusCode());
                }
                if (!ablauf.equals("chat")) {
                    byte[] rumpf = stream.readAllBytes();
                    long ende = System.nanoTime();
                    var inhalt = json.readTree(rumpf);
                    boolean gueltig = switch (ablauf) {
                        case "suche", "eingangskorb" -> inhalt.path("gesamt").asLong() > 0;
                        case "dokument" -> dokumentId.toString().equals(inhalt.path("id").asText());
                        default -> false;
                    };
                    return new Probe(ablauf, gueltig, (ende - start) / 1_000_000.0,
                            gueltig ? "" : "Antwort-ungueltig");
                }
                long ersterText = 0;
                boolean fertig = false;
                boolean fehler = false;
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String zeile;
                    String ereignis = "";
                    StringBuilder daten = new StringBuilder();
                    while ((zeile = reader.readLine()) != null) {
                        if (zeile.startsWith("event:")) {
                            ereignis = zeile.substring("event:".length()).strip();
                        } else if (zeile.startsWith("data:")) {
                            if (!daten.isEmpty()) daten.append('\n');
                            daten.append(zeile.substring("data:".length()).strip());
                        } else if (zeile.isEmpty()) {
                            long empfangen = System.nanoTime();
                            if (ereignis.equals("text") && ersterText == 0 && !daten.isEmpty()
                                    && !json.readTree(daten.toString()).path("delta").asText("").isEmpty()) {
                                ersterText = empfangen;
                            }
                            if (ereignis.equals("fertig")) fertig = true;
                            if (ereignis.equals("fehler")) fehler = true;
                            ereignis = "";
                            daten.setLength(0);
                        }
                    }
                }
                if (ersterText == 0 || !fertig || fehler) {
                    return new Probe(ablauf, false, 0, "SSE-unvollstaendig");
                }
                return new Probe(ablauf, true, (ersterText - start) / 1_000_000.0, "");
            }
        } catch (IOException e) {
            return new Probe(ablauf, false, 0, e.getClass().getSimpleName());
        }
    }
}
