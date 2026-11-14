package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.TestEmbeddingPort;
import ch.urinet.archiv.ki.TestRerankerPort;
import ch.urinet.archiv.ki.domain.Kopfzeile;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zur Hybridsuche (Spec 02, Aufgabe 6.4; Req 2.2, 2.3, 2.5–2.8): synthetische Dokumente per JDBC, ihre
 * Abschnitte direkt mit Vektoren ({@code e0 + t·n} mit zufälliger Richtung {@code n}, also umso näher an der Frage, je
 * kleiner {@code t}; «fern» steht fast senkrecht dazu), Suche über REST mit {@code modus=hybrid}. Das Embedding-Modell (WireMock) bettet jede Frage als
 * {@code e0} ein, der Reranker (WireMock, {@link KiWireMock#standardReranker()}) kehrt die Reihenfolge um. Was an
 * Embedding-Modell und Reranker hinausgeht, prüft das Request-Journal von WireMock. Jedes Szenario hängt seinen
 * Dokumenten einen eigenen Lauf-Tag an und filtert jede Suche darauf. Der Titel eines Dokuments ist sein Name; so lässt
 * sich jeder Text an den Reranker einem Dokument zuordnen (Kopfzeile «Titel – Art – Partner»).
 */
public class HybridSchritte {

    private static final String EMBEDDING_PFAD = "/v1/embeddings";
    private static final String EMBEDDING_MODELL = "bge_multilingual_gemma2";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Zeitgrenzen der Suche aus application.properties (archiv.suche.*-zeitgrenze). */
    private static final long ZEITGRENZEN_MS = 1_000 + 1_500;

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    TestEmbeddingPort embedding;

    @Inject
    TestRerankerPort reranker;

    @Inject
    VerarbeitungTestHilfe verarbeitung;

    private final String lauf = "lauf" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<UUID, String> namen = new HashMap<>();
    private final Map<String, Long> verbrauchStand = new HashMap<>();
    private boolean aktiv;
    private Response antwort;
    private long dauerMs;

    @After
    public void aufraeumen() throws SQLException {
        if (!aktiv) {
            return;
        }
        aktiv = false;
        embedding.setzen(null);
        reranker.setzen(null);
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        dokumente.clear();
        namen.clear();
        KiWireMock.zuruecksetzen();
    }

    @Angenommen("die Hybridsuche ist bereit")
    public void bereit() throws SQLException {
        aktiv = true;
        embedding.setzen(null);
        reranker.setzen(null);
        KiWireMock.zuruecksetzen();
        KiWireMock.client().register(post(urlPathEqualTo(EMBEDDING_PFAD)).atPriority(5)
                .willReturn(okJson(embeddingAntwort())));
        for (String mandant : List.of("urinet", "vhf-gsk")) {
            verbrauchStand.put(mandant, verarbeitung.kiVerbrauchStand(mandant));
        }
    }

    @Angenommen("im Mandanten «{word}» liegen diese Hybrid-Dokumente:")
    public void anlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            UUID id = dokument(mandant, zeile);
            String abschnitte = zeile.get("abschnitte");
            if (abschnitte != null) {
                String[] teile = abschnitte.split(";");
                for (int nr = 1; nr <= teile.length; nr++) {
                    abschnitt(mandant, id, zeile.get("name"), zeile.get("art"), nr, teile[nr - 1].strip());
                }
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen {int} Hybrid-Dokumente «{word}» mit dem Text {string} und je einem nahen Abschnitt")
    public void serie(String mandant, int anzahl, String praefix, String text) throws SQLException {
        for (int i = 0; i < anzahl; i++) {
            String name = praefix + "_" + i;
            Map<String, String> zeile = Map.of("name", name, "art", "OFFERTE", "vertraulichkeit", "NORMAL",
                    "bestaetigt", "ja", "erfasser", "inhaber-urinet", "text", text + " " + name);
            UUID id = dokument(mandant, zeile);
            abschnitt(mandant, id, name, "OFFERTE", 1, String.valueOf(0.01 * (i + 1)));
        }
    }

    @Angenommen("das Embedding-Modell antwortet erst nach {int} ms")
    public void embeddingVerzoegert(int ms) {
        KiWireMock.client().register(post(urlPathEqualTo(EMBEDDING_PFAD)).atPriority(5)
                .willReturn(okJson(embeddingAntwort()).withFixedDelay(ms)));
    }

    @Angenommen("der Reranker antwortet mit Status {int}")
    public void rerankerFehler(int status) {
        KiWireMock.client().register(post(urlPathEqualTo(KiWireMock.RERANKER_PFAD)).atPriority(5)
                .willReturn(aResponse().withStatus(status).withBody("{\"error\":\"probe\"}")));
    }

    @Angenommen("der Reranker antwortet erst nach {int} ms")
    public void rerankerVerzoegert(int ms) {
        KiWireMock.client().register(post(urlPathEqualTo(KiWireMock.RERANKER_PFAD)).atPriority(5)
                .willReturn(aResponse().withTransformers("reranker-antwort").withFixedDelay(ms)));
    }

    /** Ein Ergebnis mit einem Index, den es nicht gibt; die Antwort meldet trotzdem Tokens. */
    @Angenommen("der Reranker antwortet mit einem Index ausserhalb der Texte")
    public void rerankerUngueltig() {
        KiWireMock.client().register(post(urlPathEqualTo(KiWireMock.RERANKER_PFAD)).atPriority(5)
                .willReturn(okJson("{\"model\":\"" + KiWireMock.RERANKER_MODELL + "\",\"usage\":{\"prompt_tokens\":"
                        + "7,\"total_tokens\":7},\"results\":[{\"index\":999,\"relevance_score\":0.5}]}")));
    }

    @Angenommen("der Reranker hat kein Token")
    public void rerankerOhneToken() {
        reranker.setzen(TestRerankerPort.Ersatz.OHNE_ZUGANG);
    }

    @Angenommen("der Reranker liefert keine Reihenfolge wie der Dev-Stub")
    public void rerankerStub() {
        reranker.setzen(TestRerankerPort.Ersatz.STUB);
    }

    @Wenn("«{word}» hybrid sucht mit:")
    public void hybridSuchen(String benutzer, DataTable tabelle) {
        List<String[]> parameter = new ArrayList<>();
        parameter.add(new String[] {"modus", "hybrid"});
        tabelle.asLists().forEach(z -> parameter.add(new String[] {z.get(0), z.get(1)}));
        // das Journal zeigt danach nur, was diese Suche an die Modelle sendet
        KiWireMock.client().resetRequests();
        long start = System.nanoTime();
        antwort = suchen(benutzer, parameter);
        dauerMs = (System.nanoTime() - start) / 1_000_000;
    }

    @Wenn("die Vertraulichkeit von «{word}» auf «{word}» geändert wird")
    public void vertraulichkeit(String name, String stufe) throws SQLException {
        Ort ort = ort(name);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement setzen = verbindung
                .prepareStatement("update " + new MandantCode(ort.mandant()).schema()
                        + ".dokument set vertraulichkeit = ? where id = ?")) {
            setzen.setString(1, stufe);
            setzen.setObject(2, ort.id());
            assertThat(setzen.executeUpdate()).isEqualTo(1);
        }
    }

    @Dann("ist die Hybridsuche nicht degradiert")
    public void nichtDegradiert() {
        modus(false);
    }

    @Dann("ist die Hybridsuche degradiert")
    public void degradiert() {
        modus(true);
    }

    @Dann("enthält die Hybridsuche genau diese Treffer:")
    public void trefferMitFundstelle(DataTable tabelle) {
        Map<String, JsonNode> treffer = treffer();
        List<Map<String, String>> zeilen = tabelle.asMaps();
        assertThat(treffer.keySet()).containsExactlyInAnyOrderElementsOf(
                zeilen.stream().map(z -> z.get("name")).toList());
        for (Map<String, String> zeile : zeilen) {
            JsonNode t = treffer.get(zeile.get("name"));
            assertThat(fundstelle(t)).as(zeile.get("name")).isEqualTo(zeile.get("fundstelle"));
        }
    }

    @Dann("lauten die Hybridtreffer «{}»")
    public void trefferMenge(String erwartet) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(treffer().keySet()).containsExactlyInAnyOrderElementsOf(liste(erwartet));
    }

    @Dann("lauten die Hybridtreffer der Reihe nach wie die Antwort des Rerankers")
    public void reihenfolgeDesRerankers() throws Exception {
        List<String> gesendet = rerankerTexte(letzterReranker()).stream().map(HybridSchritte::name).toList();
        List<String> erwartet = new ArrayList<>(gesendet);
        java.util.Collections.reverse(erwartet);
        assertThat(new ArrayList<>(treffer().keySet())).containsExactlyElementsOf(erwartet);
    }

    @Dann("erhielt der Reranker {int} Texte mit top_n {int} und der Frage {string}")
    public void rerankerAnfrage(int texte, int topN, String frage) throws Exception {
        List<LoggedRequest> anfragen = rerankerAnfragen();
        assertThat(anfragen).hasSize(1);
        JsonNode koerper = JSON.readTree(anfragen.getFirst().getBodyAsString());
        assertThat(koerper.path("documents")).hasSize(texte);
        assertThat(koerper.path("top_n").asInt()).isEqualTo(topN);
        assertThat(koerper.path("query").asText()).isEqualTo(frage);
        assertThat(koerper.path("model").asText()).isEqualTo(KiWireMock.RERANKER_MODELL);
    }

    @Dann("erhielt der Reranker nur Texte von «{}»")
    public void rerankerNur(String erwartet) throws Exception {
        Set<String> erlaubt = new HashSet<>(liste(erwartet));
        List<LoggedRequest> anfragen = rerankerAnfragen();
        assertThat(anfragen).isNotEmpty();
        for (LoggedRequest anfrage : anfragen) {
            assertThat(rerankerTexte(anfrage)).allSatisfy(t -> assertThat(erlaubt).contains(name(t)));
        }
    }

    @Dann("ging kein Aufruf an den Reranker")
    public void keinReranker() {
        assertThat(rerankerAnfragen()).isEmpty();
    }

    @Dann("hat die Hybridantwort {int} Treffer, gesamt {int}, seite {int} und groesse {int}")
    public void form(int treffer, int gesamt, int seite, int groesse) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().getList("treffer")).hasSize(treffer);
        assertThat(antwort.jsonPath().getLong("gesamt")).isEqualTo(gesamt);
        assertThat(antwort.jsonPath().getInt("seite")).isEqualTo(seite);
        assertThat(antwort.jsonPath().getInt("groesse")).isEqualTo(groesse);
    }

    @Dann("sind die Hybridtreffer ohne Fundstelle die ersten {int} der Volltextsuche nach {string} für «{word}»")
    public void wieVolltext(int anzahl, String begriff, String benutzer) {
        Response volltext = suchen(benutzer, List.<String[]>of(new String[] {"q", begriff},
                new String[] {"groesse", String.valueOf(anzahl)}));
        assertThat(volltext.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().getList("treffer.id", String.class))
                .containsExactlyElementsOf(volltext.jsonPath().getList("treffer.id", String.class));
        assertThat(treffer().values()).allMatch(t -> !t.has("fundstelle"));
    }

    @Dann("finden die Benutzer hybrid mit dem Suchbegriff {string} diese Dokumente, und der Reranker erhält nur deren Texte:")
    public void rollen(String begriff, DataTable tabelle) throws Exception {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            KiWireMock.client().resetRequests();
            Response r = suchen(zeile.get("benutzer"), List.<String[]>of(new String[] {"modus", "hybrid"},
                    new String[] {"q", begriff}));
            String fall = zeile.get("benutzer") + " (" + zeile.get("grund") + ")";
            assertThat(r.statusCode()).as(fall).isEqualTo(200);
            assertThat(r.jsonPath().getBoolean("degradiert")).as(fall).isFalse();
            List<String> erwartet = liste(zeile.get("treffer"));
            assertThat(namen(r)).as(fall).containsExactlyInAnyOrderElementsOf(erwartet);
            List<LoggedRequest> anfragen = rerankerAnfragen();
            assertThat(anfragen).as(fall).hasSize(1);
            assertThat(rerankerTexte(anfragen.getFirst()).stream().map(HybridSchritte::name).toList()).as(fall)
                    .containsExactlyInAnyOrderElementsOf(erwartet);
        }
    }

    @Dann("antwortet die Hybridsuche für «{word}» mit Status {int} und Fehlercode «{word}»")
    public void fehler(String benutzer, int status, String code) {
        Response r = suchen(benutzer, List.<String[]>of(new String[] {"modus", "hybrid"},
                new String[] {"q", "Unfallversicherung"}));
        assertThat(ProblemDetails.pruefen(r, status)).isEqualTo(code);
        assertThat(r.asString()).doesNotContain("Unfallversicherung");
        assertThat(KiWireMock.client().find(postRequestedFor(urlPathEqualTo(EMBEDDING_PFAD)))).isEmpty();
    }

    @Dann("antwortet die Hybridsuche innert {int} ms über der Summe der Zeitgrenzen")
    public void dauer(int spielraum) {
        System.out.printf("Hybridsuche mit verzögertem Modell: %d ms (Zeitgrenzen %d ms)%n", dauerMs, ZEITGRENZEN_MS);
        assertThat(dauerMs).isLessThan(ZEITGRENZEN_MS + spielraum);
    }

    @Dann("hält «ki_verbrauch» im Mandanten «{word}» je eine Zeile «SUCHE» für das Embedding-Modell und den Reranker fest")
    public void verbrauchBeide(String mandant) throws Exception {
        int texte = rerankerTexte(letzterReranker()).size();
        assertThat(verbrauch(mandant)).containsExactly(
                List.of("SUCHE", EMBEDDING_MODELL, KiWireMock.TOKENS_JE_TEXT, 0),
                List.of("SUCHE", KiWireMock.RERANKER_MODELL, KiWireMock.RERANKER_TOKENS_JE_TEXT * texte, 0));
    }

    @Dann("hält «ki_verbrauch» im Mandanten «{word}» je eine Zeile «SUCHE» für das Embedding-Modell und den Reranker mit {int} Tokens fest")
    public void verbrauchUngueltig(String mandant, int tokens) throws SQLException {
        assertThat(verbrauch(mandant)).containsExactly(
                List.of("SUCHE", EMBEDDING_MODELL, KiWireMock.TOKENS_JE_TEXT, 0),
                List.of("SUCHE", KiWireMock.RERANKER_MODELL, tokens, 0));
    }

    @Dann("hält «ki_verbrauch» im Mandanten «{word}» nur die Zeile «SUCHE» des Embedding-Modells fest")
    public void verbrauchEmbedding(String mandant) throws SQLException {
        assertThat(verbrauch(mandant)).containsExactly(List.of("SUCHE", EMBEDDING_MODELL, KiWireMock.TOKENS_JE_TEXT, 0));
    }

    @Dann("hält «ki_verbrauch» im Mandanten «{word}» keine neue Zeile fest")
    public void keinVerbrauch(String mandant) throws SQLException {
        assertThat(verbrauch(mandant)).isEmpty();
    }

    @Wenn("«{word}» im Modus {string} sucht mit:")
    public void imModus(String benutzer, String modus, DataTable tabelle) {
        List<String[]> parameter = new ArrayList<>();
        if (!modus.isEmpty()) {
            parameter.add(new String[] {"modus", modus});
        }
        tabelle.asLists().forEach(z -> parameter.add(new String[] {z.get(0), z.get(1)}));
        antwort = suchen(benutzer, parameter);
    }

    @Und("gleicht die Antwort ohne Modus Byte für Byte der mit Modus «volltext» für «{word}» mit:")
    public void gleich(String benutzer, DataTable tabelle) {
        List<String[]> parameter = new ArrayList<>();
        tabelle.asLists().forEach(z -> parameter.add(new String[] {z.get(0), z.get(1)}));
        Response ohne = suchen(benutzer, parameter);
        List<String[]> mit = new ArrayList<>(parameter);
        mit.add(new String[] {"modus", "volltext"});
        Response volltext = suchen(benutzer, mit);
        assertThat(ohne.statusCode()).isEqualTo(200);
        assertThat(volltext.asString()).isEqualTo(ohne.asString());
        JsonNode koerper = json(ohne);
        assertThat(koerper.has("modus")).isFalse();
        assertThat(koerper.has("degradiert")).isFalse();
        koerper.path("treffer").forEach(t -> assertThat(t.has("fundstelle")).isFalse());
        assertThat(KiWireMock.client().find(postRequestedFor(urlPathEqualTo(EMBEDDING_PFAD)))).isEmpty();
        assertThat(rerankerAnfragen()).isEmpty();
    }

    @Dann("ist die Gesamtzahl der Volltextsuche {int} mit {int} Treffern auf der Seite")
    public void volltextSeite(int gesamt, int treffer) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().getLong("gesamt")).isEqualTo(gesamt);
        assertThat(antwort.jsonPath().getList("treffer")).hasSize(treffer);
        assertThat(json(antwort).has("modus")).isFalse();
    }

    private void modus(boolean degradiert) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getString("modus")).isEqualTo("hybrid");
        assertThat(antwort.jsonPath().getBoolean("degradiert")).isEqualTo(degradiert);
    }

    /** Name je Treffer in der Reihenfolge der Antwort. */
    private Map<String, JsonNode> treffer() {
        Map<String, JsonNode> treffer = new LinkedHashMap<>();
        json(antwort).path("treffer").forEach(t -> treffer.put(
                namen.getOrDefault(UUID.fromString(t.path("id").asText()), "fremd:" + t.path("id").asText()), t));
        return treffer;
    }

    private List<String> namen(Response r) {
        return r.jsonPath().getList("treffer.id", String.class).stream()
                .map(id -> namen.getOrDefault(UUID.fromString(id), "fremd:" + id)).toList();
    }

    /** «keine», «1/2-3» (Abschnitt 1, Seiten 2 bis 3) oder «1/-» (ohne Seiten). */
    private static String fundstelle(JsonNode t) {
        if (!t.has("fundstelle")) {
            return "keine";
        }
        JsonNode f = t.path("fundstelle");
        String seiten = f.path("seiteVon").isNull() && f.path("seiteBis").isNull() ? "-"
                : f.path("seiteVon").asInt() + "-" + f.path("seiteBis").asInt();
        return f.path("abschnitt").asInt() + "/" + seiten;
    }

    private List<List<Object>> verbrauch(String mandant) throws SQLException {
        return verarbeitung.kiVerbrauch(mandant, verbrauchStand.get(mandant));
    }

    private static List<LoggedRequest> rerankerAnfragen() {
        return KiWireMock.client().find(postRequestedFor(urlPathEqualTo(KiWireMock.RERANKER_PFAD)));
    }

    private static LoggedRequest letzterReranker() {
        List<LoggedRequest> anfragen = rerankerAnfragen();
        assertThat(anfragen).isNotEmpty();
        return anfragen.getLast();
    }

    private static List<String> rerankerTexte(LoggedRequest anfrage) throws Exception {
        List<String> texte = new ArrayList<>();
        JSON.readTree(anfrage.getBodyAsString()).path("documents").forEach(t -> texte.add(t.asText()));
        return texte;
    }

    /** Der Titel steht am Anfang der Kopfzeile und ist der Name des Dokuments. */
    private static String name(String text) {
        return text.split("[ \\n]", 2)[0];
    }

    private UUID dokument(String mandant, Map<String, String> z) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        UUID id = UUID.randomUUID();
        boolean bestaetigt = !"nein".equals(z.get("bestaetigt"));
        LocalDate datum = z.get("datum") == null ? null : LocalDate.parse(z.get("datum"));
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement befehl = verbindung
                .prepareStatement("insert into " + schema + ".dokument (id, art, titel, datum, quelle, tags, "
                        + "vertraulichkeit, person_benutzer, erstellt_von, status, bestaetigt, bestaetigt_von, "
                        + "geschaeftsjahr, volltext, dateiname) values (?, ?, ?, ?, 'UPLOAD', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?)")) {
            befehl.setObject(1, id);
            befehl.setString(2, z.get("art"));
            befehl.setString(3, z.get("name"));
            befehl.setObject(4, datum, Types.DATE);
            befehl.setArray(5, verbindung.createArrayOf("text", new Object[] {lauf}));
            befehl.setString(6, z.getOrDefault("vertraulichkeit", "NORMAL"));
            befehl.setString(7, z.get("person") == null ? null : Testanmeldung.sub(z.get("person")));
            befehl.setString(8, Testanmeldung.sub(z.getOrDefault("erfasser", "inhaber-urinet")));
            befehl.setString(9, z.getOrDefault("status", "ARCHIVIERT"));
            befehl.setObject(10, bestaetigt ? OffsetDateTime.now() : null, Types.TIMESTAMP_WITH_TIMEZONE);
            befehl.setString(11, bestaetigt ? "probe" : null);
            befehl.setObject(12, datum == null ? null : datum.getYear(), Types.INTEGER);
            befehl.setString(13, z.get("text"));
            befehl.setString(14, z.get("dateiname"));
            befehl.executeUpdate();
        }
        dokumente.put(z.get("name"), new Ort(mandant, id));
        namen.put(id, z.get("name"));
        return id;
    }

    /**
     * Abschnitt mit Kopfzeile wie der Chunker; {@code angabe} ist «t» oder «t@von-bis» mit dem Abstand {@code t}, oder
     * «fern».
     */
    private void abschnitt(String mandant, UUID id, String titel, String art, int nr, String angabe)
            throws SQLException {
        String schema = new MandantCode(mandant).schema();
        String[] teile = angabe.split("@");
        Integer von = null;
        Integer bis = null;
        if (teile.length > 1) {
            String[] seiten = teile[1].split("-");
            von = Integer.valueOf(seiten[0]);
            bis = Integer.valueOf(seiten[1]);
        }
        java.util.Random zufall = new java.util.Random((titel + ":" + nr).hashCode());
        float[] vektor = "fern".equals(teile[0]) ? fern(zufall) : VektorAbfrageTest.vektor(Double.parseDouble(teile[0]),
                zufall);
        String text = Kopfzeile.mitInhalt(Kopfzeile.von(titel, artName(schema, art), null), "Abschnitt " + nr);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung
                .prepareStatement("insert into " + schema + ".chunk (dokument_id, nr, text, seite_von, seite_bis, "
                        + "embedding) values (?, ?, ?, ?, ?, ?::public.halfvec)")) {
            einfuegen.setObject(1, id);
            einfuegen.setInt(2, nr);
            einfuegen.setString(3, text);
            einfuegen.setObject(4, von, Types.INTEGER);
            einfuegen.setObject(5, bis, Types.INTEGER);
            einfuegen.setString(6, Vektortext.text(vektor));
            einfuegen.executeUpdate();
        }
    }

    private String artName(String schema, String art) throws SQLException {
        if (art == null) {
            return null;
        }
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung
                .prepareStatement("select name from " + schema + ".dokumentart where code = ?")) {
            abfrage.setString(1, art);
            try (ResultSet z = abfrage.executeQuery()) {
                return z.next() ? z.getString(1) : null;
            }
        }
    }

    private Response suchen(String benutzer, List<String[]> parameter) {
        RequestSpecification anfrage = given().auth().oauth2(Testanmeldung.token(benutzer)).urlEncodingEnabled(true);
        Map<String, List<String>> werte = new LinkedHashMap<>();
        parameter.forEach(p -> werte.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[1]));
        werte.computeIfAbsent("tag", k -> new ArrayList<>()).add(lauf);
        werte.forEach((name, liste) -> anfrage.queryParam(name, liste));
        return anfrage.when().get("/api/v1/dokumente");
    }

    private Ort ort(String name) {
        Ort ort = dokumente.get(name);
        if (ort == null) {
            throw new IllegalArgumentException("Unbekanntes Testdokument: " + name);
        }
        return ort;
    }

    private static JsonNode json(Response r) {
        try {
            return JSON.readTree(r.asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> liste(String text) {
        return text == null || text.isBlank() ? List.of()
                : Arrays.stream(text.split(",")).map(String::trim).toList();
    }

    /** Frage {@code e0}. */
    private static String embeddingAntwort() {
        float[] frage = new float[KiWireMock.DIMENSION];
        frage[0] = 1f;
        String vektor = IntStream.range(0, frage.length).mapToObj(i -> Float.toString(frage[i]))
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":" + vektor
                + "}],\"model\":\"" + EMBEDDING_MODELL + "\",\"usage\":{\"prompt_tokens\":"
                + KiWireMock.TOKENS_JE_TEXT + ",\"total_tokens\":" + KiWireMock.TOKENS_JE_TEXT + "}}";
    }

    /** Zufällige Richtung, fast senkrecht zur Frage. */
    private static float[] fern(java.util.Random zufall) {
        float[] v = VektorAbfrageTest.vektor(1, zufall);
        v[0] = 0.01f;
        return v;
    }
}
