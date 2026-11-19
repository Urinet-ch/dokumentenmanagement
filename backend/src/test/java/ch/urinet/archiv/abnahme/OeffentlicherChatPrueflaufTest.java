package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.OeffentlicheChatPruefliste.ErwarteteQuelle;
import ch.urinet.archiv.abnahme.OeffentlicheChatPruefliste.Frage;
import ch.urinet.archiv.abnahme.OeffentlicherChatPrueflauf.Lauf;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OeffentlicherChatPrueflaufTest {

    private static final String TOKEN = "synthetisch-nie-echt";
    private static final String CLIENT = "a".repeat(64);
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-4000-8000-000000000071");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HINWEIS = "Antworten werden automatisch aus Verbandsdokumenten erstellt. "
            + "Massgebend sind die verlinkten Originaldokumente.";

    private HttpServer server;
    private URI basis;
    private final List<String> anfragen = new CopyOnWriteArrayList<>();
    private final List<String> autorisierungen = new CopyOnWriteArrayList<>();
    private volatile int fehlerStatus;
    private volatile String fehlercode;

    @BeforeEach
    void serverStarten() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/public/v1/chat", this::behandeln);
        server.start();
        basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void serverStoppen() {
        server.stop(0);
    }

    @Test
    void schemaErlaubtNurVierzigBisSechzigFragen() {
        assertThat(OeffentlicheChatPruefliste.aus(katalog(40))).hasSize(40);
        assertThat(OeffentlicheChatPruefliste.aus(katalog(60))).hasSize(60);
        assertThatThrownBy(() -> OeffentlicheChatPruefliste.aus(katalog(39)))
                .isInstanceOf(OeffentlicheChatPruefliste.UngueltigePruefliste.class);
        assertThatThrownBy(() -> OeffentlicheChatPruefliste.aus(katalog(61)))
                .isInstanceOf(OeffentlicheChatPruefliste.UngueltigePruefliste.class);
    }

    @Test
    void prueflisteLaeuftGegenAusschliesslichLokalenFakeServer() throws Exception {
        List<Frage> fragen = fragen();
        Lauf lauf = new OeffentlicherChatPrueflauf(basis, TOKEN, CLIENT, Duration.ZERO, Duration.ofSeconds(5),
                HINWEIS).laufen(fragen);

        assertThat(lauf.abgebrochen()).isFalse();
        assertThat(lauf.ergebnisse()).hasSize(40).allSatisfy(e -> {
            assertThat(e.antwortRichtig()).isTrue();
            assertThat(e.quellenRichtig()).isTrue();
            assertThat(e.hinweisRichtig()).isTrue();
        });
        assertThat(anfragen).hasSize(40).allMatch(a -> a.startsWith("{\"frage\":\"Synthetische Frage "));
        assertThat(autorisierungen).containsOnly("Bearer " + TOKEN);
        assertThat(OeffentlicherChatPrueflauf.repoTeil(lauf)).doesNotContain(TOKEN, "Synthetische Frage",
                "Testwert 2071", DOKUMENT.toString());
        assertThat(OeffentlicherChatPrueflauf.bericht(lauf)).contains("Synthetische Frage 1")
                .doesNotContain(TOKEN);
    }

    @Test
    void brichtBeiGesperrtemSchluesselUndErschoepftemBudgetSofortAb() throws Exception {
        for (var fall : List.of(new Object[]{403, "ZUGRIFF_VERWEIGERT"}, new Object[]{429, "BUDGET"})) {
            anfragen.clear();
            fehlerStatus = (int) fall[0];
            fehlercode = (String) fall[1];
            Lauf lauf = new OeffentlicherChatPrueflauf(basis, TOKEN, CLIENT, Duration.ZERO, Duration.ofSeconds(5),
                    null).laufen(fragen());
            assertThat(lauf.abgebrochen()).as(fehlercode).isTrue();
            assertThat(lauf.abbruchStatus()).isEqualTo(fehlerStatus);
            assertThat(anfragen).as(fehlercode).hasSize(1);
        }
    }

    @Test
    void zuVieleAnfragenBrichtNichtAbSondernWirdAlsFalschGezaehlt() throws Exception {
        fehlerStatus = 429;
        fehlercode = "ZU_VIELE_ANFRAGEN";
        Lauf lauf = new OeffentlicherChatPrueflauf(basis, TOKEN, CLIENT, Duration.ZERO, Duration.ofSeconds(5), null)
                .laufen(fragen());
        assertThat(lauf.abgebrochen()).isFalse();
        assertThat(anfragen).hasSize(40);
        assertThat(lauf.ergebnisse()).allSatisfy(e -> assertThat(e.antwortRichtig()).isFalse());
    }

    @Test
    void frageOhneBelegErwartetKeineQuelleUndHinweisWirdGegenDenMandantentextGeprueft() {
        ArrayNode katalog = katalog(40);
        ((ObjectNode) katalog.get(0).path("erwartet")).putArray("quellen");
        Frage ohneBeleg = OeffentlicheChatPruefliste.aus(katalog).getFirst();
        assertThat(ohneBeleg.quellen()).isEmpty();

        var antwort = new OeffentlicherChatPrueflauf.Antwort(200, "Testwert 2071 bei der Geschäftsstelle", List.of(),
                true, " " + HINWEIS + " ", null);
        var ergebnis = OeffentlicherChatPrueflauf.bewerten(ohneBeleg, antwort, HINWEIS);
        assertThat(ergebnis.quellenRichtig()).isTrue();
        assertThat(ergebnis.hinweisRichtig()).isTrue();
        assertThat(OeffentlicherChatPrueflauf.bewerten(ohneBeleg, antwort, "Anderer Mandantentext").hinweisRichtig())
                .isFalse();
        var ohneHinweis = new OeffentlicherChatPrueflauf.Antwort(200, "Testwert 2071", List.of(), true, " ", null);
        assertThat(OeffentlicherChatPrueflauf.bewerten(ohneBeleg, ohneHinweis, null).hinweisRichtig()).isFalse();
    }

    private void behandeln(HttpExchange x) throws IOException {
        try (x) {
            autorisierungen.add(x.getRequestHeaders().getFirst("Authorization"));
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            anfragen.add(body);
            if (!"POST".equals(x.getRequestMethod())
                    || !("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))
                    || !"oeffentlich".equals(x.getRequestHeaders().getFirst("X-Archiv-Zugriff"))
                    || !CLIENT.equals(x.getRequestHeaders().getFirst("X-Archiv-Client"))) {
                x.sendResponseHeaders(401, -1);
                return;
            }
            if (fehlerStatus != 0) {
                byte[] problem = JSON.writeValueAsString(JSON.createObjectNode().put("status", fehlerStatus)
                        .put("fehlercode", fehlercode)).getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "application/problem+json");
                x.sendResponseHeaders(fehlerStatus, problem.length);
                x.getResponseBody().write(problem);
                return;
            }
            byte[] strom = strom().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            x.sendResponseHeaders(200, strom.length);
            x.getResponseBody().write(strom);
        }
    }

    private static String strom() throws IOException {
        String text = JSON.writeValueAsString("Die Nordbahn wurde nach Testwert 2071 erneuert.");
        String hinweis = JSON.writeValueAsString(HINWEIS);
        ObjectNode quelle = JSON.createObjectNode().put("dokumentId", DOKUMENT.toString())
                .put("titel", "Synthetisches Testdokument").put("ausgabe", "1/2071").put("jahr", 2071);
        quelle.putObject("reihe").put("code", "TEST").put("name", "Testreihe");
        quelle.putObject("fundstelle").put("seite", "7-8");
        String quellen = JSON.createArrayNode().add(quelle).toString();
        return "event: text\ndata: {\"delta\":" + text + "}\n\n"
                + "event: quellen\ndata: " + quellen + "\n\n"
                + "event: fertig\ndata: {\"gespraechId\":\"00000000-0000-4000-8000-000000000099\","
                + "\"hinweis\":" + hinweis + "}\n\n";
    }

    private static List<Frage> fragen() {
        ErwarteteQuelle quelle = new ErwarteteQuelle(DOKUMENT, "Synthetisches Testdokument", "TEST", "1/2071",
                2071, "7-8");
        List<Frage> fragen = new ArrayList<>();
        for (int nr = 1; nr <= 40; nr++) {
            fragen.add(new Frage(nr, "Synthetische Frage " + nr, List.of("Testwert 2071"), List.of(quelle)));
        }
        return List.copyOf(fragen);
    }

    private static ArrayNode katalog(int anzahl) {
        ArrayNode katalog = JSON.createArrayNode();
        for (int nr = 1; nr <= anzahl; nr++) {
            ObjectNode frage = katalog.addObject().put("nr", nr).put("frage", "Synthetische Frage " + nr);
            ObjectNode erwartet = frage.putObject("erwartet");
            erwartet.putArray("kernwerte").add("Testwert 2071");
            erwartet.putArray("quellen").addObject()
                    .put("dokument", DOKUMENT.toString())
                    .put("titel", "Synthetisches Testdokument")
                    .put("reihe", "TEST")
                    .put("ausgabe", "1/2071")
                    .put("jahr", 2071)
                    .put("seite", "7-8");
        }
        return katalog;
    }
}
