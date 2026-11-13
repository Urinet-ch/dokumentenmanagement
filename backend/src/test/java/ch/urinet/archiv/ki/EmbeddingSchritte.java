package ch.urinet.archiv.ki;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import ch.urinet.archiv.verarbeitung.adapter.in.kommando.EmbeddingKommando;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.EmbeddingAlleHandler;
import ch.urinet.archiv.verarbeitung.application.EmbeddingSpeichern;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import ch.urinet.archiv.dokument.Testdokumente;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Schritte zu Abschnitten und Embeddings (Spec 02, Aufgabe 6.3; Req 2.1, 2.4). Das Embedding-Modell ist der WireMock
 * aus {@link KiWireMock}; alle Dokumente und Texte sind synthetisch (Req 16.15).
 */
public class EmbeddingSchritte {

    private static final String PFAD = "/v1/embeddings";
    private static final String MODELL = "bge_multilingual_gemma2";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Ort(String mandant, UUID id) {
    }

    /** Ein Abschnitt aus {@code chunk}: Nummer, Text, Seiten, Zahl der Werte und die ersten Werte des Vektors. */
    private record Zeile(long id, int nr, String text, Integer von, Integer bis, Integer dimension, float[] anfang) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    AuftragArbeiter arbeiter;

    @Inject
    VerarbeitungTestHilfe verarbeitung;

    @Inject
    EmbeddingKommando kommando;

    @Inject
    TestEmbeddingPort port;

    @Inject
    AuftragSchlange schlange;

    @Inject
    EmbeddingAlleHandler teilHandler;

    @Inject
    MandantKontext kontext;

    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<String, List<Long>> gemerkt = new HashMap<>();
    private boolean aktiv;
    private long verbrauchStand;
    private int exitCode;
    private String ausgabe;
    private long letzterAuftrag;
    private long ersterTeil;
    private long lebenszeichenAusMs;

    @After
    public void aufraeumen() throws Exception {
        if (!aktiv) {
            return;
        }
        aktiv = false;
        port.setzen(null);
        if (!dokumente.isEmpty()) {
            auftraege.ausfuehren("delete from public.auftrag where nutzlast ->> 'dokumentId' in ("
                    + dokumente.values().stream().map(o -> "'" + o.id() + "'").collect(Collectors.joining(", ")) + ")");
        }
        auftraege.ausfuehren("delete from public.auftrag where typ in ('EMBEDDING', 'EMBEDDING_ALLE')");
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        dokumente.clear();
        gemerkt.clear();
        KiWireMock.zuruecksetzen();
    }

    @Angenommen("das Embedding-Modell ist bereit")
    public void bereit() throws Exception {
        aktiv = true;
        auftraege.vorbereiten();
        // auch fremde offene Aufträge der Verarbeitung (z. B. aus dem Mail-Eingang): Der Worker holt hier alle Typen
        auftraege.ausfuehren("delete from public.auftrag where typ in ('EMBEDDING', 'EMBEDDING_ALLE', 'VERARBEITEN', "
                + "'KI_VORSCHLAG', 'ARCHIVIEREN')");
        KiWireMock.zuruecksetzen();
        port.setzen(null);
        verbrauchStand = verarbeitung.kiVerbrauchStand("urinet");
    }

    @Angenommen("im Mandanten «{word}» liegen {int} archivierte Dokumente")
    public void vieleArchiviert(String mandant, int anzahl) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung
                .prepareStatement("insert into " + new MandantCode(mandant).schema() + ".dokument (id, art, titel, "
                        + "partner_name, quelle, vertraulichkeit, erstellt_von, status, volltext, seiten, pdfa_fehlt) "
                        + "values (?, 'OFFERTE', 'Probe-Offerte', 'Muster AG', 'UPLOAD', 'NORMAL', 'test-embedding', "
                        + "'ARCHIVIERT', ?, 1, false)")) {
            for (int i = 0; i < anzahl; i++) {
                UUID id = UUID.randomUUID();
                einfuegen.setObject(1, id);
                einfuegen.setString(2, seite(1, 100) + "\f");
                einfuegen.addBatch();
                dokumente.put("Viele" + i, new Ort(mandant, id));
            }
            einfuegen.executeBatch();
        }
    }

    @Angenommen("das Embedding-Modell liefert keine Vektoren wie der Dev-Stub")
    public void wieStub() {
        port.setzen(TestEmbeddingPort.Ersatz.STUB);
    }

    @Angenommen("das Embedding-Modell hat kein Token")
    public void ohneToken() {
        port.setzen(TestEmbeddingPort.Ersatz.OHNE_ZUGANG);
    }

    /** Ein Wert im JSON der Antwort, z. B. {@code 1e39}: als {@code float} gelesen unendlich. */
    @Angenommen("das Embedding-Modell antwortet mit einem Vektor mit dem Wert {word}")
    public void ausserhalbHalfvec(String wert) {
        String vektor = IntStream.range(0, KiWireMock.DIMENSION).mapToObj(i -> i == 7 ? wert : "0.5")
                .collect(Collectors.joining(",", "[", "]"));
        KiWireMock.client().register(post(urlPathEqualTo(PFAD)).atPriority(5).willReturn(okJson(
                "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":" + vektor
                        + "}],\"model\":\"" + MODELL + "\",\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}")));
    }

    @Angenommen("im Mandanten «{word}» liegt das archivierte Dokument «{word}» mit {int} Seite(n) zu je {int} Zeichen")
    public void archiviert(String mandant, String name, int seiten, int zeichen) throws SQLException {
        String volltext = IntStream.range(0, seiten).mapToObj(s -> seite(s + 1, zeichen) + "\f")
                .collect(Collectors.joining());
        anlegen(mandant, name, "ARCHIVIERT", volltext, seiten, false);
    }

    /** Wie oben, aber aus wenigen wiederkehrenden Wörtern: Der tsvector des Volltexts bleibt unter 1 MB. */
    @Angenommen("im Mandanten «{word}» liegt das archivierte Dokument «{word}» mit {int} Seiten zu je {int} Zeichen aus wenigen Wörtern")
    public void archiviertWenigeWoerter(String mandant, String name, int seiten, int zeichen) throws SQLException {
        String volltext = IntStream.range(0, seiten).mapToObj(s -> wenigeWoerter(s + 1, zeichen) + "\f")
                .collect(Collectors.joining());
        anlegen(mandant, name, "ARCHIVIERT", volltext, seiten, false);
    }

    @Angenommen("im Mandanten «{word}» liegt das Dokument «{word}» im Status «{word}» mit Text")
    public void imStatus(String mandant, String name, String status) throws SQLException {
        anlegen(mandant, name, status, seite(1, 500) + "\f", 1, false);
    }

    @Angenommen("das Dokument «{word}» hat einen Abschnitt ohne Vektor")
    public void ohneVektor(String name) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("insert into " + schema(ort) + ".chunk (dokument_id, nr, text) values (?, 1, 'alt')", ort.id());
    }

    @Angenommen("die Abschnitte von «{word}» sind gemerkt")
    public void merken(String name) throws SQLException {
        gemerkt.put(name, abschnitte(name).stream().map(Zeile::id).toList());
    }

    @Angenommen("das Embedding-Modell antwortet mit Status {int}")
    public void fehler(int status) {
        KiWireMock.client().register(post(urlPathEqualTo(PFAD)).atPriority(5)
                .willReturn(aResponse().withStatus(status).withBody("{\"error\":\"probe\"}")));
    }

    @Angenommen("das Embedding-Modell antwortet mit einem Vektor aus {int} Werten")
    public void falscheDimension(int werte) {
        String vektor = IntStream.range(0, werte).mapToObj(i -> "0.5").collect(Collectors.joining(",", "[", "]"));
        KiWireMock.client().register(post(urlPathEqualTo(PFAD)).atPriority(5).willReturn(okJson(
                "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":" + vektor
                        + "}],\"model\":\"" + MODELL + "\",\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}")));
    }

    @Wenn("für das Dokument «{word}» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet")
    public void einbetten(String name) throws Exception {
        Ort ort = ort(name);
        letzterAuftrag = auftraege.rohEinreihen(ort.mandant(), EmbeddingSpeichern.EMBEDDING,
                "{\"" + VerarbeitenHandler.DOKUMENT_ID + "\":\"" + ort.id() + "\"}");
        abarbeiten();
    }

    @Wenn("für das Dokument «{word}» der Auftrag «EMBEDDING» im Mandanten «{word}» eingereiht ist und der Worker ihn abarbeitet")
    public void einbettenIm(String name, String mandant) throws Exception {
        letzterAuftrag = auftraege.rohEinreihen(mandant, EmbeddingSpeichern.EMBEDDING,
                "{\"" + VerarbeitenHandler.DOKUMENT_ID + "\":\"" + ort(name).id() + "\"}");
        abarbeiten();
    }

    /**
     * Wie {@link #einbetten}, misst dabei aber alle 50 ms, wie lange das letzte Lebenszeichen zurückliegt: Hält die
     * Transaktion die Zeile des Auftrags, wartet das Lebenszeichen (alle 200 ms im Test) auf die Sperre.
     */
    @Wenn("für das Dokument «{word}» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet, während das Lebenszeichen gemessen wird")
    public void einbettenMitMessung(String name) throws Exception {
        Ort ort = ort(name);
        letzterAuftrag = auftraege.rohEinreihen(ort.mandant(), EmbeddingSpeichern.EMBEDDING,
                "{\"" + VerarbeitenHandler.DOKUMENT_ID + "\":\"" + ort.id() + "\"}");
        AtomicLong hoechstens = new AtomicLong();
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> lauf = CompletableFuture.runAsync(() -> {
                try {
                    abarbeiten();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, thread);
            while (!lauf.isDone()) {
                AuftragTestHilfe.Zeile zeile = auftraege.zeile(letzterAuftrag);
                if ("LAEUFT".equals(zeile.status()) && zeile.lebenszeichenAlterMs() != null) {
                    hoechstens.accumulateAndGet(zeile.lebenszeichenAlterMs(), Math::max);
                }
                Thread.sleep(50);
            }
            lauf.get(5, TimeUnit.MINUTES);
        }
        lebenszeichenAusMs = hoechstens.get();
        System.out.printf("Lebenszeichen während EMBEDDING höchstens %d ms aus%n", lebenszeichenAusMs);
    }

    @Wenn("der erste Teil der Nachberechnung zweimal läuft, ohne abgeschlossen zu werden, und nach einer Freigabe ein drittes Mal")
    public void teilWiederholen() throws Exception {
        List<Auftrag> geholt = schlange.holen(Set.of(EmbeddingSpeichern.EMBEDDING_ALLE), 1);
        assertThat(geholt).hasSize(1);
        Auftrag teil = geholt.getFirst();
        ersterTeil = teil.id();
        teilAusfuehren(teil);
        teilAusfuehren(teil);
        assertThat(schlange.freigeben(teil)).isTrue();
        List<Auftrag> wieder = schlange.holen(Set.of(EmbeddingSpeichern.EMBEDDING_ALLE), 1);
        assertThat(wieder).extracting(Auftrag::id).containsExactly(ersterTeil);
        teilAusfuehren(wieder.getFirst());
        assertThat(schlange.abschliessen(wieder.getFirst())).isTrue();
    }

    private void teilAusfuehren(Auftrag teil) throws Exception {
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            thread.submit(() -> kontext.mit(teil.mandant(), () -> teilHandler.ausfuehren(teil)))
                    .get(60, TimeUnit.SECONDS);
        }
    }

    @Wenn("der Betrieb {string} aufruft")
    public void kommando(String aufruf) throws Exception {
        List<String> argumente = List.of(aufruf.split(" "));
        assertThat(argumente.getFirst()).isEqualTo("embedding");
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        // wie die Kommandozeile in einem eigenen Thread ohne Request-Kontext und ohne Transaktion
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            PrintStream druck = new PrintStream(aus, true, StandardCharsets.UTF_8);
            exitCode = thread.submit(() -> kommando.ausfuehren(argumente.subList(1, argumente.size()), druck, druck))
                    .get(30, TimeUnit.SECONDS);
        }
        ausgabe = aus.toString(StandardCharsets.UTF_8);
    }

    @Wenn("der Worker alle Aufträge abarbeitet")
    public void abarbeiten() throws Exception {
        // eine Nachberechnung reiht je archiviertem Dokument des Mandanten einen Auftrag ein, auch für fremde Testdaten
        for (int runde = 0; runde < 1000; runde++) {
            List<CompletableFuture<Void>> laeufe = arbeiter.abholen();
            if (laeufe.isEmpty()) {
                return;
            }
            CompletableFuture.allOf(laeufe.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        }
        throw new IllegalStateException("Auftragsschlange wird nicht leer");
    }

    @Dann("endet das Kommando mit Exit-Code {int} und der Ausgabe {string}")
    public void exitCode(int code, String text) {
        assertThat(exitCode).as(ausgabe).isEqualTo(code);
        assertThat(ausgabe).contains(text);
    }

    @Dann("hat das Dokument «{word}» diese Abschnitte:")
    public void abschnitte(String name, List<Map<String, String>> erwartet) throws SQLException {
        List<Zeile> zeilen = abschnitte(name);
        assertThat(zeilen).hasSize(erwartet.size());
        for (int i = 0; i < zeilen.size(); i++) {
            Zeile zeile = zeilen.get(i);
            Map<String, String> soll = erwartet.get(i);
            assertThat(zeile.nr()).isEqualTo(Integer.parseInt(soll.get("nr")));
            assertThat(String.valueOf(zeile.von())).as("seite_von von %d", zeile.nr()).isEqualTo(soll.get("seite_von"));
            assertThat(String.valueOf(zeile.bis())).as("seite_bis von %d", zeile.nr()).isEqualTo(soll.get("seite_bis"));
        }
    }

    @Dann("hat der erste Teil {int} Aufträge «EMBEDDING» für verschiedene Dokumente und {int} Folgeteil eingereiht")
    public void teilEingereiht(int embeddings, int folgeteile) throws SQLException {
        String von = "nutzlast ->> '" + EmbeddingSpeichern.VORGAENGER + "' = '" + ersterTeil + "'";
        assertThat(zahl("select count(*) from public.auftrag where typ = 'EMBEDDING' and " + von))
                .isEqualTo(embeddings);
        assertThat(zahl("select count(distinct nutzlast ->> 'dokumentId') from public.auftrag "
                + "where typ = 'EMBEDDING' and " + von)).isEqualTo(embeddings);
        assertThat(zahl("select count(*) from public.auftrag where typ = 'EMBEDDING_ALLE' and " + von))
                .isEqualTo(folgeteile);
    }

    @Dann("ist genau {int} Nachberechnung gestartet worden")
    public void gestartet(int anzahl) throws SQLException {
        assertThat(zahl("select count(*) from public.auftrag where typ = 'EMBEDDING_ALLE' and nutzlast ->> '"
                + EmbeddingSpeichern.VORGAENGER + "' is null")).isEqualTo(anzahl);
    }

    @Dann("ist der Auftrag «EMBEDDING» fertig")
    public void fertig() throws SQLException {
        assertThat(auftraege.zeile(letzterAuftrag).status()).isEqualTo("FERTIG");
    }

    @Dann("ging kein Aufruf an das Embedding-Modell")
    public void keinAufruf() {
        assertThat(KiWireMock.client().find(postRequestedFor(urlPathEqualTo(PFAD)))).isEmpty();
    }

    @Dann("blieb das Lebenszeichen des Auftrags höchstens {int} ms aus")
    public void lebenszeichen(int ms) {
        assertThat(lebenszeichenAusMs).isPositive().isLessThanOrEqualTo(ms);
    }

    @Dann("liegt im Mandanten «{word}» kein Abschnitt von «{word}»")
    public void keinAbschnittIm(String mandant, String name) throws SQLException {
        assertThat(zahl("select count(*) from " + new MandantCode(mandant).schema() + ".chunk where dokument_id = '"
                + ort(name).id() + "'")).isZero();
    }

    @Dann("hat das Dokument «{word}» mindestens {int} Abschnitte")
    public void mindestens(String name, int anzahl) throws SQLException {
        assertThat(abschnitte(name)).hasSizeGreaterThanOrEqualTo(anzahl);
    }

    @Dann("hat das Dokument «{word}» {int} Abschnitte")
    public void anzahl(String name, int anzahl) throws SQLException {
        assertThat(abschnitte(name)).hasSize(anzahl);
    }

    @Dann("beginnt jeder Abschnitt von «{word}» mit der Kopfzeile {string} und hat höchstens {int} Zeichen Inhalt")
    public void kopfzeile(String name, String kopf, int hoechstens) throws SQLException {
        assertThat(abschnitte(name)).isNotEmpty().allSatisfy(zeile -> {
            assertThat(zeile.text()).startsWith(kopf + "\n\n");
            assertThat(zeile.text().length() - kopf.length() - 2).isLessThanOrEqualTo(hoechstens);
        });
    }

    @Dann("beginnt jeder Abschnitt von «{word}» ab dem zweiten mit {int} bis {int} Zeichen vom Ende des vorigen")
    public void ueberlappung(String name, int mindestens, int hoechstens) throws SQLException {
        List<Zeile> zeilen = abschnitte(name);
        assertThat(zeilen).hasSizeGreaterThan(1);
        for (int i = 1; i < zeilen.size(); i++) {
            String vorher = zeilen.get(i - 1).text();
            String text = zeilen.get(i).text();
            String inhalt = text.substring(text.indexOf("\n\n") + 2);
            int gemeinsam = 0;
            for (int k = Math.min(hoechstens, inhalt.length()); k > 0; k--) {
                if (vorher.endsWith(inhalt.substring(0, k))) {
                    gemeinsam = k;
                    break;
                }
            }
            assertThat(gemeinsam).as("Überlappung von Abschnitt %d", zeilen.get(i).nr())
                    .isBetween(mindestens, hoechstens);
        }
    }

    @Dann("hat jeder Abschnitt von «{word}» den Vektor des Embedding-Modells zu seinem Text mit {int} Werten")
    public void vektoren(String name, int dimension) throws SQLException {
        assertThat(abschnitte(name)).isNotEmpty().allSatisfy(zeile -> {
            assertThat(zeile.dimension()).isEqualTo(dimension);
            float[] soll = KiWireMock.vektor(zeile.text());
            for (int i = 0; i < zeile.anfang().length; i++) {
                assertThat(zeile.anfang()[i]).isCloseTo(soll[i], offset(0.01f));
            }
        });
    }

    @Dann("hat kein Abschnitt von «{word}» einen Vektor")
    public void keinVektor(String name) throws SQLException {
        assertThat(abschnitte(name)).isNotEmpty().allSatisfy(zeile -> assertThat(zeile.dimension()).isNull());
    }

    @Dann("sind die Abschnitte von «{word}» unverändert")
    public void unveraendert(String name) throws SQLException {
        assertThat(abschnitte(name).stream().map(Zeile::id).toList()).isEqualTo(gemerkt.get(name));
    }

    @Dann("sind die Abschnitte von «{word}» neu berechnet")
    public void neu(String name) throws SQLException {
        List<Long> neu = abschnitte(name).stream().map(Zeile::id).toList();
        assertThat(neu).isNotEmpty().doesNotContainAnyElementsOf(gemerkt.get(name));
    }

    @Dann("ging jeder Aufruf an das Embedding-Modell mit dem Modell «{word}», ohne «dimensions» und mit höchstens {int} Texten")
    public void aufrufe(String modell, int hoechstens) throws Exception {
        List<LoggedRequest> anfragen = KiWireMock.client().find(postRequestedFor(urlPathEqualTo(PFAD)));
        assertThat(anfragen).isNotEmpty();
        for (LoggedRequest anfrage : anfragen) {
            JsonNode koerper = JSON.readTree(anfrage.getBodyAsString());
            assertThat(koerper.path("model").asText()).isEqualTo(modell);
            assertThat(koerper.has("dimensions")).isFalse();
            assertThat(koerper.path("input").size()).isBetween(1, hoechstens);
        }
    }

    @Dann("gingen mindestens {int} Aufrufe an das Embedding-Modell, zusammen mit einem Text je Abschnitt von «{word}»")
    public void aufrufeZaehlen(int aufrufe, String name) throws Exception {
        List<LoggedRequest> anfragen = KiWireMock.client().find(postRequestedFor(urlPathEqualTo(PFAD)));
        assertThat(anfragen).hasSizeGreaterThanOrEqualTo(aufrufe);
        List<String> texte = new ArrayList<>();
        for (LoggedRequest anfrage : anfragen) {
            JSON.readTree(anfrage.getBodyAsString()).path("input").forEach(t -> texte.add(t.asText()));
        }
        assertThat(texte).containsExactlyElementsOf(abschnitte(name).stream().map(Zeile::text).toList());
    }

    @Dann("hält «ki_verbrauch» je Aufruf eine Zeile «EMBEDDING» mit dem Modell «{word}» und {int} Tokens je Text fest")
    public void verbrauch(String modell, int tokensJeText) throws Exception {
        List<LoggedRequest> anfragen = KiWireMock.client().find(postRequestedFor(urlPathEqualTo(PFAD)));
        List<Integer> erwartet = new ArrayList<>();
        for (LoggedRequest anfrage : anfragen) {
            erwartet.add(tokensJeText * JSON.readTree(anfrage.getBodyAsString()).path("input").size());
        }
        List<List<Object>> zeilen = verarbeitung.kiVerbrauch("urinet", verbrauchStand);
        assertThat(zeilen).extracting(z -> z.get(0)).containsOnly("EMBEDDING");
        assertThat(zeilen).extracting(z -> z.get(1)).containsOnly(modell);
        assertThat(zeilen).extracting(z -> (Integer) z.get(2)).containsExactlyInAnyOrderElementsOf(erwartet);
        assertThat(zeilen).extracting(z -> z.get(3)).containsOnly(0);
    }

    @Dann("hat der Auftrag «EMBEDDING» {int} Fehlversuch(e) und wartet auf den nächsten Versuch")
    public void fehlversuch(int versuche) throws SQLException {
        AuftragTestHilfe.Zeile zeile = auftraege.zeile(letzterAuftrag);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(versuche);
        assertThat(zeile.wartezeitSekunden()).isNotNull().isPositive();
    }

    @Dann("ist das Dokument «{word}» im Status «{word}» geblieben")
    public void status(String name, String status) throws SQLException {
        Ort ort = ort(name);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung
                .prepareStatement("select status from " + schema(ort) + ".dokument where id = ?")) {
            abfrage.setObject(1, ort.id());
            try (ResultSet z = abfrage.executeQuery()) {
                assertThat(z.next()).isTrue();
                assertThat(z.getString(1)).isEqualTo(status);
            }
        }
    }

    private void anlegen(String mandant, String name, String status, String volltext, int seiten, boolean pdfaFehlt)
            throws SQLException {
        UUID id = UUID.randomUUID();
        // nur urinet hat Dokumentarten aus dem Seed; im zweiten Mandanten ohne Art
        ausfuehren("insert into " + new MandantCode(mandant).schema() + ".dokument (id, art, titel, partner_name, quelle, "
                + "vertraulichkeit, erstellt_von, status, volltext, seiten, pdfa_fehlt) "
                + "values (?, ?, 'Probe-Offerte " + name + "', 'Muster AG', 'UPLOAD', 'NORMAL', "
                + "'test-embedding', ?, ?, ?, ?)", id, "urinet".equals(mandant) ? "OFFERTE" : null, status, volltext,
                seiten, pdfaFehlt);
        dokumente.put(name, new Ort(mandant, id));
    }

    /** Synthetischer Text einer Seite: Wörter «s<seite>w<nr>», getrennt durch Leerzeichen, genau {@code zeichen} lang. */
    private static String seite(int seite, int zeichen) {
        StringBuilder text = new StringBuilder();
        for (int nr = 0; text.length() < zeichen; nr++) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append("s%dw%05d".formatted(seite, nr));
        }
        return text.substring(0, zeichen).strip();
    }

    private static String wenigeWoerter(int seite, int zeichen) {
        StringBuilder text = new StringBuilder("seite" + seite);
        for (int nr = 0; text.length() < zeichen; nr++) {
            text.append(' ').append("wort").append(nr % 50);
        }
        return text.substring(0, zeichen).strip();
    }

    private List<Zeile> abschnitte(String name) throws SQLException {
        Ort ort = ort(name);
        List<Zeile> zeilen = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung
                .prepareStatement("select id, nr, text, seite_von, seite_bis, public.vector_dims(embedding), "
                        + "public.subvector(embedding, 1, 5)::text from " + schema(ort)
                        + ".chunk where dokument_id = ? order by nr")) {
            abfrage.setObject(1, ort.id());
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    String anfang = z.getString(7);
                    float[] werte = new float[0];
                    if (anfang != null) {
                        String[] teile = anfang.substring(1, anfang.length() - 1).split(",");
                        werte = new float[teile.length];
                        for (int i = 0; i < teile.length; i++) {
                            werte[i] = Float.parseFloat(teile[i]);
                        }
                    }
                    zeilen.add(new Zeile(z.getLong(1), z.getInt(2), z.getString(3), (Integer) z.getObject(4),
                            (Integer) z.getObject(5), (Integer) z.getObject(6), werte));
                }
            }
        }
        return zeilen;
    }

    private long zahl(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement(sql); ResultSet z = abfrage.executeQuery()) {
            z.next();
            return z.getLong(1);
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                anweisung.setObject(i + 1, werte[i]);
            }
            anweisung.executeUpdate();
        }
    }

    private static String schema(Ort ort) {
        return new MandantCode(ort.mandant()).schema();
    }

    private Ort ort(String name) {
        Ort ort = dokumente.get(name);
        if (ort == null) {
            throw new IllegalArgumentException("Unbekanntes Testdokument: " + name);
        }
        return ort;
    }
}
