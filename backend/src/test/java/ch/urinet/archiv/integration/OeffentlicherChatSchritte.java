package ch.urinet.archiv.integration;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragenAufraeumen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zum öffentlichen Chatbot (Spec 03, Aufgabe 11.3, Req 6.1–6.5) gegen {@code POST /public/v1/chat}. Das
 * Sprachmodell ist der WireMock der Chat-Szenarien (Schritte aus {@code AgentSchritte}: Antworten, Journal, Werkzeuge);
 * die Werkzeuge laufen echt gegen die Datenbank. Schlüssel, Reihen und Dokumente (synthetisch, Jahre 2071) entstehen zur
 * Laufzeit und werden danach gelöscht. Webseite und Client-Namen sind je Szenario eindeutig, weil die Grenzen im Speicher
 * der Instanz über Szenarien hinweg zählen.
 */
public class OeffentlicherChatSchritte {

    private static final String PFAD = "/public/v1/chat";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");

    @Inject
    WebseitenSchluesselVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    OeffentlicheFragenAufraeumen aufraeumen;

    @Inject
    MeterRegistry registry;

    private final String lauf = UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, String> schluessel = new HashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private final Map<String, Dokument> dokumente = new LinkedHashMap<>();
    private final Map<String, Set<String>> reihen = new HashMap<>();
    private final Set<String> budgetMandanten = new LinkedHashSet<>();
    private final Set<String> frageMandanten = new LinkedHashSet<>();
    private String ip;
    private Response antwort;
    private List<Response> paralleleAntworten = List.of();
    private List<Ereignis> ereignisse = List.of();
    private String gespraech;
    private String letzterSchluessel;
    private String letzterClient;
    private String letzteStufe;
    private String budgetFehlerTrigger;
    private String budgetFehlerFunktion;
    private double budgetFehlerZaehlerVorher;

    private record Dokument(String mandant, UUID id, String titel) {
    }

    private record Ereignis(String name, JsonNode daten) {
    }

    @After
    public void aufraeumen() throws SQLException {
        if (budgetFehlerTrigger != null) {
            ausfuehren("drop trigger if exists " + budgetFehlerTrigger + " on public.mandant_budget");
            ausfuehren("drop function if exists public." + budgetFehlerFunktion + "()");
        }
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(d -> jeMandant.computeIfAbsent(d.mandant(), m -> new ArrayList<>()).add(d.id()));
        for (Map.Entry<String, List<UUID>> e : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Set<String>> e : reihen.entrySet()) {
            for (String code : e.getValue()) {
                ausfuehren("delete from " + schema(e.getKey()) + ".dokumentart where code = ?", code);
            }
        }
        for (String mandant : budgetMandanten) {
            ausfuehren("delete from public.mandant_budget where mandant = ?", mandant);
        }
        for (String mandant : frageMandanten) {
            ausfuehren("delete from " + schema(mandant) + ".oeffentliche_frage");
            ausfuehren("delete from " + schema(mandant) + ".ki_verbrauch where zweck = 'CHAT'");
        }
        dokumente.clear();
        reihen.clear();
        tokenIds.clear();
        schluessel.clear();
        budgetMandanten.clear();
        frageMandanten.clear();
        ip = null;
        gespraech = null;
        paralleleAntworten = List.of();
    }

    // ---- Vorbereitung

    @Angenommen("im Mandanten «{word}» gibt es die Reihen:")
    public void reihenAnlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            int neu = ausfuehren("insert into " + schema(mandant) + ".dokumentart (code, name, vertraulichkeit, "
                    + "gesetzliche_pflicht, aktiv) values (?, ?, 'NORMAL', false, true) on conflict do nothing",
                    zeile.get("code"), zeile.get("name"));
            if (neu > 0) {
                reihen.computeIfAbsent(mandant, m -> new LinkedHashSet<>()).add(zeile.get("code"));
            }
        }
    }

    @Angenommen("der Chatbot-Schlüssel «{word}» der Webseite «{word}» im Mandanten «{word}» hat die höchste Stufe «{word}»")
    public void leseSchluessel(String name, String webseite, String mandant, String stufe) {
        String text = verwaltung.erstellen(mandant, webseite + "-" + lauf, "lesen", stufe, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        schluessel.put(name, text);
    }

    @Angenommen("der Erfassungs-Schlüssel «{word}» der Webseite «{word}» im Mandanten «{word}» ist für den Chatbot angelegt")
    public void erfassungsSchluessel(String name, String webseite, String mandant) {
        String text = verwaltung.erstellen(mandant, webseite + "-" + lauf, "erfassung", null, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        schluessel.put(name, text);
    }

    /**
     * Spalten: name, titel, reihe, ausgabe, jahr, zugriff, veroeffentlicht, vertraulichkeit, status, text; «-» lässt
     * das Feld weg (Zusatz, Text); {@code veroeffentlicht} steht roh im JSON («true», «"true"»).
     */
    @Angenommen("im Mandanten «{word}» liegen diese Chatbot-Dokumente:")
    public void dokumenteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> z : tabelle.asMaps()) {
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, "-".equals(z.get("reihe"))
                    ? null : z.get("reihe"), z.get("vertraulichkeit"), true, "probe", null, z.get("status")));
            dokumente.put(z.get("name"), new Dokument(mandant, id, z.get("titel")));
            ObjectNode zusatz = JSON.createObjectNode();
            if (!"-".equals(z.get("ausgabe"))) {
                zusatz.put("ausgabe", z.get("ausgabe"));
            }
            if (!"-".equals(z.get("jahr"))) {
                zusatz.put("jahr", Integer.parseInt(z.get("jahr")));
            }
            if (!"-".equals(z.get("zugriff"))) {
                zusatz.put("zugriff", z.get("zugriff"));
            }
            if (!"-".equals(z.get("veroeffentlicht"))) {
                zusatz.set("veroeffentlicht", roh(z.get("veroeffentlicht")));
            }
            ausfuehren("update " + schema(mandant) + ".dokument set titel = ?, zusatz = ?::jsonb, volltext = ?, "
                    + "datum = '2071-03-01' where id = ?", z.get("titel"), zusatz.toString(),
                    "-".equals(z.get("text")) ? null : z.get("text"), id);
        }
    }

    /** Das Embedding-Modell (WireMock) bettet jede Frage als Einheitsvektor {@code e0} ein; die Abschnitte liegen nahe. */
    @Angenommen("das Embedding-Modell bettet jede Frage des öffentlichen Chatbots gleich ein")
    public void embeddingBereit() {
        float[] frage = new float[KiWireMock.DIMENSION];
        frage[0] = 1f;
        KiWireMock.client().register(post(urlPathEqualTo("/v1/embeddings")).atPriority(5).willReturn(okJson(
                "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":"
                        + Vektortext.text(frage) + "}],\"model\":\"bge_multilingual_gemma2\",\"usage\":{"
                        + "\"prompt_tokens\":" + KiWireMock.TOKENS_JE_TEXT + ",\"total_tokens\":"
                        + KiWireMock.TOKENS_JE_TEXT + "}}")));
    }

    /** Setzt {@code dokument.seiten} und legt einen Abschnitt nahe der Frage mit dem Seitenbereich «von-bis» an. */
    @Angenommen("das Chatbot-Dokument «{word}» hat {int} Seiten und einen Abschnitt nahe der Frage auf den Seiten «{}»")
    public void abschnitt(String name, int seiten, String bereich) throws SQLException {
        Dokument d = dokumente.get(name);
        ausfuehren("update " + schema(d.mandant()) + ".dokument set seiten = ? where id = ?", seiten, d.id());
        String[] teile = bereich.split("-");
        float[] vektor = new float[KiWireMock.DIMENSION];
        vektor[0] = 1f;
        vektor[1] = 0.01f;
        ausfuehren("insert into " + schema(d.mandant()) + ".chunk (dokument_id, nr, text, seite_von, seite_bis, "
                + "embedding) values (?, 1, ?, ?, ?, ?::public.halfvec)", d.id(), "Abschnitt " + name,
                Integer.valueOf(teile[0]), Integer.valueOf(teile[1]), Vektortext.text(vektor));
    }

    @Angenommen("die Webseite übermittelt bei jeder Frage die IP «{}»")
    public void ipUebermitteln(String wert) {
        ip = wert;
    }

    @Angenommen("das Monatsbudget des Chatbots für «{word}» beträgt {long} Tokens und ist zu {long} Tokens verbraucht")
    public void budget(String mandant, long maximum, long verbraucht) throws SQLException {
        budgetMandanten.add(mandant);
        ausfuehren("delete from public.mandant_budget where mandant = ?", mandant);
        ausfuehren("insert into public.mandant_budget (mandant, monat, tokens_max, tokens_verbraucht) values (?, "
                + "date_trunc('month', now() at time zone 'Europe/Zurich')::date, ?, ?)", mandant, maximum, verbraucht);
    }

    @Angenommen("die Frage-Reservierung des Chatbots für «{word}» läuft seit {int} Minuten")
    public void reservierungSeit(String mandant, int minuten) throws SQLException {
        budgetMandanten.add(mandant);
        ausfuehren("insert into public.mandant_budget (mandant, monat, tokens_max, tokens_verbraucht) values (?, "
                + "date_trunc('month', now() at time zone 'Europe/Zurich')::date, 1000, 0) on conflict do nothing",
                mandant);
        ausfuehren("update public.mandant_budget set frage_laufend = true, laufend_seit = now() - (? * interval '1 minute') "
                + "where mandant = ? and monat = date_trunc('month', now() at time zone 'Europe/Zurich')::date",
                minuten, mandant);
    }

    @Angenommen("das Monatsbudget des Chatbots für «{word}» war im Vormonat verbraucht und die Frage-Reservierung hing")
    public void vormonatVerbraucht(String mandant) throws SQLException {
        budgetMandanten.add(mandant);
        ausfuehren("delete from public.mandant_budget where mandant = ?", mandant);
        ausfuehren("insert into public.mandant_budget (mandant, monat, tokens_max, tokens_verbraucht, frage_laufend, "
                + "laufend_seit) values (?, (date_trunc('month', now() at time zone 'Europe/Zurich') - interval '1 month')"
                + "::date, 1000, 1000, true, now() - interval '1 month')", mandant);
    }

    @Angenommen("das Schreiben des Monatsbudgets wird für diese Frage verhindert")
    public void budgetSchreibfehler() throws SQLException {
        budgetFehlerZaehlerVorher = registry.counter("archiv.oeffentlicher_chat.nutzung_schreibfehler").count();
        String suffix = lauf.replace("-", "");
        budgetFehlerFunktion = "probe_budget_schreibfehler_" + suffix;
        budgetFehlerTrigger = "probe_budget_schreibfehler_" + suffix;
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("create function public." + budgetFehlerFunktion
                    + "() returns trigger language plpgsql as $$ begin raise exception 'synthetic write failure'; end $$");
            befehl.execute("create trigger " + budgetFehlerTrigger
                    + " before update of tokens_verbraucht on public.mandant_budget for each row execute function public."
                    + budgetFehlerFunktion + "()");
        }
    }

    @Angenommen("in «{word}» liegt eine öffentliche Frage «{}» von vor {int} Tagen")
    public void alteFrage(String mandant, String frage, int tage) throws SQLException {
        frageMandanten.add(mandant);
        ausfuehren("insert into " + schema(mandant) + ".oeffentliche_frage (frage, quellen, tokens, zeit) values "
                + "(?, '[]'::jsonb, 10, ?)", frage, java.sql.Timestamp.from(Instant.now().minus(tage, ChronoUnit.DAYS)
                .minus(1, ChronoUnit.HOURS)));
    }

    // ---- Fragen

    @Wenn("die Webseite mit dem Schlüssel «{word}» für den Client «{word}» auf der Stufe «{word}» fragt: «{}»")
    public void fragen(String name, String client, String stufe, String frage) throws Exception {
        letzterSchluessel = name;
        letzterClient = client;
        letzteStufe = stufe;
        gespraech = null;
        senden(frage, null);
    }

    @Wenn("die Webseite im selben Gespräch fragt: «{}»")
    public void fragenImSelbenGespraech(String frage) throws Exception {
        assertThat(gespraech).as("Gespräch der ersten Frage").isNotNull();
        senden(frage, gespraech);
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» eines anderen Mandanten im selben Gespräch fragt: «{}»")
    public void fremderMandantImGespraech(String name, String frage) throws Exception {
        assertThat(gespraech).as("Gespräch des ersten Mandanten").isNotNull();
        String fremdeId = gespraech;
        gespraech = null;
        letzterSchluessel = name;
        letzteStufe = "oeffentlich";
        senden(frage, fremdeId);
        assertThat(gespraech).as("neue Gespräch-ID nach Mandantenwechsel").isNotNull().isNotEqualTo(fremdeId);
    }

    @Wenn("die Webseite im selben Gespräch auf der Stufe «{word}» fragt: «{}»")
    public void fragenAufAndererStufe(String stufe, String frage) throws Exception {
        assertThat(gespraech).as("Gespräch der ersten Frage").isNotNull();
        letzteStufe = stufe;
        senden(frage, gespraech);
    }

    @Wenn("ein anderer Client «{word}» im Gespräch des ersten Clients fragt: «{}»")
    public void fremdesGespraech(String client, String frage) throws Exception {
        assertThat(gespraech).as("Gespräch der ersten Frage").isNotNull();
        String id = gespraech;
        letzterClient = client;
        senden(frage, id);
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» für den Client «{word}» {int} Fragen «{}» stellt")
    public void vieleFragen(String name, String client, int anzahl, String frage) throws Exception {
        letzterSchluessel = name;
        letzterClient = client;
        letzteStufe = "oeffentlich";
        for (int i = 0; i < anzahl; i++) {
            gespraech = null;
            senden(frage, null);
            assertThat(antwort.statusCode()).as("Frage " + (i + 1) + ": " + antwort.asString()).isEqualTo(200);
        }
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» für den Client «{word}» {int} Fragen «{}» stellt, die alle mit «LAEUFT» abgelehnt werden")
    public void vieleAbgelehnteFragen(String name, String client, int anzahl, String frage) throws Exception {
        letzterSchluessel = name;
        letzterClient = client;
        letzteStufe = "oeffentlich";
        for (int i = 0; i < anzahl; i++) {
            gespraech = null;
            senden(frage, null);
            assertThat(antwort.statusCode()).as("Frage " + (i + 1)).isEqualTo(429);
            assertThat(antwort.<String>path("grund")).isEqualTo("LAEUFT");
        }
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» zwei gleichzeitige Fragen «{}» und «{}» stellt")
    public void gleichzeitigeFragen(String name, String frageEins, String frageZwei) throws Exception {
        letzterSchluessel = name;
        letzterClient = "parallel";
        letzteStufe = "oeffentlich";
        CountDownLatch bereit = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Response> eins = paralleleFrage(name, "parallel-eins", frageEins, bereit, start);
        CompletableFuture<Response> zwei = paralleleFrage(name, "parallel-zwei", frageZwei, bereit, start);
        assertThat(bereit.await(10, TimeUnit.SECONDS)).as("beide Fragen warten am Start").isTrue();
        start.countDown();
        paralleleAntworten = List.of(eins.get(60, TimeUnit.SECONDS), zwei.get(60, TimeUnit.SECONDS));
    }

    private CompletableFuture<Response> paralleleFrage(String name, String client, String frage,
                                                       CountDownLatch bereit, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            bereit.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start der parallelen Chatfragen abgelaufen");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Start der parallelen Chatfragen unterbrochen", e);
            }
            return anfrage(name, "oeffentlich", client).body("{\"frage\":" + quote(frage) + "}")
                    .when().post(PFAD);
        });
    }

    @Wenn("die Webseite ohne Schlüssel fragt: «{}»")
    public void ohneSchluessel(String frage) {
        antwort = given().header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .body("{\"frage\":" + quote(frage) + "}").when().post(PFAD);
        ereignisse = List.of();
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» den Rohkörper «{}» sendet")
    public void rohkoerper(String name, String koerper) {
        antwort = anfrage(name, "oeffentlich", null).body(koerper).when().post(PFAD);
        ereignisse = lesen();
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» mit dem Inhaltstyp «{}» den Rohkörper «{}» sendet")
    public void rohkoerperMitInhaltstyp(String name, String inhaltstyp, String koerper) {
        antwort = anfrage(name, "oeffentlich", null).contentType(inhaltstyp).body(koerper).when().post(PFAD);
        ereignisse = lesen();
    }

    @Wenn("die Webseite mit dem Schlüssel «{word}» eine Frage mit {int} Zeichen stellt")
    public void langeFrage(String name, int zeichen) throws Exception {
        letzterSchluessel = name;
        letzterClient = "lang";
        letzteStufe = "oeffentlich";
        senden("a".repeat(zeichen), null);
    }

    @Wenn("der Löschjob für öffentliche Fragen in «{word}» läuft")
    public void loeschjob(String mandant) {
        ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread.ausfuehren("oeffentliche-fragen-test",
                () -> aufraeumen.aufraeumen(new MandantCode(mandant), Instant.now()));
    }

    private void senden(String frage, String gespraechId) throws Exception {
        frageMandanten.add("urinet");
        frageMandanten.add("vhf-gsk");
        ObjectNode koerper = JSON.createObjectNode().put("frage", frage);
        if (gespraechId != null) {
            koerper.put("gespraechId", gespraechId);
        }
        antwort = anfrage(letzterSchluessel, letzteStufe, letzterClient).body(JSON.writeValueAsString(koerper)).when()
                .post(PFAD);
        ereignisse = lesen();
        ereignisse.stream().filter(e -> e.daten().has("gespraechId")).findFirst()
                .ifPresent(e -> gespraech = e.daten().path("gespraechId").asText());
    }

    private RequestSpecification anfrage(String name, String stufe, String client) {
        RequestSpecification spec = given().header("Authorization", "Bearer " + schluessel.get(name))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream");
        if (stufe != null && !"-".equals(stufe)) {
            spec = spec.header("X-Archiv-Zugriff", stufe);
        }
        if (client != null && !"-".equals(client)) {
            spec = spec.header("X-Archiv-Client", hash(client));
        }
        if (ip != null) {
            spec = spec.header("X-Real-Ip", ip);
        }
        return spec;
    }

    /** SHA-256 (hex) aus Name und Laufkennung: je Szenario eindeutig, immer 64 Hexziffern. */
    private String hash(String client) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((client + ":" + lauf).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Ereignis> lesen() {
        if (antwort.statusCode() != 200) {
            return List.of();
        }
        List<Ereignis> gelesen = new ArrayList<>();
        for (String block : antwort.asString().split("\n\n")) {
            String name = null;
            StringBuilder daten = new StringBuilder();
            for (String zeile : block.split("\n")) {
                if (zeile.startsWith("event:")) {
                    name = zeile.substring("event:".length()).strip();
                } else if (zeile.startsWith("data:")) {
                    daten.append(zeile.substring("data:".length()).strip());
                }
            }
            if (name != null) {
                try {
                    gelesen.add(new Ereignis(name, JSON.readTree(daten.toString())));
                } catch (Exception e) {
                    throw new IllegalStateException("Ereignis nicht lesbar: " + block, e);
                }
            }
        }
        return gelesen;
    }

    // ---- Antwort

    @Dann("liefert der Chatbot Status {int} und Fehlercode «{word}»")
    public void problem(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status, "ZU_VIELE_ANFRAGEN".equals(code) ? new String[]{"grund"}
                : new String[0])).isEqualTo(code);
    }

    @Dann("wird genau eine parallele Frage mit Status 200 beantwortet und die andere mit «{word}» abgelehnt")
    public void paralleleFragenAntworten(String grund) {
        assertThat(paralleleAntworten).extracting(Response::statusCode).containsExactlyInAnyOrder(200, 429);
        Response abgelehnt = paralleleAntworten.stream().filter(r -> r.statusCode() == 429).findFirst().orElseThrow();
        assertThat(ProblemDetails.pruefen(abgelehnt, 429, new String[]{"grund"})).isEqualTo("ZU_VIELE_ANFRAGEN");
        assertThat(abgelehnt.<String>path("grund")).isEqualTo(grund);
    }

    @Dann("trägt die Chatbot-Antwort den Header «{word}»")
    public void header(String name) {
        assertThat(antwort.headers().hasHeaderWithName(name)).as(antwort.headers().toString()).isTrue();
    }

    @Dann("trägt die Chatbot-Antwort den Header «{word}» mit dem Wert «{word}»")
    public void headerMitWert(String name, String wert) {
        assertThat(antwort.getHeader(name)).contains(wert);
    }

    @Dann("trägt die Chatbot-Antwort keinen Header «{word}»")
    public void keinHeader(String name) {
        assertThat(antwort.headers().hasHeaderWithName(name)).as(antwort.headers().toString()).isFalse();
    }

    @Dann("nennt das Problem den Grund «{word}»")
    public void grund(String grund) {
        assertThat(antwort.<String>path("grund")).isEqualTo(grund);
    }

    @Dann("steht in der Chatbot-Antwort weder «{}» noch «{}»")
    public void nennt(String eins, String zwei) {
        assertThat(antwort.asString()).doesNotContain(eins).doesNotContain(zwei);
    }

    @Dann("zeigt der Stream des Chatbots genau die Ereignisse «{}»")
    public void streamEreignisse(String namen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(ereignisse).extracting(Ereignis::name).containsExactlyElementsOf(
                Arrays.stream(namen.split(",")).map(String::strip).toList());
    }

    @Dann("endet der Stream des Chatbots mit «quellen» und «fertig»")
    public void streamEnde() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<String> namen = ereignisse.stream().map(Ereignis::name).toList();
        assertThat(namen).endsWith("quellen", "fertig");
        assertThat(namen.stream().filter("quellen"::equals).count()).isEqualTo(1);
    }

    @Dann("meldet der Stream des Chatbots «fehler» «{}» und weder «quellen» noch «fertig»")
    public void streamFehler(String meldung) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(ereignisse).extracting(Ereignis::name).doesNotContain("quellen", "fertig").contains("fehler");
        assertThat(ereignisse.stream().filter(e -> "fehler".equals(e.name())).findFirst().orElseThrow()
                .daten().path("meldung").asText()).isEqualTo(meldung);
    }

    @Dann("lautet der Text des Chatbots «{}»")
    public void text(String erwartet) {
        assertThat(gestreamterText()).isEqualTo(erwartet);
    }

    @Dann("enthält der Text des Chatbots «{}»")
    public void textEnthaelt(String teil) {
        assertThat(gestreamterText()).contains(teil);
    }

    @Dann("enthält der Text des Chatbots nicht «{}»")
    public void textEnthaeltNicht(String teil) {
        assertThat(gestreamterText()).doesNotContain(teil);
    }

    private String gestreamterText() {
        StringBuilder sb = new StringBuilder();
        ereignisse.stream().filter(e -> "text".equals(e.name())).forEach(e -> sb.append(e.daten().path("delta").asText()));
        return sb.toString();
    }

    @Dann("nennen die Quellen des Chatbots genau die Dokumente «{}»")
    public void quellen(String namen) {
        List<String> erwartet = Arrays.stream(namen.split(",")).map(String::strip).filter(n -> !n.isEmpty())
                .map(n -> dokumente.get(n).titel()).toList();
        assertThat(quellenKnoten()).extracting(q -> q.path("titel").asText()).containsExactlyInAnyOrderElementsOf(erwartet);
    }

    @Dann("sind die Quellen des Chatbots leer")
    public void quellenLeer() {
        assertThat(quellenKnoten()).isEmpty();
    }

    @Dann("hat jede Quelle des Chatbots genau die Felder «{}»")
    public void quellenFelder(String felder) {
        List<String> erwartet = Arrays.stream(felder.split(",")).map(String::strip).sorted().toList();
        assertThat(quellenKnoten()).isNotEmpty().allSatisfy(q -> {
            List<String> da = new ArrayList<>();
            q.fieldNames().forEachRemaining(da::add);
            assertThat(da.stream().sorted().toList()).isEqualTo(erwartet);
        });
    }

    @Dann("nennt die Quelle «{word}» die echte ID, die Reihe «{word}» mit dem Namen «{}» und die Ausgabe «{}»")
    public void quelleDetails(String name, String reiheCode, String reiheName, String ausgabe) {
        Dokument d = dokumente.get(name);
        JsonNode quelle = quellenKnoten().stream().filter(q -> q.path("titel").asText().equals(d.titel())).findFirst()
                .orElseThrow();
        assertThat(quelle.path("dokumentId").asText()).isEqualTo(d.id().toString());
        assertThat(quelle.path("reihe").path("code").asText()).isEqualTo(reiheCode);
        assertThat(quelle.path("reihe").path("name").asText()).isEqualTo(reiheName);
        assertThat(quelle.path("ausgabe").asText()).isEqualTo(ausgabe);
    }

    private List<JsonNode> quellenKnoten() {
        JsonNode daten = ereignisse.stream().filter(e -> "quellen".equals(e.name())).findFirst().orElseThrow().daten();
        List<JsonNode> liste = new ArrayList<>();
        daten.forEach(liste::add);
        return liste;
    }

    @Dann("sind die Werkzeugbeschreibungen der ersten Anfrage frei von «{}»")
    public void beschreibungenFrei(String begriffe) throws Exception {
        JsonNode anfrage = JSON.readTree(KiWireMock.chatAnfragen().getFirst());
        String tools = anfrage.path("tools").toString().toLowerCase(java.util.Locale.ROOT);
        for (String b : begriffe.split("\\|")) {
            assertThat(tools).doesNotContain(b.strip().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Dann("enthält die Anfrage {int} an das Sprachmodell {int} Nachrichten")
    public void nachrichten(int nummer, int anzahl) throws Exception {
        JsonNode anfrage = JSON.readTree(KiWireMock.chatAnfragen().get(nummer - 1));
        assertThat(anfrage.path("messages")).hasSize(anzahl);
    }

    @Dann("enthält eine Nachricht der Anfrage {int} den Text «{}»")
    public void nachrichtTeil(int nummer, String text) throws Exception {
        JsonNode anfrage = JSON.readTree(KiWireMock.chatAnfragen().get(nummer - 1));
        List<String> inhalte = new ArrayList<>();
        anfrage.path("messages").forEach(m -> inhalte.add(m.path("content").asText()));
        assertThat(inhalte).anyMatch(i -> i.contains(text));
    }

    @Dann("hat das simulierte Sprachmodell keine Anfrage erhalten")
    public void keineAnfrage() {
        assertThat(KiWireMock.chatAnfragen()).isEmpty();
    }

    // ---- Speicherung und Budget

    @Dann("hat «{word}» genau {int} gespeicherte öffentliche Frage")
    public void anzahlFragen(String mandant, int anzahl) throws SQLException {
        frageMandanten.add(mandant);
        assertThat(zahl("select count(*) from " + schema(mandant) + ".oeffentliche_frage")).isEqualTo(anzahl);
    }

    @Dann("steht in der gespeicherten Frage von «{word}» «{}» und nicht «{}»")
    public void gespeicherteFrage(String mandant, String drin, String nichtDrin) throws SQLException {
        String frage = texte("select frage from " + schema(mandant) + ".oeffentliche_frage order by id desc limit 1")
                .getFirst();
        assertThat(frage).contains(drin).doesNotContain(nichtDrin);
    }

    @Dann("nennt die gespeicherte Frage von «{word}» {int} Tokens und die Quelle «{word}»")
    public void gespeicherteQuellen(String mandant, int tokens, String name) throws SQLException {
        List<String> zeile = texte("select tokens::text || '|' || quellen::text from " + schema(mandant)
                + ".oeffentliche_frage order by id desc limit 1");
        String[] teile = zeile.getFirst().split("\\|", 2);
        assertThat(Integer.parseInt(teile[0])).isEqualTo(tokens);
        assertThat(teile[1]).contains(dokumente.get(name).id().toString()).contains(dokumente.get(name).titel());
    }

    @Dann("enthält die Tabelle der öffentlichen Fragen von «{word}» weder den Client «{word}» noch die IP «{}» und nur die Spalten «{}»")
    public void keinPersonenbezug(String mandant, String client, String ipWert, String spalten) throws SQLException {
        assertThat(texte("select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns "
                + "where table_schema = ? and table_name = 'oeffentliche_frage'", schema(mandant)).getFirst())
                .isEqualTo(spalten);
        String alles = texte("select string_agg(t::text, ' ') from " + schema(mandant) + ".oeffentliche_frage t")
                .getFirst();
        assertThat(alles).doesNotContain(hash(client)).doesNotContain(ipWert).doesNotContain(lauf);
    }

    @Dann("hat der öffentliche Chatbot in «{word}» weder Gespräche noch Nachrichten des internen Chats angelegt")
    public void keinInternerChat(String mandant) throws SQLException {
        assertThat(zahl("select count(*) from " + schema(mandant) + ".chat_gespraech")).isZero();
        assertThat(zahl("select count(*) from " + schema(mandant) + ".chat_nachricht")).isZero();
    }

    @Dann("beträgt der Verbrauch im Monatsbudget von «{word}» {long} Tokens")
    public void verbrauch(String mandant, long tokens) throws SQLException {
        budgetMandanten.add(mandant);
        assertThat(zahl("select coalesce(sum(tokens_verbraucht), 0) from public.mandant_budget where mandant = ?",
                mandant)).isEqualTo(tokens);
    }

    @Dann("ist die Frage-Reservierung des Chatbots für «{word}» frei")
    public void reservierungFrei(String mandant) throws Exception {
        // Die Freigabe läuft im Thread des Durchgangs kurz nach dem Ende des Streams.
        long ende = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (zahl("select count(*) from public.mandant_budget where mandant = ? and frage_laufend", mandant) > 0
                && System.nanoTime() < ende) {
            Thread.sleep(50);
        }
        assertThat(zahl("select count(*) from public.mandant_budget where mandant = ? and frage_laufend", mandant))
                .as("hängende Reservierungen").isZero();
    }

    @Dann("ist die Frage-Reservierung des Chatbots für «{word}» noch belegt")
    public void reservierungBelegt(String mandant) throws SQLException {
        assertThat(zahl("select count(*) from public.mandant_budget where mandant = ? and frage_laufend", mandant))
                .as("Reservierungen").isEqualTo(1);
    }

    @Dann("beträgt der Verbrauch des Chatbots für «{word}» im laufenden Monat {long} Tokens")
    public void verbrauchLaufenderMonat(String mandant, long erwartet) throws SQLException {
        assertThat(zahl("select coalesce(sum(tokens_verbraucht), 0) from public.mandant_budget where mandant = ? "
                + "and monat = date_trunc('month', now() at time zone 'Europe/Zurich')::date", mandant))
                .isEqualTo(erwartet);
    }

    @Dann("wird ein Monatsbudget-Schreibfehler in der Kennzahl erfasst")
    public void budgetFehlerKennzahl() {
        assertThat(registry.counter("archiv.oeffentlicher_chat.nutzung_schreibfehler").count())
                .isEqualTo(budgetFehlerZaehlerVorher + 1);
    }

    @Dann("hat das Budget von «{word}» einen Eintrag im Verbrauch des Sprachmodells mit Zweck CHAT")
    public void kiVerbrauch(String mandant) throws SQLException {
        frageMandanten.add(mandant);
        assertThat(zahl("select count(*) from " + schema(mandant) + ".ki_verbrauch where zweck = 'CHAT'")).isPositive();
    }

    // ---- Hilfen

    private static JsonNode roh(String wert) {
        try {
            return JSON.readTree(wert);
        } catch (Exception e) {
            throw new IllegalArgumentException(wert, e);
        }
    }

    private static String quote(String text) {
        return JSON.valueToTree(text).toString();
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private int ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                p.setObject(i + 1, werte[i]);
            }
            return p.executeUpdate();
        }
    }

    private long zahl(String sql, Object... werte) throws SQLException {
        return Long.parseLong(texte(sql, werte).getFirst());
    }

    private List<String> texte(String sql, Object... werte) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                p.setObject(i + 1, werte[i]);
            }
            List<String> liste = new ArrayList<>();
            try (ResultSet z = p.executeQuery()) {
                while (z.next()) {
                    liste.add(z.getString(1));
                }
            }
            return liste;
        }
    }
}
