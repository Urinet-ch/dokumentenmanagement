package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.TestEmbeddingPort;
import ch.urinet.archiv.ki.TestRerankerPort;
import ch.urinet.archiv.ki.adapter.in.werkzeug.ArchivWerkzeuge;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.ki.domain.Kopfzeile;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 02, Aufgabe 7.1d (Req 3.1, 2.3, 2.5): das Werkzeug {@code dokumente_suchen} über CDI, jeder Aufruf in
 * einem eigenen Thread ohne Anmeldung, mit Hybridsuche. Embedding-Modell und Reranker sind WireMocks wie bei Aufgabe 6.4:
 * Die Frage ist {@code e0}, ein Abschnitt mit Abstand {@code t} liegt nahe bei ihr, der Reranker kehrt die Reihenfolge
 * um. Der Titel eines Dokuments ist sein Name; so lässt sich jeder Text an den Reranker einem Dokument zuordnen.
 */
public class ChatHybridSchritte {

    private static final String EMBEDDING_PFAD = "/v1/embeddings";
    private static final String EMBEDDING_MODELL = "bge_multilingual_gemma2";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ArchivWerkzeuge werkzeuge;

    @Inject
    WerkzeugKontextRegister register;

    @Inject
    TestEmbeddingPort embedding;

    @Inject
    TestRerankerPort reranker;

    @Inject
    VerarbeitungTestHilfe verarbeitung;

    private final String lauf = "chathybrid-" + UUID.randomUUID();
    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<UUID, String> namen = new HashMap<>();
    private final Map<String, Ort> kontakte = new LinkedHashMap<>();
    private final Map<String, String> dateinamen = new HashMap<>();
    private final Map<String, Long> verbrauchStand = new HashMap<>();
    private final List<String> laeufe = new ArrayList<>();
    private boolean aktiv;
    private JsonNode antwort;
    private String antwortText;

    @After
    public void aufraeumen() throws SQLException {
        laeufe.forEach(register::entfernen);
        laeufe.clear();
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
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Ort kontakt : kontakte.values()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + new MandantCode(kontakt.mandant()).schema() + ".kontakt where id = ?")) {
                    loeschen.setObject(1, kontakt.id());
                    loeschen.executeUpdate();
                }
            }
        }
        kontakte.clear();
        dokumente.clear();
        namen.clear();
        KiWireMock.zuruecksetzen();
    }

    @Angenommen("die Hybridsuche im Chat ist bereit")
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

    @Angenommen("im Mandanten «{word}» gibt es im Hybrid-Chat die Kontakte «{}» und «{}»")
    public void kontakteAnlegen(String mandant, String erster, String zweiter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String name : List.of(erster, zweiter)) {
                UUID id = UUID.randomUUID();
                try (PreparedStatement befehl = verbindung.prepareStatement("insert into "
                        + new MandantCode(mandant).schema() + ".kontakt (id, typ, name, name_normalisiert, quelle) "
                        + "values (?, 'ORGANISATION', ?, ?, 'TEST')")) {
                    befehl.setObject(1, id);
                    befehl.setString(2, name);
                    befehl.setString(3, Normalisierer.name(name));
                    befehl.executeUpdate();
                }
                kontakte.put(name, new Ort(mandant, id));
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen diese Hybrid-Chat-Dokumente:")
    public void anlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            UUID id = dokument(mandant, zeile);
            String abschnitte = zeile.get("abschnitte");
            if (abschnitte != null && !abschnitte.isBlank()) {
                String[] teile = abschnitte.split(";");
                for (int nr = 1; nr <= teile.length; nr++) {
                    abschnitt(mandant, id, zeile.get("name"), zeile.get("art"), nr, teile[nr - 1].strip());
                }
            }
        }
    }

    @Angenommen("das Embedding-Modell antwortet im Chat mit Status {int}")
    public void embeddingFehler(int status) {
        KiWireMock.client().register(post(urlPathEqualTo(EMBEDDING_PFAD)).atPriority(4)
                .willReturn(aResponse().withStatus(status).withBody("{\"error\":\"probe\"}")));
    }

    @Wenn("«{word}» im Chat mit dokumente_suchen sucht mit:")
    public void suchen(String benutzer, DataTable tabelle) throws SQLException {
        Map<String, String> p = tabelle.asMap();
        KiWireMock.client().resetRequests();
        String id = kontext(benutzer);
        String neueste = p.get("neueste");
        Integer anzahl = p.get("anzahl") == null ? 10 : Integer.valueOf(p.get("anzahl"));
        antwortText = imFremdenThread(() -> werkzeuge.dokumenteSuchen(id, p.get("frage"), p.get("art"),
                p.get("partner"), p.get("von"), p.get("bis"), neueste == null ? null : Boolean.valueOf(neueste),
                anzahl));
        try {
            antwort = JSON.readTree(antwortText);
        } catch (Exception e) {
            throw new IllegalStateException("Antwort ist kein JSON", e);
        }
    }

    @Dann("lauten die Treffer im Chat {string}")
    public void treffer(String erwartet) {
        assertThat(antwort.has("fehler")).as(antwortText).isFalse();
        assertThat(trefferNamen()).as(antwortText).containsExactlyInAnyOrderElementsOf(liste(erwartet));
    }

    @Dann("hat der Chat genau {int} Treffer")
    public void trefferAnzahl(int anzahl) {
        assertThat(antwort.has("fehler")).as(antwortText).isFalse();
        assertThat(antwort.path("treffer").size()).as(antwortText).isEqualTo(anzahl);
    }

    @Angenommen("der Reranker hat im Chat kein Token")
    public void rerankerOhneToken() {
        reranker.setzen(TestRerankerPort.Ersatz.OHNE_ZUGANG);
    }

    @Dann("hat der Chat keine Treffer")
    public void keineTreffer() {
        assertThat(antwort.has("fehler")).as(antwortText).isFalse();
        assertThat(trefferNamen()).as(antwortText).isEmpty();
    }

    @Dann("ist die Antwort im Chat nicht degradiert und ohne Hinweis")
    public void nichtDegradiert() {
        assertThat(antwort.has("degradiert")).as(antwortText).isFalse();
        assertThat(antwort.has("hinweis")).as(antwortText).isFalse();
    }

    @Dann("ist die Antwort im Chat degradiert mit dem Hinweis {string}")
    public void degradiert(String hinweis) {
        assertThat(antwort.path("degradiert").asBoolean()).as(antwortText).isTrue();
        assertThat(antwort.path("hinweis").asText()).as(antwortText).isEqualTo(hinweis);
    }

    @Dann("nennt der Treffer «{word}» im Chat die Seiten {string}")
    public void seiten(String name, String seiten) {
        JsonNode t = trefferNamed(name);
        if (seiten.isEmpty()) {
            assertThat(t.has("seiten")).as(antwortText).isFalse();
        } else {
            assertThat(t.path("seiten").asText()).as(antwortText).isEqualTo(seiten);
        }
    }

    @Dann("erhielt der Reranker im Chat nur Texte von «{}»")
    public void rerankerNur(String erwartet) throws Exception {
        List<String> texte = rerankerTexte();
        List<String> erwartete = liste(erwartet);
        assertThat(texte).hasSameSizeAs(erwartete);
        for (String text : texte) {
            assertThat(erwartete.stream().filter(n -> n.equals(text.split("[ \\n]", 2)[0]))).as(text).hasSize(1);
        }
    }

    @Dann("enthält kein Text an den Reranker und keine Antwort des Chats {string}")
    public void rerankerOhne(String wort) throws Exception {
        assertThat(antwortText).doesNotContain(wort);
        assertThat(rerankerTexte()).noneMatch(t -> t.contains(wort));
    }

    @Dann("enthält kein Text an den Reranker und keine Antwort des Chats den Dateinamen von «{word}»")
    public void rerankerOhneDateiname(String name) throws Exception {
        String datei = dateinamen.get(name);
        assertThat(datei).isNotBlank();
        String stamm = datei.substring(0, datei.indexOf('_'));
        assertThat(antwortText).doesNotContain(datei, stamm, ".pdf");
        assertThat(rerankerTexte()).isNotEmpty().noneMatch(t -> t.contains(datei) || t.contains(stamm));
    }

    @Dann("hält «ki_verbrauch» im Mandanten «{word}» genau je eine Zeile «SUCHE» für Embedding-Modell und Reranker fest")
    public void verbrauch(String mandant) throws Exception {
        int texte = rerankerTexte().size();
        assertThat(verarbeitung.kiVerbrauch(mandant, verbrauchStand.get(mandant))).containsExactly(
                List.of("SUCHE", EMBEDDING_MODELL, KiWireMock.TOKENS_JE_TEXT, 0),
                List.of("SUCHE", KiWireMock.RERANKER_MODELL, KiWireMock.RERANKER_TOKENS_JE_TEXT * texte, 0));
    }

    // --- Hilfen -----------------------------------------------------------------------------------------------------

    private JsonNode trefferNamed(String name) {
        for (JsonNode t : antwort.path("treffer")) {
            if (name.equals(namen.get(UUID.fromString(t.path("id").asText())))) {
                return t;
            }
        }
        throw new AssertionError("Kein Treffer " + name + " in " + antwortText);
    }

    private List<String> trefferNamen() {
        List<String> liste = new ArrayList<>();
        for (JsonNode t : antwort.path("treffer")) {
            liste.add(namen.getOrDefault(UUID.fromString(t.path("id").asText()), "fremd:" + t.path("id").asText()));
        }
        return liste;
    }

    private String kontext(String benutzer) {
        String id = lauf + ":" + benutzer;
        if (!laeufe.contains(id)) {
            int trenner = benutzer.indexOf('-');
            Rolle rolle = Rolle.valueOf(benutzer.substring(0, trenner).toUpperCase(Locale.ROOT));
            MandantCode mandant = new MandantCode(benutzer.substring(trenner + 1));
            register.festhalten(id, WerkzeugKontext.fuer(mandant, new Benutzer(Testanmeldung.sub(benutzer), rolle),
                    null));
            laeufe.add(id);
        }
        return id;
    }

    /** Jeder Aufruf in einem neuen Thread ohne Request-Kontext, wie ein Callback beim Streaming. */
    private static String imFremdenThread(Callable<String> aufruf) {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return thread.submit(aufruf).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Werkzeugaufruf gescheitert", e);
        } finally {
            thread.shutdownNow();
        }
    }

    private static List<LoggedRequest> rerankerAnfragen() {
        return KiWireMock.client().find(postRequestedFor(urlPathEqualTo(KiWireMock.RERANKER_PFAD)));
    }

    private static List<String> rerankerTexte() throws Exception {
        List<LoggedRequest> anfragen = rerankerAnfragen();
        assertThat(anfragen).isNotEmpty();
        List<String> texte = new ArrayList<>();
        JSON.readTree(anfragen.getLast().getBodyAsString()).path("documents").forEach(t -> texte.add(t.asText()));
        return texte;
    }

    private static List<String> liste(String text) {
        return text == null || text.isBlank() ? List.of()
                : Arrays.stream(text.split(",")).map(String::strip).toList();
    }

    private UUID dokument(String mandant, Map<String, String> z) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        UUID id = UUID.randomUUID();
        boolean bestaetigt = !"nein".equals(z.get("bestaetigt"));
        LocalDate datum = z.get("datum") == null || z.get("datum").isBlank() ? null : LocalDate.parse(z.get("datum"));
        String partner = leer(z.get("partner"));
        Ort kontakt = partner == null ? null : kontakte.get(partner);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement befehl = verbindung
                .prepareStatement("insert into " + schema + ".dokument (id, art, titel, datum, quelle, tags, "
                        + "vertraulichkeit, person_benutzer, erstellt_von, status, bestaetigt, bestaetigt_von, "
                        + "geschaeftsjahr, volltext, dateiname, partner_id, partner_name, seiten) values (?, ?, ?, ?, "
                        + "'UPLOAD', cast('{}' as text[]), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            befehl.setObject(1, id);
            befehl.setString(2, leer(z.get("art")));
            befehl.setString(3, z.get("name"));
            befehl.setObject(4, datum, Types.DATE);
            befehl.setString(5, z.getOrDefault("vertraulichkeit", "NORMAL"));
            befehl.setString(6, leer(z.get("person")) == null ? null : Testanmeldung.sub(z.get("person")));
            befehl.setString(7, Testanmeldung.sub(z.getOrDefault("erfasser", "inhaber-urinet")));
            befehl.setString(8, z.getOrDefault("status", "ARCHIVIERT"));
            befehl.setObject(9, bestaetigt ? OffsetDateTime.now() : null, Types.TIMESTAMP_WITH_TIMEZONE);
            befehl.setString(10, bestaetigt ? "probe" : null);
            befehl.setObject(11, datum == null ? null : datum.getYear(), Types.INTEGER);
            befehl.setString(12, z.get("text"));
            befehl.setString(13, leer(z.get("dateiname")));
            befehl.setObject(14, kontakt == null ? null : kontakt.id());
            befehl.setString(15, partner);
            // Spalte «seiten»: ohne Angabe 100 (die Fundstellen der Szenarien liegen im Dokument), «-» ohne Seitenzahl
            String seiten = z.getOrDefault("seiten", "100");
            befehl.setObject(16, "-".equals(seiten) ? null : Integer.valueOf(seiten), Types.INTEGER);
            befehl.executeUpdate();
        }
        dokumente.put(z.get("name"), new Ort(mandant, id));
        namen.put(id, z.get("name"));
        if (leer(z.get("dateiname")) != null) {
            dateinamen.put(z.get("name"), z.get("dateiname"));
        }
        return id;
    }

    private static String leer(String wert) {
        return wert == null || wert.isBlank() ? null : wert;
    }

    /** Abschnitt mit Kopfzeile wie der Chunker; {@code angabe} ist «t» oder «t@von-bis» mit dem Abstand {@code t}. */
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
        float[] vektor = VektorAbfrageTest.vektor(Double.parseDouble(teile[0]),
                new java.util.Random((titel + ":" + nr).hashCode()));
        String text = Kopfzeile.mitInhalt(Kopfzeile.von(titel, artName(schema, leer(art)), null), "Abschnitt " + nr);
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
}
