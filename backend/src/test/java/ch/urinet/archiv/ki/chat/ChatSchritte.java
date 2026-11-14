package ch.urinet.archiv.ki.chat;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.ki.adapter.in.job.ChatAufraeumer;
import ch.urinet.archiv.ki.application.chat.ChatFragen;
import ch.urinet.archiv.ki.application.chat.ChatGrenzen;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 02, Aufgabe 7.3 (Req 3.8, 4.1, 5.4, 5.5): {@code POST /api/v1/chat} als Server-Sent Events gegen
 * den {@link TestChatAgent}, gelesen mit dem HTTP-Client des JDK, Ereignis für Ereignis. Zeiten für Rate-Limit und
 * Aufräumer setzen die Schritte direkt in {@code chat_nachricht.zeit}.
 */
public class ChatSchritte {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");
    private static final Duration GEDULD = Duration.ofSeconds(20);

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    TestChatAgent agent;

    @Inject
    ChatGrenzen grenzen;

    @Inject
    ChatAufraeumer aufraeumer;

    /** SSE-Ereignis: Name und JSON aus {@code data:}. */
    record Ereignis(String name, JsonNode daten) {
    }

    /** Antwort auf eine Frage: Status, Kopfzeilen, Rumpf (bei Fehlern) und Ereignisse. */
    record Antwort(int status, HttpResponse<?> roh, String rumpf, List<Ereignis> ereignisse) {
    }

    // HTTP/1.1: Schliessen des Rumpfs schliesst die Verbindung (Abmeldung des Abonnenten)
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, UUID> dokumente = new HashMap<>();
    private final Map<String, UUID> gespraeche = new LinkedHashMap<>();
    private final Map<String, String[]> gespraechOrt = new HashMap<>();
    private final Map<String, UUID> letztesGespraech = new HashMap<>();
    private Antwort antwort;
    private String letzterKoerper;
    private CompletableFuture<Antwort> offen;
    private LogMitschnitt mitschnitt;
    private JsonNode abgerufen;
    private boolean benutzt;

    @Before
    public void vorbereiten() {
        agent.zuruecksetzen();
    }

    @After
    public void aufraeumen() throws SQLException {
        agent.freigeben();
        if (offen != null) {
            offen.cancel(true);
        }
        if (mitschnitt != null) {
            mitschnitt.close();
        }
        if (benutzt) {
            for (String mandant : MANDANTEN) {
                ausfuehren("delete from " + schema(mandant) + ".chat_gespraech");
            }
        }
        agent.zuruecksetzen();
    }

    // ---- Testagent

    @Angenommen("der Testagent wartet nach dem ersten Text, bis der Client ihn erhalten hat")
    public void ersterTextWartet() {
        agent.verhalten(TestChatAgent.Verhalten.ERSTER_TEXT_WARTET, "Erster Teil.");
    }

    @Angenommen("der Testagent hält die Antwort nach dem ersten Text an")
    public void angehalten() {
        agent.verhalten(TestChatAgent.Verhalten.ANGEHALTEN, "Läuft noch.");
    }

    @Angenommen("der Testagent wartet nach dem Text «{}» auf den Abbruch")
    public void wartetAufAbbruch(String text) {
        agent.verhalten(TestChatAgent.Verhalten.WARTET_AUF_ABBRUCH, text);
    }

    @Angenommen("der Testagent ist der Adapter ohne Agenten")
    public void ohneAgent() {
        agent.verhalten(TestChatAgent.Verhalten.NICHT_VERFUEGBAR, "");
    }

    @Angenommen("der Testagent fällt nach dem Text «{}» aus")
    public void ausfallNachText(String text) {
        agent.folge(List.of(new ChatEreignis.Text(text), new ChatEreignis.Ausfall("TestAusfall")));
    }

    /**
     * Spalten {@code art} (werkzeug, ergebnis, text, ende, ausfall), {@code name}, {@code dokumente} (Namen, mit Komma),
     * {@code belege} (Datenstand), {@code parameter} ({@code a=b; c=d}) und {@code wert} (Text, «ein/aus» oder Grund).
     */
    @Angenommen("der Testagent liefert diese Folge:")
    public void folge(DataTable tabelle) {
        List<ChatEreignis> folge = new ArrayList<>();
        for (Map<String, String> z : tabelle.asMaps()) {
            String wert = Optional.ofNullable(z.get("wert")).orElse("");
            folge.add(switch (z.get("art")) {
                case "werkzeug" -> new ChatEreignis.WerkzeugStart(z.get("name"));
                case "ergebnis" -> new ChatEreignis.WerkzeugErgebnis(z.get("name"), parameter(z.get("parameter")),
                        liste(z.get("dokumente")).stream().map(n -> new ChatEreignis.QuellDokument(dokument(n),
                                "Titel " + n)).toList(),
                        leer(z.get("belege")) ? Optional.empty() : Optional.of(new ChatEreignis.Datenstand(
                                Instant.parse("2026-11-14T08:00:00Z"), Integer.parseInt(z.get("belege")))));
                case "text" -> new ChatEreignis.Text(wert);
                case "ende" -> new ChatEreignis.Ende(Integer.parseInt(wert.split("/")[0]),
                        Integer.parseInt(wert.split("/")[1]));
                case "ausfall" -> new ChatEreignis.Ausfall(wert);
                default -> throw new IllegalArgumentException(z.get("art"));
            });
        }
        agent.folge(folge);
    }

    // ---- Fragen

    @Wenn("«{word}» die Frage «{}» stellt")
    public void fragen(String benutzer, String frage) throws Exception {
        antwort = fragenMit(benutzer, koerper(frage, null));
    }

    @Wenn("«{word}» im eigenen Gespräch die Frage «{}» stellt")
    public void imEigenen(String benutzer, String frage) throws Exception {
        antwort = fragenMit(benutzer, koerper(frage, letztesGespraech.get(benutzer)));
    }

    @Wenn("«{word}» im Gespräch von «{word}» die Frage «{}» stellt")
    public void imFremden(String benutzer, String besitzer, String frage) throws Exception {
        antwort = fragenMit(benutzer, koerper(frage, letztesGespraech.get(besitzer)));
    }

    @Wenn("«{word}» in einem unbekannten Gespräch die Frage «{}» stellt")
    public void imUnbekannten(String benutzer, String frage) throws Exception {
        antwort = fragenMit(benutzer, koerper(frage, UUID.randomUUID()));
    }

    @Wenn("«{word}» den Chat mit dem Körper «{}» aufruft")
    public void mitKoerper(String benutzer, String koerper) throws Exception {
        letzterKoerper = koerper;
        antwort = fragenMit(benutzer, koerper);
    }

    @Wenn("«{word}» eine Frage mit {int} Zeichen stellt")
    public void mitLaenge(String benutzer, int zeichen) throws Exception {
        String frage = "Lang".repeat(zeichen / 4 + 1).substring(0, zeichen);
        letzterKoerper = frage;
        antwort = fragenMit(benutzer, koerper(frage, null));
    }

    @Wenn("«{word}» die Frage «{}» stellt, ohne das Ende abzuwarten")
    public void ohneEnde(String benutzer, String frage) throws Exception {
        CompletableFuture<Void> textDa = new CompletableFuture<>();
        offen = CompletableFuture.supplyAsync(() -> {
            try {
                return lesen(benutzer, koerper(frage, null), e -> {
                    if ("text".equals(e.name())) {
                        textDa.complete(null);
                    }
                    return true;
                });
            } catch (Exception e) {
                textDa.completeExceptionally(e);
                throw new IllegalStateException(e);
            }
        });
        textDa.get(GEDULD.toSeconds(), TimeUnit.SECONDS);
    }

    @Wenn("«{word}» die Frage «{}» stellt und den Stream nach dem ersten «text» schliesst")
    public void schliessen(String benutzer, String frage) throws Exception {
        antwort = lesen(benutzer, koerper(frage, null), e -> !"text".equals(e.name()));
    }

    @Wenn("der Testagent die Antwort freigibt")
    public void freigeben() {
        agent.freigeben();
    }

    @Dann("endet der offene Stream mit «fertig»")
    public void offenerFertig() throws Exception {
        Antwort erste = offen.get(GEDULD.toSeconds(), TimeUnit.SECONDS);
        offen = null;
        assertThat(erste.ereignisse()).extracting(Ereignis::name).last().isEqualTo("fertig");
    }

    // ---- Stream

    @Dann("hat der Client das erste «text»-Ereignis erhalten, bevor der Testagent sein Ende lieferte")
    public void ersterTextVorEnde() {
        assertThat(agent.laeufe()).singleElement().satisfies(l -> assertThat(l.ersterTextVorEnde()).isTrue());
        assertThat(antwort.ereignisse()).extracting(Ereignis::name).first().isEqualTo("text");
    }

    @Dann("zeigt der Stream die Ereignisse «{}»")
    public void ereignisse(String namen) {
        assertThat(antwort.status()).isEqualTo(200);
        assertThat(antwort.roh().headers().firstValue("content-type")).hasValueSatisfying(
                t -> assertThat(t).startsWith("text/event-stream"));
        assertThat(antwort.ereignisse()).extracting(Ereignis::name).containsExactlyElementsOf(liste(namen));
    }

    @Dann("endet der Stream mit «quellen» und «fertig»")
    public void endetMitQuellenUndFertig() {
        List<String> namen = antwort.ereignisse().stream().map(Ereignis::name).toList();
        assertThat(namen.subList(namen.size() - 2, namen.size())).containsExactly("quellen", "fertig");
    }

    @Dann("endet der Stream mit «fertig»")
    public void endetMitFertig() {
        assertThat(antwort.status()).isEqualTo(200);
        assertThat(antwort.ereignisse()).extracting(Ereignis::name).last().isEqualTo("fertig");
        JsonNode fertig = antwort.ereignisse().getLast().daten();
        assertThat(fertig.path("gespraechId").asText()).isNotBlank();
        assertThat(fertig.path("tokens").has("ein")).isTrue();
    }

    @Dann("kommt «quellen» genau einmal unmittelbar vor «fertig»")
    public void quellenVorFertig() {
        List<String> namen = antwort.ereignisse().stream().map(Ereignis::name).toList();
        assertThat(namen.stream().filter("quellen"::equals).count()).isEqualTo(1);
        assertThat(namen.indexOf("quellen")).isEqualTo(namen.indexOf("fertig") - 1);
        assertThat(namen.getLast()).isEqualTo("fertig");
    }

    @Dann("nennt «quellen» genau das Dokument «{word}» und den Datenstand von «{word}» mit {int} Belegen")
    public void quellenInhalt(String dok, String werkzeug, int belege) {
        JsonNode quellen = ereignis("quellen").daten();
        assertThat(quellen).hasSize(2);
        List<JsonNode> dokumenteDa = new ArrayList<>();
        List<JsonNode> staende = new ArrayList<>();
        quellen.forEach(q -> (q.has("link") ? dokumenteDa : staende).add(q));
        assertThat(dokumenteDa).singleElement().satisfies(q -> {
            assertThat(q.path("id").asText()).isEqualTo(dokument(dok).toString());
            assertThat(q.path("titel").asText()).isEqualTo("Titel " + dok);
        });
        assertThat(staende).singleElement().satisfies(q -> {
            assertThat(q.path("werkzeug").asText()).isEqualTo(werkzeug);
            assertThat(q.path("belege").asInt()).isEqualTo(belege);
            assertThat(q.path("datenstand").asText()).isEqualTo("2026-11-14T08:00:00Z");
        });
    }

    @Dann("beginnt jeder Link in «quellen» mit «{}»")
    public void links(String anfang) {
        List<String> links = new ArrayList<>();
        ereignis("quellen").daten().forEach(q -> {
            if (q.has("link")) {
                links.add(q.path("link").asText());
            }
        });
        assertThat(links).isNotEmpty().allSatisfy(l -> assertThat(l).startsWith(anfang));
        assertThat(links).allSatisfy(l -> assertThat(l).isEqualTo(anfang + l.substring(anfang.length())));
    }

    @Dann("meldet «fehler» «{}» mit der ID des Gesprächs")
    public void fehlerMeldung(String meldung) {
        JsonNode fehler = ereignis("fehler").daten();
        assertThat(fehler.path("meldung").asText()).isEqualTo(meldung);
        assertThat(fehler.path("gespraechId").asText()).isEqualTo(einzigesGespraech().toString());
        assertThat(antwort.ereignisse()).extracting(Ereignis::name).doesNotContain("fertig", "quellen");
    }

    // ---- Fehler vor dem Stream

    @Dann("lehnt das Archiv die Anfrage mit {int} «{word}» ab")
    public void abgelehnt(int status, String fehlercode) throws IOException {
        assertThat(antwort.status()).isEqualTo(status);
        assertThat(antwort.roh().headers().firstValue("content-type")).hasValueSatisfying(
                t -> assertThat(t).startsWith("application/problem+json"));
        JsonNode problem = JSON.readTree(antwort.rumpf());
        assertThat(problem.path("fehlercode").asText()).isEqualTo(fehlercode);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        if (letzterKoerper != null) {
            assertThat(antwort.rumpf()).doesNotContain(letzterKoerper.length() > 3 ? letzterKoerper : "\u0000");
        }
    }

    @Dann("nennt die Ablehnung Retry-After von {int} bis {int} Sekunden")
    public void retryAfter(int von, int bis) {
        assertThat(antwort.roh().headers().firstValue("retry-after")).hasValueSatisfying(
                w -> assertThat(Integer.parseInt(w)).isBetween(von, bis));
    }

    @Dann("im Mandanten «{word}» hat «{word}» kein Gespräch")
    public void keinGespraech(String mandant, String benutzer) throws SQLException {
        assertThat(zahl("select count(*) from " + schema(mandant) + ".chat_gespraech where benutzer = ?",
                Testanmeldung.sub(benutzer))).isZero();
    }

    // ---- Rate-Limit

    @Angenommen("«{word}» hat im Mandanten «{word}» in den letzten 60 Minuten {int} Fragen gestellt, die älteste vor {int} Minuten")
    public void fragenGestellt(String benutzer, String mandant, int anzahl, int minuten) throws SQLException {
        benutzt = true;
        String s = schema(mandant);
        UUID id = UUID.randomUUID();
        Instant jetzt = Instant.now();
        Instant aelteste = jetzt.minus(minuten, ChronoUnit.MINUTES);
        ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) values (?, ?, "
                + "'Viele Fragen', ?, ?)", id, Testanmeldung.sub(benutzer), Timestamp.from(aelteste), Timestamp.from(jetzt));
        long schritt = Duration.between(aelteste, jetzt.minusSeconds(60)).toMillis() / Math.max(1, anzahl - 1);
        for (int i = 0; i < anzahl; i++) {
            ausfuehren("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', "
                    + "?, ?)", id, "Frage " + i, Timestamp.from(aelteste.plusMillis(schritt * i)));
        }
    }

    @Wenn("die Fragen von «{word}» im Mandanten «{word}» {int} Minuten älter werden")
    public void aelter(String benutzer, String mandant, int minuten) throws SQLException {
        String s = schema(mandant);
        ausfuehren("update " + s + ".chat_nachricht n set zeit = zeit - make_interval(mins => ?) from " + s
                + ".chat_gespraech g where g.id = n.gespraech_id and g.benutzer = ?", minuten, Testanmeldung.sub(benutzer));
    }

    @Dann("hat «{word}» im Mandanten «{word}» {int} Fragen in der letzten Stunde")
    public void fragenInDerStunde(String benutzer, String mandant, int anzahl) throws SQLException {
        String s = schema(mandant);
        assertThat(zahl("select count(*) from " + s + ".chat_nachricht n join " + s + ".chat_gespraech g on g.id = "
                + "n.gespraech_id where g.benutzer = ? and n.rolle = 'BENUTZER' and n.zeit > ?",
                Testanmeldung.sub(benutzer), Timestamp.from(Instant.now().minus(Duration.ofHours(1)))))
                .isEqualTo(anzahl);
    }

    // ---- Speicherung

    @Dann("ist im Mandanten «{word}» für «{word}» das Gespräch mit dem Titel «{}» gespeichert")
    public void gespeichert(String mandant, String benutzer, String titel) throws SQLException {
        assertThat(texte("select titel from " + schema(mandant) + ".chat_gespraech where benutzer = ?",
                Testanmeldung.sub(benutzer))).containsExactly(titel);
    }

    @Dann("ist das Gespräch im Mandanten «{word}» für «{word}» gespeichert")
    public void gespraechGespeichert(String mandant, String benutzer) throws SQLException {
        assertThat(zahl("select count(*) from " + schema(mandant) + ".chat_gespraech where benutzer = ?",
                Testanmeldung.sub(benutzer))).isEqualTo(1);
        for (String anderer : MANDANTEN) {
            if (!anderer.equals(mandant)) {
                assertThat(zahl("select count(*) from " + schema(anderer) + ".chat_gespraech where benutzer = ?",
                        Testanmeldung.sub(benutzer))).as(anderer).isZero();
            }
        }
    }

    @Dann("hat das Gespräch im Mandanten «{word}» die Frage «{}» und eine Antwort mit Status «{word}», {int} Tokens ein und {int} Tokens aus")
    public void fragenUndAntwort(String mandant, String frage, String status, int ein, int aus) throws SQLException {
        UUID id = gespraechAusDb();
        List<Map<String, Object>> zeilen = zeilen("select rolle, inhalt, status, tokens_ein, tokens_aus, werkzeuge, "
                + "quellen from " + schema(mandant) + ".chat_nachricht where gespraech_id = ? order by id", id);
        assertThat(zeilen).hasSize(2);
        assertThat(zeilen.get(0)).containsEntry("rolle", "BENUTZER").containsEntry("inhalt", frage)
                .containsEntry("status", null).containsEntry("werkzeuge", null);
        assertThat(zeilen.get(1)).containsEntry("rolle", "ASSISTENT").containsEntry("status", status)
                .containsEntry("tokens_ein", ein).containsEntry("tokens_aus", aus);
    }

    @Dann("nennt die gespeicherte Antwort den Werkzeugaufruf «{word}» mit dem Parameter «{word}» = «{}» und der ID von «{word}» ohne Titel")
    public void werkzeugGespeichert(String name, String parameter, String wert, String dok) throws Exception {
        String json = antwortSpalte("werkzeuge");
        JsonNode aufrufe = JSON.readTree(json);
        assertThat(aufrufe).hasSize(1);
        assertThat(aufrufe.get(0).path("name").asText()).isEqualTo(name);
        assertThat(aufrufe.get(0).path("parameter").path(parameter).asText()).isEqualTo(wert);
        assertThat(aufrufe.get(0).path("dokumentIds").get(0).asText()).isEqualTo(dokument(dok).toString());
        assertThat(json).doesNotContain("Titel " + dok);
    }

    @Dann("nennt die gespeicherte Antwort die Quelle «{word}» mit Link")
    public void quelleGespeichert(String dok) throws Exception {
        JsonNode quellen = JSON.readTree(antwortSpalte("quellen"));
        assertThat(quellen).hasSize(1);
        assertThat(quellen.get(0).path("link").asText()).isEqualTo("/dokument/" + dokument(dok));
    }

    @Dann("ist die Antwort im Mandanten «{word}» mit Status «{word}» und dem Inhalt «{}» gespeichert")
    public void antwortMitInhalt(String mandant, String status, String inhalt) throws Exception {
        Map<String, Object> zeile = antwortZeile(mandant);
        assertThat(zeile).containsEntry("status", status).containsEntry("inhalt", inhalt);
    }

    @Dann("ist die Antwort im Mandanten «{word}» mit Status «{word}» gespeichert")
    public void antwortMitStatus(String mandant, String status) throws Exception {
        assertThat(antwortZeile(mandant)).containsEntry("status", status);
    }

    @Dann("bemerkt der Testagent den Abbruch")
    public void abbruchBemerkt() throws InterruptedException {
        long ende = System.nanoTime() + GEDULD.toNanos();
        while (agent.laeufe().isEmpty() && System.nanoTime() < ende) {
            Thread.sleep(50);
        }
        assertThat(agent.laeufe()).singleElement().satisfies(l -> assertThat(l.abbruchGesehen()).isTrue());
    }

    // ---- Aufräumer

    @Angenommen("im Mandanten «{word}» hat «{word}» das Gespräch «{word}» mit Nachrichten vor {}")
    public void altesGespraech(String mandant, String benutzer, String name, String alter) throws SQLException {
        benutzt = true;
        String s = schema(mandant);
        UUID id = UUID.randomUUID();
        gespraeche.put(name, id);
        gespraechOrt.put(name, new String[]{mandant});
        List<Instant> zeiten = new ArrayList<>();
        for (String teil : alter.split(" und vor ")) {
            int monate = Integer.parseInt(teil.replace(" Monaten", "").trim());
            zeiten.add(Instant.now().atOffset(java.time.ZoneOffset.UTC).minusMonths(monate).toInstant());
        }
        Instant neueste = zeiten.stream().max(Instant::compareTo).orElseThrow();
        ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) values (?, ?, ?, ?, ?)",
                id, Testanmeldung.sub(benutzer), name, Timestamp.from(zeiten.getFirst()), Timestamp.from(neueste));
        for (Instant zeit : zeiten) {
            ausfuehren("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', "
                    + "'Frage', ?)", id, Timestamp.from(zeit));
            ausfuehren("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, status, quellen, zeit) "
                    + "values (?, 'ASSISTENT', 'Antwort', 'OK', '[]'::jsonb, ?)", id, Timestamp.from(zeit));
        }
    }

    @Wenn("der Chat-Aufräumer läuft")
    public void aufraeumerLaeuft() {
        TaktThread.ausfuehren("chat-aufraeumen-test", aufraeumer::alleAufraeumen);
    }

    @Dann("gibt es das Gespräch «{word}» nicht mehr")
    public void nichtMehr(String name) throws SQLException {
        String s = schema(gespraechOrt.get(name)[0]);
        assertThat(zahl("select count(*) from " + s + ".chat_gespraech where id = ?", gespraeche.get(name))).isZero();
        assertThat(zahl("select count(*) from " + s + ".chat_nachricht where gespraech_id = ?", gespraeche.get(name)))
                .isZero();
    }

    @Dann("hat das Gespräch «{word}» nur noch die {int} Nachrichten vor {int} Monaten")
    public void nurNoch(String name, int anzahl, int monate) throws SQLException {
        String s = schema(gespraechOrt.get(name)[0]);
        Instant grenze = Instant.now().atOffset(java.time.ZoneOffset.UTC).minusMonths(monate).plusDays(1).toInstant();
        assertThat(zahl("select count(*) from " + s + ".chat_nachricht where gespraech_id = ?", gespraeche.get(name)))
                .isEqualTo(anzahl);
        assertThat(zahl("select count(*) from " + s + ".chat_nachricht where gespraech_id = ? and zeit < ?",
                gespraeche.get(name), Timestamp.from(grenze))).isEqualTo(anzahl);
    }

    // ---- Gesprächsliste und Verlauf

    @Angenommen("im Mandanten «{word}» hat «{word}» {int} Gespräche")
    public void vieleGespraeche(String mandant, String benutzer, int anzahl) throws SQLException {
        benutzt = true;
        String s = schema(mandant);
        Instant jetzt = Instant.now();
        for (int i = 1; i <= anzahl; i++) {
            UUID id = UUID.randomUUID();
            gespraeche.put(benutzer + "#" + i, id);
            Instant zeit = jetzt.minus(i, ChronoUnit.MINUTES);
            ausfuehren("insert into " + s + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) values (?, ?, ?, "
                    + "?, ?)", id, Testanmeldung.sub(benutzer), "Gespräch " + i, Timestamp.from(zeit), Timestamp.from(zeit));
            ausfuehren("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', "
                    + "'Frage', ?)", id, Timestamp.from(zeit));
        }
    }

    @Wenn("«{word}» die Gesprächsliste abruft")
    public void listeAbrufen(String benutzer) throws Exception {
        HttpResponse<String> r = get(benutzer, "/api/v1/chat/gespraeche");
        assertThat(r.statusCode()).isEqualTo(200);
        abgerufen = JSON.readTree(r.body());
    }

    @Dann("enthält die Liste die {int} neuesten Gespräche von «{word}», neueste zuerst")
    public void listeNeueste(int anzahl, String benutzer) {
        assertThat(abgerufen).hasSize(anzahl);
        List<String> ids = new ArrayList<>();
        abgerufen.forEach(g -> ids.add(g.path("id").asText()));
        List<String> erwartet = new ArrayList<>();
        for (int i = 1; i <= anzahl; i++) {
            erwartet.add(gespraeche.get(benutzer + "#" + i).toString());
        }
        assertThat(ids).containsExactlyElementsOf(erwartet);
        assertThat(abgerufen.get(0).path("titel").asText()).isEqualTo("Gespräch 1");
        assertThat(abgerufen.get(0).has("geaendert")).isTrue();
    }

    @Dann("enthält die Liste {int} Gespräche")
    public void listeAnzahl(int anzahl) {
        assertThat(abgerufen).hasSize(anzahl);
    }

    @Wenn("«{word}» den Verlauf des Gesprächs von «{word}» abruft")
    public void verlaufAbrufen(String benutzer, String besitzer) throws Exception {
        HttpResponse<String> r = get(benutzer, "/api/v1/chat/gespraeche/" + letztesGespraech.get(besitzer));
        antwort = new Antwort(r.statusCode(), r, r.body(), List.of());
        abgerufen = r.statusCode() == 200 ? JSON.readTree(r.body()) : null;
    }

    @Dann("zeigt der Verlauf mit dem Titel «{}» diese Nachrichten:")
    public void verlaufZeigt(String titel, DataTable tabelle) {
        assertThat(antwort.status()).isEqualTo(200);
        assertThat(abgerufen.path("titel").asText()).isEqualTo(titel);
        List<Map<String, String>> erwartet = tabelle.asMaps();
        JsonNode nachrichten = abgerufen.path("nachrichten");
        assertThat(nachrichten).hasSize(erwartet.size());
        for (int i = 0; i < erwartet.size(); i++) {
            JsonNode n = nachrichten.get(i);
            Map<String, String> e = erwartet.get(i);
            assertThat(n.path("rolle").asText()).isEqualTo(e.get("rolle"));
            assertThat(n.path("inhalt").asText()).isEqualTo(e.get("inhalt"));
            assertThat(n.path("status").isNull() ? "" : n.path("status").asText())
                    .isEqualTo(e.get("status") == null ? "" : e.get("status"));
            List<String> quellen = new ArrayList<>();
            n.path("quellen").forEach(q -> quellen.add(q.path("link").asText()));
            List<String> soll = liste(e.get("quellen")).stream().map(d -> "/dokument/" + dokument(d)).toList();
            assertThat(quellen).containsExactlyElementsOf(soll);
            assertThat(n.path("zeit").asText()).isNotBlank();
        }
    }

    @Dann("erhielt der Testagent zur letzten Frage diesen Verlauf:")
    public void verlaufAnAgent(DataTable tabelle) {
        List<VerlaufEintrag> erwartet = tabelle.asMaps().stream()
                .map(z -> new VerlaufEintrag(ChatRolle.valueOf(z.get("rolle")), z.get("inhalt"))).toList();
        assertThat(agent.laeufe().getLast().anfrage().verlauf()).containsExactlyElementsOf(erwartet);
    }

    // ---- Mandanten und Threads

    @Dann("lief der Testagent für «{word}» im Mandanten-Kontext «{word}» mit dessen Schema, ohne Transaktion, auf einem Chat-Thread")
    public void laufImMandanten(String benutzer, String mandant) {
        String sub = Testanmeldung.sub(benutzer);
        assertThat(agent.laeufe()).filteredOn(l -> l.anfrage().benutzer().id().equals(sub)).singleElement()
                .satisfies(l -> {
                    assertThat(l.kontext()).contains(new MandantCode(mandant));
                    assertThat(l.anfrage().mandant()).isEqualTo(new MandantCode(mandant));
                    assertThat(l.schema()).isEqualTo(schema(mandant));
                    assertThat(l.transaktion()).isFalse();
                    assertThat(l.thread()).startsWith("chat-");
                });
    }

    @Dann("ist das Gespräch «{}» nur im Mandanten «{word}» gespeichert")
    public void nurImMandanten(String titel, String mandant) throws SQLException {
        for (String m : MANDANTEN) {
            assertThat(zahl("select count(*) from " + schema(m) + ".chat_gespraech g join " + schema(m)
                    + ".chat_nachricht n on n.gespraech_id = g.id where g.titel = ? and n.rolle = 'ASSISTENT'", titel))
                    .as(m).isEqualTo(m.equals(mandant) ? 1 : 0);
        }
    }

    @Dann("sind alle Plätze für laufende Fragen wieder frei")
    public void plaetzeFrei() throws InterruptedException {
        long ende = System.nanoTime() + GEDULD.toNanos();
        while (grenzen.belegt() > 0 && System.nanoTime() < ende) {
            Thread.sleep(20);
        }
        assertThat(grenzen.belegt()).isZero();
    }

    // ---- Log

    @Angenommen("das Log des Chats wird mitgeschnitten")
    public void logMitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Dann("enthält das mitgeschnittene Log weder «{}» noch «{}» noch «{}»")
    public void logOhne(String a, String b, String c) {
        String log = mitschnitt.volltext();
        assertThat(log).contains(einzigesGespraech().toString());
        assertThat(log).doesNotContain(a, b, c);
    }

    // ---- Hilfen

    private Antwort fragenMit(String benutzer, String koerper) throws Exception {
        return lesen(benutzer, koerper, e -> true);
    }

    /**
     * Sendet die Frage und liest die Ereignisse, bis der Stream endet oder {@code weiter} {@code false} liefert (dann
     * schliesst der Client die Verbindung). Meldet dem Testagenten den ersten Text.
     */
    private Antwort lesen(String benutzer, String koerper, java.util.function.Predicate<Ereignis> weiter)
            throws Exception {
        benutzt = true;
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + "/api/v1/chat"))
                .header("Authorization", "Bearer " + Testanmeldung.token(benutzer))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .timeout(GEDULD).POST(HttpRequest.BodyPublishers.ofString(koerper, StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> r = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
        List<Ereignis> ereignisse = new ArrayList<>();
        if (r.statusCode() != 200) {
            try (InputStream rumpf = r.body()) {
                return new Antwort(r.statusCode(), r, new String(rumpf.readAllBytes(), StandardCharsets.UTF_8), List.of());
            }
        }
        InputStream rumpf = r.body();
        try {
            BufferedReader zeilen = new BufferedReader(new InputStreamReader(rumpf, StandardCharsets.UTF_8));
            String name = null;
            StringBuilder daten = new StringBuilder();
            String zeile;
            while ((zeile = zeilen.readLine()) != null) {
                if (zeile.isEmpty()) {
                    if (name != null) {
                        Ereignis e = new Ereignis(name, JSON.readTree(daten.toString()));
                        ereignisse.add(e);
                        if ("text".equals(name)) {
                            agent.ersterTextErhalten();
                        }
                        if ("fertig".equals(name) || "fehler".equals(name)) {
                            erinnern(benutzer, e);
                        }
                        if (!weiter.test(e)) {
                            break;
                        }
                    }
                    name = null;
                    daten.setLength(0);
                } else if (zeile.startsWith("event:")) {
                    name = zeile.substring("event:".length()).strip();
                } else if (zeile.startsWith("data:")) {
                    daten.append(zeile.substring("data:".length()).strip());
                }
            }
        } finally {
            rumpf.close();
        }
        return new Antwort(r.statusCode(), r, null, ereignisse);
    }

    private void erinnern(String benutzer, Ereignis e) {
        String id = e.daten().path("gespraechId").asText();
        if (!id.isEmpty()) {
            letztesGespraech.put(benutzer, UUID.fromString(id));
        }
    }

    private HttpResponse<String> get(String benutzer, String pfad) throws Exception {
        benutzt = true;
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + pfad))
                .header("Authorization", "Bearer " + Testanmeldung.token(benutzer)).timeout(GEDULD).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String koerper(String frage, UUID gespraech) throws IOException {
        Map<String, Object> werte = new LinkedHashMap<>();
        werte.put("frage", frage);
        if (gespraech != null) {
            werte.put("gespraechId", gespraech.toString());
        }
        return JSON.writeValueAsString(werte);
    }

    private Ereignis ereignis(String name) {
        List<Ereignis> treffer = antwort.ereignisse().stream().filter(e -> e.name().equals(name)).toList();
        assertThat(treffer).as(name).hasSize(1);
        return treffer.getFirst();
    }

    /** Gespräch der letzten Antwort: aus {@code fertig} bzw. {@code fehler}, sonst das einzige in der Datenbank. */
    private UUID gespraechAusDb() {
        for (Ereignis e : antwort.ereignisse()) {
            if (e.daten().has("gespraechId")) {
                return UUID.fromString(e.daten().path("gespraechId").asText());
            }
        }
        return einzigesGespraech();
    }

    /** Das einzige Gespräch in beiden Mandanten. */
    private UUID einzigesGespraech() {
        try {
            List<String> ids = new ArrayList<>();
            for (String m : MANDANTEN) {
                ids.addAll(texte("select id::text from " + schema(m) + ".chat_gespraech"));
            }
            assertThat(ids).hasSize(1);
            return UUID.fromString(ids.getFirst());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String antwortSpalte(String spalte) throws SQLException {
        UUID id = gespraechAusDb();
        for (String m : MANDANTEN) {
            List<String> werte = texte("select " + spalte + "::text from " + schema(m) + ".chat_nachricht "
                    + "where gespraech_id = ? and rolle = 'ASSISTENT'", id);
            if (!werte.isEmpty()) {
                return werte.getFirst();
            }
        }
        throw new AssertionError("keine Antwort gespeichert");
    }

    /** Wartet, bis die Antwort gespeichert ist (beim Abbruch geschieht das nach dem Ende des Streams). */
    private Map<String, Object> antwortZeile(String mandant) throws Exception {
        UUID id = gespraechAusDb();
        long ende = System.nanoTime() + GEDULD.toNanos();
        while (true) {
            List<Map<String, Object>> zeilen = zeilen("select inhalt, status from " + schema(mandant)
                    + ".chat_nachricht where gespraech_id = ? and rolle = 'ASSISTENT'", id);
            if (!zeilen.isEmpty()) {
                return zeilen.getFirst();
            }
            assertThat(System.nanoTime()).as("Antwort gespeichert").isLessThan(ende);
            Thread.sleep(50);
        }
    }

    private UUID dokument(String name) {
        return dokumente.computeIfAbsent(name, n -> UUID.randomUUID());
    }

    private static boolean leer(String wert) {
        return wert == null || wert.isBlank();
    }

    private static List<String> liste(String wert) {
        return leer(wert) ? List.of() : Arrays.stream(wert.split(",")).map(String::strip).toList();
    }

    private static Map<String, Object> parameter(String wert) {
        Map<String, Object> werte = new LinkedHashMap<>();
        if (!leer(wert)) {
            for (String paar : wert.split(";")) {
                String[] teile = paar.split("=", 2);
                werte.put(teile[0].strip(), teile[1].strip());
            }
        }
        return werte;
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            setzen(p, werte);
            p.executeUpdate();
        }
    }

    private long zahl(String sql, Object... werte) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            setzen(p, werte);
            try (ResultSet z = p.executeQuery()) {
                z.next();
                return z.getLong(1);
            }
        }
    }

    private List<String> texte(String sql, Object... werte) throws SQLException {
        List<String> liste = new ArrayList<>();
        for (Map<String, Object> zeile : zeilen(sql, werte)) {
            liste.add((String) zeile.values().iterator().next());
        }
        return liste;
    }

    private List<Map<String, Object>> zeilen(String sql, Object... werte) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            setzen(p, werte);
            List<Map<String, Object>> liste = new ArrayList<>();
            try (ResultSet z = p.executeQuery()) {
                int spalten = z.getMetaData().getColumnCount();
                while (z.next()) {
                    Map<String, Object> zeile = new LinkedHashMap<>();
                    for (int i = 1; i <= spalten; i++) {
                        Object wert = z.getObject(i);
                        zeile.put(z.getMetaData().getColumnLabel(i), wert instanceof org.postgresql.util.PGobject pg
                                ? pg.getValue() : wert);
                    }
                    liste.add(zeile);
                }
            }
            return liste;
        }
    }

    private static void setzen(PreparedStatement p, Object... werte) throws SQLException {
        for (int i = 0; i < werte.length; i++) {
            if (werte[i] instanceof UUID uuid) {
                p.setObject(i + 1, uuid, java.sql.Types.OTHER);
            } else {
                p.setObject(i + 1, werte[i]);
            }
        }
    }

    /** Für {@link ChatFragen#NICHT_VERFUEGBAR} im Feature unverändert. */
    static final String NICHT_VERFUEGBAR = ChatFragen.NICHT_VERFUEGBAR;
}
