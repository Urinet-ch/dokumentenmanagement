package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.integration.adapter.in.kommando.AlteLinksKommando;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedAufraeumen;
import ch.urinet.archiv.integration.application.webseite.DateiPlaetze;
import ch.urinet.archiv.integration.domain.webseite.AlteUrl;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu den lesenden Endpunkten der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.2) gegen {@code /public/v1}.
 * Schlüssel, Reihen ({@code WEBTEST_…}), Dokumente (Titel {@code webtest-<name>}) und alte URLs entstehen zur Laufzeit
 * (nur synthetische Daten) und werden danach gelöscht; Dateien liegen synthetisch im Ablage-Port (MinIO).
 */
public class WebseitenLesenSchritte {

    private static final int JAHR = 2026;

    @Inject
    WebseitenSchluesselVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AblagePort ablage;

    @Inject
    DateiPlaetze plaetze;

    @Inject
    AenderungFeedAufraeumen aufraeumen;

    @Inject
    AlteLinksKommando alteLinksKommando;

    private record Dok(String mandant, UUID id) {
    }

    private final Map<String, String> schluessel = new HashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private final Map<String, Dok> dokumente = new LinkedHashMap<>();
    private final Set<String> mandantenMitReihen = new TreeSet<>();
    private final Set<String> mandantenMitKlassifikationen = new TreeSet<>();
    private long protokollStand;
    private final List<RohClient> offeneStreams = new ArrayList<>();
    private final Map<String, String> sha256 = new HashMap<>();
    private final Map<String, String> cursor = new HashMap<>();
    private RohClient.Kopf streamKopf;
    private String streamText = "";
    private String gelesenSha256;
    private long gelesenBytes;
    private long heapZuwachs;
    private Response antwort;
    private Response vergleich;
    private boolean embeddingGesetzt;
    private Path alteLinksCsv;
    private String alteLinksUrl;
    private UUID alteLinksZiel;
    private String alteLinksWer;
    private String alteLinksAusgabe;
    private String alteLinksFehler;
    private int alteLinksExitCode;

    @After
    public void aufraeumen() throws SQLException, InterruptedException, IOException {
        if (alteLinksCsv != null) {
            Files.deleteIfExists(alteLinksCsv);
            alteLinksCsv = null;
        }
        offeneStreams.forEach(RohClient::close);
        offeneStreams.clear();
        // der nächste Lauf braucht freie Plätze: die API gibt sie nach dem Abbruch des Clients von selbst frei
        long bis = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (plaetze.belegt() > 0 && System.nanoTime() < bis) {
            Thread.sleep(50);
        }
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(d -> jeMandant.computeIfAbsent(d.mandant(), m -> new ArrayList<>()).add(d.id()));
        for (Map.Entry<String, List<UUID>> e : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), e.getKey(), e.getValue());
            for (UUID id : e.getValue()) {
                TestAblage.loeschen(e.getKey(), "eingang/" + JAHR + "/" + id + "/", k -> true);
            }
        }
        for (String mandant : mandantenMitReihen) {
            ausfuehren("delete from " + new MandantCode(mandant).schema()
                    + ".dokumentart where code like 'WEBTEST\\_%'");
        }
        for (String mandant : mandantenMitKlassifikationen) {
            ausfuehren("delete from " + new MandantCode(mandant).schema()
                    + ".klassifikation where code like 'webtest\\_%'");
        }
        if (embeddingGesetzt) {
            embeddingGesetzt = false;
            KiWireMock.zuruecksetzen();
        }
        dokumente.clear();
        tokenIds.clear();
        schluessel.clear();
        cursor.clear();
        mandantenMitReihen.clear();
        mandantenMitKlassifikationen.clear();
    }

    @Angenommen("der Webseiten-Lesezugang «{word}» der Webseite «{word}» im Mandanten «{word}» hat die höchste Stufe «{word}»")
    public void lesezugang(String name, String webseite, String mandant, String stufe) {
        String text = verwaltung.erstellen(mandant, webseite, "lesen", stufe, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        schluessel.put(name, text);
    }

    @Angenommen("der Webseiten-Erfassungszugang «{word}» der Webseite «{word}» im Mandanten «{word}» ist angelegt")
    public void erfassungszugang(String name, String webseite, String mandant) {
        String text = verwaltung.erstellen(mandant, webseite, "erfassung", null, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        schluessel.put(name, text);
    }

    @Angenommen("im Mandanten «{word}» sind diese Reihen angelegt:")
    public void reihen(String mandant, DataTable tabelle) throws SQLException {
        mandantenMitReihen.add(mandant);
        for (Map<String, String> zeile : tabelle.asMaps()) {
            ausfuehren("insert into " + new MandantCode(mandant).schema() + ".dokumentart (code, name, vertraulichkeit, "
                    + "gesetzliche_pflicht, aktiv) values (?, ?, 'NORMAL', false, ?::boolean)", zeile.get("code"),
                    zeile.get("name"), zeile.get("aktiv"));
        }
    }

    @Angenommen("im Mandanten «{word}» sind diese Webseiten-Klassifikationen angelegt:")
    public void klassifikationen(String mandant, DataTable tabelle) throws SQLException {
        mandantenMitKlassifikationen.add(mandant);
        Set<String> angelegt = new HashSet<>();
        String schema = new MandantCode(mandant).schema();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String code = zeile.get("code");
            if (angelegt.add(code)) {
                ausfuehren("insert into " + schema + ".klassifikation "
                        + "(code, name, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter, aktiv) "
                        + "values (?, ?, false, false, false, ?::boolean, true)",
                        code, zeile.get("name"), zeile.get("oeffentlich"));
            }
            ausfuehren("insert into " + schema + ".klassifikation_wert "
                            + "(klassifikation, code, name, reihenfolge, aktiv) values (?, ?, ?, 0, true)",
                    code, zeile.get("wert"), zeile.get("wert_name"));
        }
    }

    @Angenommen("das Dokument «{word}» trägt die Webseiten-Klassifikation «{word}» mit dem Wert «{word}»")
    public void dokumentKlassifikation(String dokument, String klassifikation, String wert) throws SQLException {
        Dok d = dokumente.get(dokument);
        ausfuehren("insert into " + new MandantCode(d.mandant()).schema()
                + ".dokument_klassifikation (dokument_id, klassifikation, wert) values (?, ?, ?)",
                d.id(), klassifikation, wert);
    }

    /**
     * Spalten (alle ausser name optional): art, ausgabe, jahr, datum («heute» ist das Datum in Zürich), zugriff (Standard oeffentlich),
     * veroeffentlicht (Standard true), status (ARCHIVIERT), vertraulichkeit (NORMAL), bestaetigt (ja), seiten, text.
     * «-» lässt das Feld weg; Bezeichner stehen als JSON-String, alles andere («1», «null», «["x"]», «"öffentlich"»)
     * roh, damit auch falsche Typen im Zusatzfeld geprüft werden.
     */
    @Angenommen("im Mandanten «{word}» liegen diese Webseiten-Dokumente:")
    public void dokumenteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String name = zeile.get("name");
            String art = wert(zeile, "art", "-");
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant,
                    "-".equals(art) ? null : art, wert(zeile, "vertraulichkeit", "NORMAL"),
                    "ja".equals(wert(zeile, "bestaetigt", "ja")), "probe", null, wert(zeile, "status", "ARCHIVIERT")));
            dokumente.put(name, new Dok(mandant, id));
            List<String> felder = new ArrayList<>();
            String zugriff = wert(zeile, "zugriff", "oeffentlich");
            if (!"-".equals(zugriff)) {
                felder.add("\"zugriff\": " + bezeichnerOderRoh(zugriff));
            }
            String veroeffentlicht = wert(zeile, "veroeffentlicht", "true");
            if (!"-".equals(veroeffentlicht)) {
                felder.add("\"veroeffentlicht\": " + veroeffentlicht);
            }
            String ausgabe = wert(zeile, "ausgabe", "-");
            if (!"-".equals(ausgabe)) {
                felder.add("\"ausgabe\": \"" + ausgabe + "\"");
            }
            String jahr = wert(zeile, "jahr", "-");
            if (!"-".equals(jahr)) {
                felder.add("\"jahr\": " + jahr);
            }
            String datum = wert(zeile, "datum", "-");
            if ("heute".equals(datum)) {
                datum = LocalDate.now(ZoneId.of("Europe/Zurich")).toString();
            }
            String seiten = wert(zeile, "seiten", "-");
            ausfuehren("update " + new MandantCode(mandant).schema() + ".dokument set titel = ?, zusatz = ?::jsonb, "
                    + "datum = ?::date, seiten = ?::int, volltext = ? where id = ?", "webtest-" + name,
                    "{" + String.join(", ", felder) + "}", "-".equals(datum) ? null : datum,
                    "-".equals(seiten) ? null : seiten, wert(zeile, "text", "Webtest-Text"), id);
        }
    }

    @Angenommen("das Dokument «{word}» trägt den Dateinamen «{}» und den Partner «{}»")
    public void dateinameUndPartner(String name, String dateiname, String partner) throws SQLException {
        Dok d = dokumente.get(name);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set dateiname = ?, partner_name = ? "
                + "where id = ?", dateiname, "-".equals(partner) ? null : partner, d.id());
    }

    @Angenommen("das Dokument «{word}» hat eine Datei")
    public void datei(String name) throws SQLException {
        Dok d = dokumente.get(name);
        byte[] inhalt = dateiInhalt(name);
        AbgelegteDatei datei = ablage.ablegenEingang(new MandantCode(d.mandant()), DateiSchluessel.pdfa(JAHR, d.id()),
                new ByteArrayInputStream(inhalt), inhalt.length);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set s3_pdfa = ?, s3_version_pdfa = ?, "
                + "sha256_pdfa = ? where id = ?", datei.schluessel(), datei.versionId(), datei.sha256(), d.id());
    }

    @Angenommen("die alten URLs «{}» und «{}» führen im Mandanten «{word}» zu den Dokumenten «{word}» und «{word}»")
    public void alteUrls(String eins, String zwei, String mandant, String ersteres, String zweites)
            throws SQLException {
        zuordnen(eins, mandant, ersteres);
        zuordnen(zwei, mandant, zweites);
    }

    @Angenommen("das Dokument «{word}» ist unveröffentlicht")
    public void dokumentUnveroeffentlicht(String name) throws SQLException {
        veroeffentlicht(name, false);
    }

    @Angenommen("die CSV-Datei ordnet die URL «{}» dem Dokument «{word}» zu")
    public void alteLinksCsv(String url, String dokument) throws IOException {
        alteLinksCsv = Files.createTempFile("webtest-alte-links-", ".csv");
        alteLinksUrl = AlteUrl.normalisieren(url);
        alteLinksWer = "webtest-" + UUID.randomUUID();
        Dok ziel = dokumente.get(dokument);
        assertThat(ziel).isNotNull();
        alteLinksZiel = ziel.id();
        String csvUrl = "\"" + url.replace("\"", "\"\"") + "\"";
        Files.writeString(alteLinksCsv, "\uFEFFalte_url;dokument_id\r\n" + csvUrl + ";" + ziel.id() + "\r\n",
                StandardCharsets.UTF_8);
    }

    @Wenn("der Import alter Links im Mandanten «{word}» als Trockenlauf gestartet wird")
    public void alteLinksTrockenlauf(String mandant) {
        alteLinksImportStarten(mandant, false);
    }

    @Wenn("der Import alter Links im Mandanten «{word}» ausgeführt wird")
    public void alteLinksAusfuehren(String mandant) {
        alteLinksImportStarten(mandant, true);
    }

    @Wenn("die öffentliche Umleitung die importierte alte URL abruft")
    public void importierteUmleitung() {
        antwort = anfrage("oeffentlich").queryParam("url", alteLinksUrl).when().get("/public/v1/umleitung");
    }

    @Dann("meldet der alte-Link-Import die Zeile 2 als «{word}»")
    public void alteLinksStatus(String status) {
        assertThat(alteLinksExitCode).as(alteLinksFehler).isEqualTo("ABGELEHNT".equals(status) ? 1 : 0);
        assertThat(alteLinksFehler).isEmpty();
        assertThat(alteLinksAusgabe).contains(" " + alteLinksUrl + ": " + status);
    }

    @Dann("ist die importierte alte URL im Mandanten «{word}» nicht gespeichert")
    public void alteUrlNichtGespeichert(String mandant) throws SQLException {
        assertThat(anzahlAlteUrl(mandant)).isZero();
    }

    @Dann("ist die importierte alte URL im Mandanten «{word}» einmal gespeichert")
    public void alteUrlEinmalGespeichert(String mandant) throws SQLException {
        assertThat(anzahlAlteUrl(mandant)).isEqualTo(1);
    }

    @Dann("nennt der alte-Link-Import den Grund «{}»")
    public void alteLinksGrund(String grund) {
        assertThat(alteLinksAusgabe).contains(grund);
    }

    @Dann("ist die importierte alte URL im Mandanten «{word}» nicht zugeordnet")
    public void alteUrlNichtZugeordnet(String mandant) throws SQLException {
        // weder die URL noch das Zieldokument darf im Mandanten in alte_url vorkommen
        assertThat(anzahlAlteUrl(mandant)).isZero();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from " + new MandantCode(mandant).schema() + ".alte_url where dokument_id = ?")) {
            abfrage.setObject(1, alteLinksZiel);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                assertThat(zeile.getLong(1)).isZero();
            }
        }
    }

    @Dann("ist der alte-Link-Import einmal im Zugriffsprotokoll erfasst")
    public void alteLinksProtokolliert() throws SQLException {
        assertThat(anzahlAlteLinksProtokoll()).isEqualTo(1);
    }

    @Dann("ist der alte-Link-Import nicht im Zugriffsprotokoll erfasst")
    public void alteLinksNichtProtokolliert() throws SQLException {
        assertThat(anzahlAlteLinksProtokoll()).isZero();
    }

    @Dann("nennt die Umleitung die Dokument-ID von «{word}»")
    public void umleitungDokumentId(String dokument) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("dokumentId")).isEqualTo(dokumente.get(dokument).id().toString());
    }

    private void alteLinksImportStarten(String mandant, boolean ausfuehren) {
        List<String> argumente = new ArrayList<>(List.of("importieren", alteLinksCsv.toString(),
                "--mandant", mandant));
        if (ausfuehren) {
            argumente.addAll(List.of("--ausfuehren", "--wer", alteLinksWer, "--grund", "Cucumber-Importtest"));
        }
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        try (PrintStream ausgabe = new PrintStream(aus, true, StandardCharsets.UTF_8);
             PrintStream fehlerausgabe = new PrintStream(fehler, true, StandardCharsets.UTF_8)) {
            // Das Admin-Kommando braucht wie ein Worker einen eigenen Thread ohne Cucumber-Request-Kontext.
            alteLinksExitCode = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> alteLinksKommando.ausfuehren(argumente, ausgabe, fehlerausgabe)).join();
        }
        alteLinksAusgabe = aus.toString(StandardCharsets.UTF_8);
        alteLinksFehler = fehler.toString(StandardCharsets.UTF_8);
    }

    private long anzahlAlteUrl(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from " + new MandantCode(mandant).schema() + ".alte_url where url = ?")) {
            abfrage.setString(1, alteLinksUrl);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    private long anzahlAlteLinksProtokoll() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from m_urinet.zugriff_protokoll where benutzer = ? and aktion = 'AENDERN' "
                             + "and details ->> 'grund' = 'Cucumber-Importtest'")) {
            abfrage.setString(1, "cli:" + alteLinksWer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    private void zuordnen(String url, String mandant, String dokument) throws SQLException {
        ausfuehren("insert into " + new MandantCode(mandant).schema() + ".alte_url (url, dokument_id) values (?, ?) "
                + "on conflict (url) do nothing", AlteUrl.normalisieren(url), dokumente.get(dokument).id());
    }

    @Angenommen("der Stand des Zugriffsprotokolls von «{word}» ist für die Webseite gemerkt")
    public void protokollStand(String mandant) throws SQLException {
        protokollStand = hoechsteProtokollId(mandant);
    }

    // ---- Aufrufe

    @Wenn("der Webseiten-Lesezugang «{word}» «{}» mit der Stufe «{}» aufruft")
    public void aufrufenMitStufe(String name, String pfad, String stufe) {
        antwort = anfrage(name).header("X-Archiv-Zugriff", stufe).when().get(pfad);
    }

    @Wenn("der Webseiten-Lesezugang «{word}» «{}» ohne Stufe aufruft")
    public void aufrufenOhneStufe(String name, String pfad) {
        antwort = anfrage(name).when().get(pfad);
    }

    @Wenn("der Webseiten-Lesezugang «{word}» das Dokument «{word}» mit der Stufe «{word}» abruft")
    public void dokumentAbrufen(String name, String dokument, String stufe) {
        antwort = anfrage(name).header("X-Archiv-Zugriff", stufe).when()
                .get("/public/v1/dokumente/" + dokumente.get(dokument).id());
    }

    @Wenn("der Webseiten-Lesezugang «{word}» ein unbekanntes Dokument mit der Stufe «{word}» abruft")
    public void unbekanntesDokument(String name, String stufe) {
        vergleich = anfrage(name).header("X-Archiv-Zugriff", stufe).when()
                .get("/public/v1/dokumente/" + UUID.randomUUID());
    }

    @Wenn("der Webseiten-Lesezugang «{word}» die Datei des Dokuments «{word}» mit der Stufe «{word}» und der Adresse «{}» abruft")
    public void dateiAbrufen(String name, String dokument, String stufe, String ip) {
        antwort = anfrage(name).header("X-Archiv-Zugriff", stufe).header("X-Real-Ip", ip).when()
                .get("/public/v1/dokumente/" + dokumente.get(dokument).id() + "/datei");
    }

    @Wenn("der Webseiten-Lesezugang «{word}» die Suche mit einem Begriff von {int} Zeichen aufruft")
    public void langeSuche(String name, int laenge) {
        antwort = anfrage(name).when().get("/public/v1/suche?q=" + "a".repeat(laenge));
    }

    @Wenn("ohne Schlüssel «{}» aufruft")
    public void ohneSchluessel(String pfad) {
        antwort = given().urlEncodingEnabled(false).when().get(pfad);
    }

    @Wenn("mit dem ungültigen Schlüssel «{}» «{}» aufruft")
    public void ungueltigerSchluessel(String text, String pfad) {
        antwort = given().header("Authorization", "Bearer " + text).urlEncodingEnabled(false).when().get(pfad);
    }

    @Wenn("mit dem Erfassungs-Zugang «{word}» «{}» aufruft")
    public void erfassungsZugangAufrufen(String name, String pfad) {
        antwort = anfrage(name).when().get(pfad);
    }

    // ---- Prüfungen

    @Dann("antwortet die Lese-Schnittstelle mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Lese-Schnittstelle mit Status {int} und Fehlercode «{word}»")
    public void statusUndCode(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
    }

    @Dann("nennt die Antwort nicht das Dokument «{word}»")
    public void nichtDokument(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(titel()).doesNotContain(name);
    }

    @Dann("nennt die Antwort genau die Dokumente «{}»")
    public void genau(String namen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(titel()).containsExactlyInAnyOrderElementsOf(erwartet(namen));
    }

    @Dann("nennt die Antwort die Dokumente «{}» in dieser Reihenfolge")
    public void inReihenfolge(String namen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(titel()).containsExactlyElementsOf(erwartet(namen));
    }

    @Dann("nennt die Antwort die Seite {int} mit {int} Einträgen je Seite und der Gesamtzahl {int}")
    public void seite(int seite, int proSeite, int gesamt) {
        assertThat(antwort.<Integer>path("seite")).isEqualTo(seite);
        assertThat(antwort.<Integer>path("proSeite")).isEqualTo(proSeite);
        assertThat(antwort.<Integer>path("gesamt")).isEqualTo(gesamt);
    }

    @Dann("nennt die Antwort genau die Reihen «{}»")
    public void reihenGenau(String codes) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<List<String>>path("reihen.code"))
                .containsExactlyInAnyOrderElementsOf(codes.isEmpty() ? List.of() : Arrays.asList(codes.split(",")));
    }

    @Dann("nennt die Antwort nicht die Reihe «{word}»")
    public void reiheNicht(String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<List<String>>path("reihen.code")).doesNotContain(code);
    }

    @Dann("nennt die Antwort die Reihen «{}» in dieser Reihenfolge")
    public void reihenFolge(String paare) {
        List<String> ist = new ArrayList<>();
        List<Map<String, String>> reihen = antwort.path("reihen");
        reihen.forEach(r -> ist.add(r.get("code") + ":" + r.get("name")));
        assertThat(ist).containsExactlyElementsOf(Arrays.asList(paare.split(",")));
    }

    @Dann("nennt der Filter die öffentliche Klassifikation «{word}» mit den Werten «{}»")
    public void filterKlassifikation(String code, String werte) {
        List<Map<String, Object>> klassifikationen = antwort.path("klassifikationen");
        Map<String, Object> klassifikation = klassifikationen.stream()
                .filter(k -> code.equals(k.get("code"))).findFirst().orElseThrow();
        List<String> codes = ((List<Map<String, String>>) klassifikation.get("werte")).stream()
                .map(w -> w.get("code")).toList();
        assertThat(codes).containsExactlyElementsOf(werte.isEmpty() ? List.of() : Arrays.asList(werte.split(",")));
    }

    @Dann("nennt der Filter keine öffentliche Klassifikation «{word}»")
    public void filterKlassifikationFehlt(String code) {
        List<Map<String, Object>> klassifikationen = antwort.path("klassifikationen");
        assertThat(klassifikationen).extracting(k -> k.get("code")).doesNotContain(code);
    }

    @Dann("hat die Antwort für «{word}» die öffentliche Klassifikation «{word}» mit dem Wert «{word}»")
    public void antwortKlassifikation(String dokument, String code, String wert) {
        List<Map<String, Object>> eintraege = antwort.path("eintraege");
        Map<String, Object> eintrag = eintraege.stream()
                .filter(e -> ("webtest-" + dokument).equals(e.get("titel"))).findFirst().orElseThrow();
        List<Map<String, Object>> klassifikationen = (List<Map<String, Object>>) eintrag.get("klassifikationen");
        Map<String, Object> klassifikation = klassifikationen.stream()
                .filter(k -> code.equals(k.get("code"))).findFirst().orElseThrow();
        assertThat((List<Map<String, String>>) klassifikation.get("werte"))
                .extracting(w -> w.get("code")).containsExactly(wert);
    }

    @Dann("nennt die Antwort die Dokument-ID von «{word}»")
    public void dokumentId(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("dokumentId")).isEqualTo(dokumente.get(name).id().toString());
        assertThat(antwort.<Map<String, Object>>path("$")).containsOnlyKeys("dokumentId");
    }

    @Dann("ist die Lese-Antwort für das Dokument gleich der für ein unbekanntes Dokument")
    public void gleichWieUnbekannt() {
        assertThat(ProblemDetails.pruefen(antwort, 404)).isEqualTo("NICHT_GEFUNDEN");
        assertThat(ProblemDetails.pruefen(vergleich, 404)).isEqualTo("NICHT_GEFUNDEN");
        assertThat(ohneKennungen(antwort.asString())).isEqualTo(ohneKennungen(vergleich.asString()));
    }

    @Dann("hat die Antwort die Felder «{}» und nur diese")
    public void felder(String felder) {
        assertThat(antwort.<Map<String, Object>>path("$").keySet())
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(felder.split(",")));
    }

    @Dann("haben alle Einträge nur die Felder «{}»")
    public void alleFelder(String erlaubt) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<Map<String, Object>> eintraege = antwort.path(antwort.path("eintraege") != null ? "eintraege" : "treffer");
        assertThat(eintraege).isNotEmpty();
        Set<String> zulaessig = Set.of(erlaubt.split(","));
        for (Map<String, Object> eintrag : eintraege) {
            assertThat(eintrag.keySet()).isSubsetOf(zulaessig);
        }
    }

    @Dann("nennt die Antwort die Reihe «{word}» mit dem Namen «{}», die Ausgabe «{}», das Jahr {int}, das Datum «{}», die Zugriffsstufe «{word}» und {int} Seiten")
    public void angaben(String reihe, String name, String ausgabe, int jahr, String datum, String zugriff,
                        int seiten) {
        assertThat(antwort.<String>path("reihe.code")).isEqualTo(reihe);
        assertThat(antwort.<String>path("reihe.name")).isEqualTo(name);
        assertThat(antwort.<String>path("ausgabe")).isEqualTo(ausgabe);
        assertThat(antwort.<Integer>path("jahr")).isEqualTo(jahr);
        assertThat(LocalDate.parse(antwort.path("datum"))).isEqualTo(LocalDate.parse(datum));
        assertThat(antwort.<String>path("zugriff")).isEqualTo(zugriff);
        assertThat(antwort.<Integer>path("seiten")).isEqualTo(seiten);
    }

    @Dann("beginnt der Auszug mit «{}»")
    public void auszugBeginnt(String anfang) {
        assertThat(antwort.<String>path("auszug")).startsWith(anfang);
    }

    @Dann("hat die Suche den Rang 1 und eine Angabe zum Rückfall")
    public void rang() {
        assertThat(antwort.<Integer>path("treffer[0].rang")).isEqualTo(1);
        assertThat(antwort.<Object>path("degradiert")).isInstanceOf(Boolean.class);
    }

    @Dann("enthält der Auszug des Treffers «{word}» das Wort «{}» und höchstens {int} Zeichen")
    public void auszugTreffer(String name, String wort, int max) {
        String id = dokumente.get(name).id().toString();
        List<Map<String, Object>> treffer = antwort.path("treffer");
        Map<String, Object> t = treffer.stream().filter(x -> id.equals(x.get("id"))).findFirst().orElseThrow();
        assertThat((String) t.get("auszug")).containsIgnoringCase(wort).hasSizeLessThanOrEqualTo(max);
    }

    @Dann("steht in der Lese-Antwort weder «{}» noch «{}»")
    public void nennt(String eins, String zwei) {
        // instance trägt eine zufällige UUID, die Hex-Folgen wie «abc» oder «101» enthalten kann
        String text = antwort.asString().replaceAll("\"instance\":\"[^\"]*\"", "");
        assertThat(text).doesNotContain(eins).doesNotContain(zwei);
    }

    @Dann("trägt die Lese-Antwort den Header «{}» mit dem Wert «{}»")
    public void header(String name, String wert) {
        assertThat(antwort.header(name)).isEqualTo(wert);
    }

    @Dann("trägt die Lese-Antwort keinen Header «{}»")
    public void keinHeader(String name) {
        assertThat(antwort.headers().hasHeaderWithName(name)).as(antwort.headers().toString()).isFalse();
    }

    @Dann("ist die Antwort eine gültige signierte S3-Adresse der Datei «{word}» mit höchstens 300 Sekunden Gültigkeit")
    public void adresse(String dokument) {
        String text = antwort.path("url");
        if (text == null || text.isBlank()) {
            throw new AssertionError("Die Antwort enthält keine Datei-Adresse");
        }
        URI url;
        try {
            url = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("Die Antwort enthält keine gültige Datei-Adresse");
        }
        Instant bis = Instant.parse(antwort.path("gueltigBis"));
        long restMillis = Duration.between(Instant.now(), bis).toMillis();
        assertThat(url.getScheme()).as("Schema der Datei-Adresse").isIn("http", "https");
        assertThat(restMillis).as("Restlaufzeit gemäss gueltigBis in ms").isPositive()
                .isLessThanOrEqualTo(Duration.ofSeconds(300).toMillis());

        String query = url.getRawQuery();
        if (query == null || query.isBlank()) {
            throw new AssertionError("Die Datei-Adresse enthält keine SigV4-Parameter");
        }
        Map<String, String> parameter = new HashMap<>();
        for (String teil : query.split("&")) {
            int trenner = teil.indexOf('=');
            if (trenner <= 0 || parameter.putIfAbsent(teil.substring(0, trenner), teil.substring(trenner + 1)) != null) {
                throw new AssertionError("Die Datei-Adresse enthält ungültige oder doppelte Abfrageparameter");
            }
        }
        Set<String> sigV4Parameter = Set.of("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date",
                "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature");
        assertThat(parameter.keySet()).as("SigV4-Signaturparameter").containsAll(sigV4Parameter);
        assertThat(parameter.get("X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(parameter.get("X-Amz-Credential")).isNotBlank();
        assertThat(parameter.get("X-Amz-Date")).matches("\\d{8}T\\d{6}Z");
        assertThat(parameter.get("X-Amz-SignedHeaders")).contains("host");
        assertThat(parameter.get("X-Amz-Signature")).matches("[0-9a-f]{64}");
        String ablauf = parameter.get("X-Amz-Expires");
        if (!ablauf.matches("\\d+")) {
            throw new AssertionError("Die SigV4-Ablaufzeit ist ungültig");
        }
        int sekunden = Integer.parseInt(ablauf);
        assertThat(sekunden).as("X-Amz-Expires in Sekunden").isPositive().isLessThanOrEqualTo(300);
        Instant signiertBis = LocalDateTime.parse(parameter.get("X-Amz-Date"),
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC).plusSeconds(sekunden);
        assertThat(Duration.between(signiertBis, bis).abs().toSeconds())
                .as("Abweichung zwischen gueltigBis und X-Amz-Date + X-Amz-Expires in Sekunden")
                .isLessThanOrEqualTo(5);

        HttpResponse<byte[]> dateiAntwort = anonymAbrufen(url);
        assertThat(dateiAntwort.statusCode()).as("Status der anonymen Abfrage der signierten Adresse").isEqualTo(200);
        assertThat(sha256Hex(dateiAntwort.body())).as("SHA-256 der gelieferten Datei")
                .isEqualTo(sha256Hex(dateiInhalt(dokument)));
        Map<String, Object> antwortfelder = antwort.path("$");
        assertThat(antwortfelder.keySet()).as("Felder der Antwort").containsExactlyInAnyOrder("url", "gueltigBis");
    }

    private static HttpResponse<byte[]> anonymAbrufen(URI url) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new AssertionError("Die signierte S3-Adresse konnte nicht anonym abgerufen werden ("
                    + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Der anonyme Abruf der signierten S3-Adresse wurde unterbrochen");
        }
    }

    private static byte[] dateiInhalt(String name) {
        return ("PDF/A " + name).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] inhalt) {
        return HexFormat.of().formatHex(digest().digest(inhalt));
    }

    @Dann("steht im Zugriffsprotokoll von «{word}» genau ein neuer Eintrag HERUNTERLADEN für «{word}» von der Adresse «{}» ohne weitere Angaben")
    public void protokolliert(String mandant, String dokument, String ip) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select aktion, dokument_id::text, host(ip), benutzer, rolle, coalesce(details::text, '') from "
                        + new MandantCode(mandant).schema() + ".zugriff_protokoll where id > ?")) {
            abfrage.setLong(1, protokollStand);
            try (ResultSet z = abfrage.executeQuery()) {
                assertThat(z.next()).as("ein neuer Eintrag").isTrue();
                assertThat(z.getString(1)).isEqualTo("HERUNTERLADEN");
                assertThat(z.getString(2)).isEqualTo(dokumente.get(dokument).id().toString());
                assertThat(z.getString(3)).as("gekürzte IP").isEqualTo(ip);
                assertThat(z.getString(4)).as("Schlüssel-Kennung").startsWith("token:");
                assertThat(z.getString(5)).as("keine Rolle").isNull();
                assertThat(z.getString(6)).as("keine Einzelheiten").doesNotContain("192.0.2.77");
                assertThat(z.next()).as("genau ein neuer Eintrag").isFalse();
            }
        }
    }

    @Dann("steht im Zugriffsprotokoll von «{word}» kein neuer Eintrag")
    public void keinEintrag(String mandant) throws SQLException {
        assertThat(hoechsteProtokollId(mandant)).isEqualTo(protokollStand);
    }

    // ---- Stream (Aufgabe 11.8)

    @Angenommen("das Dokument «{word}» hat eine Datei von {int} MB")
    public void grosseDatei(String name, int megabyte) throws SQLException, IOException {
        Dok d = dokumente.get(name);
        long laenge = (long) megabyte * 1024 * 1024;
        MessageDigest digest = digest();
        InputStream erzeugt = new InputStream() {
            long gelesen;

            @Override
            public int read() {
                byte[] eins = new byte[1];
                return read(eins, 0, 1) < 0 ? -1 : eins[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (gelesen >= laenge) {
                    return -1;
                }
                int n = (int) Math.min(len, Math.min(laenge - gelesen, 65536));
                for (int i = 0; i < n; i++) {
                    b[off + i] = (byte) ((gelesen + i) * 31 % 251);
                }
                digest.update(b, off, n);
                gelesen += n;
                return n;
            }
        };
        AbgelegteDatei datei = ablage.ablegenEingang(new MandantCode(d.mandant()), DateiSchluessel.pdfa(JAHR, d.id()),
                erzeugt, laenge);
        sha256.put(name, HexFormat.of().formatHex(digest.digest()));
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set s3_pdfa = ?, s3_version_pdfa = ?, "
                + "sha256_pdfa = ? where id = ?", datei.schluessel(), datei.versionId(), datei.sha256(), d.id());
    }

    @Wenn("der Webseiten-Lesezugang «{word}» die Datei des Dokuments «{word}» mit der Stufe «{word}» und dem Range-Kopf «{}» abruft")
    public void dateiMitRange(String name, String dokument, String stufe, String range) {
        antwort = anfrage(name).header("X-Archiv-Zugriff", stufe).header("Range", range).when()
                .get("/public/v1/dokumente/" + dokumente.get(dokument).id() + "/datei");
    }

    @Wenn("der Webseiten-Lesezugang «{word}» die Datei des Dokuments «{word}» mit der Stufe «{word}» per HEAD abfragt")
    public void dateiMitHead(String name, String dokument, String stufe) {
        antwort = anfrage(name).header("X-Archiv-Zugriff", stufe).when()
                .head("/public/v1/dokumente/" + dokumente.get(dokument).id() + "/datei");
    }

    @Wenn("der Webseiten-Lesezugang «{word}» einen Stream für «{word}» mit der Stufe «{word}» öffnet und nur den Anfang liest")
    public void streamOeffnen(String name, String dokument, String stufe) throws IOException {
        assertThat(schluessel).containsKey(name);
        RohClient client = RohClient.get("localhost", RestAssured.port,
                "/public/v1/dokumente/" + dokumente.get(dokument).id() + "/datei",
                "Authorization: Bearer " + schluessel.get(name), "X-Archiv-Zugriff: " + stufe,
                "X-Real-Ip: 192.0.2.77");
        offeneStreams.add(client);
        streamKopf = client.kopf();
        if (client.kopf().status() == 200) {
            assertThat(client.koerper().readNBytes(1024)).hasSize(1024);
        } else {
            streamText = new String(client.koerper().readNBytes((int) streamKopf.laenge()), StandardCharsets.UTF_8);
        }
    }

    /** Liest langsam und misst den Heap in der Mitte. */
    @Wenn("der Webseiten-Lesezugang «{word}» die Datei von «{word}» mit der Stufe «{word}» langsam liest")
    public void langsamLesen(String name, String dokument, String stufe) throws IOException, InterruptedException {
        assertThat(schluessel).containsKey(name);
        langsamLesenEinmal(name, dokument, stufe);
    }

    private void langsamLesenEinmal(String name, String dokument, String stufe) throws IOException, InterruptedException {
        Runtime laufzeit = Runtime.getRuntime();
        heapZuwachs = Long.MAX_VALUE; // unvollständige Messung ist sicher rot
        gc();
        long vorher = laufzeit.totalMemory() - laufzeit.freeMemory();
        MessageDigest digest = digest();
        try (RohClient client = RohClient.get("localhost", RestAssured.port,
                "/public/v1/dokumente/" + dokumente.get(dokument).id() + "/datei",
                "Authorization: Bearer " + schluessel.get(name), "X-Archiv-Zugriff: " + stufe)) {
            streamKopf = client.kopf();
            assertThat(streamKopf.status()).isEqualTo(200);
            long gesamt = streamKopf.laenge();
            byte[] block = new byte[64 * 1024];
            gelesenBytes = 0;
            long messpunkt = gesamt / 2;
            boolean gemessen = false;
            for (int n; (n = client.koerper().read(block)) > 0; ) {
                digest.update(block, 0, n);
                gelesenBytes += n;
                Thread.sleep(3); // gedrosselter Client: rund 20 MB/s
                if (!gemessen && gelesenBytes >= messpunkt) {
                    // Der isolierte Messfall hat ein längeres Leerlauflimit für die Heap-Messung mit GC.
                    gc();
                    heapZuwachs = laufzeit.totalMemory() - laufzeit.freeMemory() - vorher;
                    gemessen = true;
                }
            }
        }
        gelesenSha256 = HexFormat.of().formatHex(digest.digest());
    }

    @Wenn("der Client den ersten offenen Stream schliesst")
    public void ersterStreamZu() {
        offeneStreams.getFirst().close();
    }

    @Dann("liefert der Stream Status {int} und den Medientyp «{}»")
    public void streamStatus(int status, String typ) {
        assertThat(streamKopf.status()).isEqualTo(status);
        assertThat(streamKopf.header().get("content-type")).isEqualTo(typ);
    }

    @Dann("lehnt der Stream mit Status {int} ab und nennt Retry-After {int}")
    public void streamAbgelehnt(int status, int sekunden) {
        assertThat(streamKopf.status()).isEqualTo(status);
        assertThat(streamKopf.header().get("retry-after")).isEqualTo(Integer.toString(sekunden));
    }

    @Dann("nennt der abgelehnte Stream den Fehlercode «{word}»")
    public void streamFehlercode(String code) {
        assertThat(streamText).contains("\"fehlercode\":\"" + code + "\"");
    }

    @Dann("trägt der Stream den Header «{}» mit dem Wert «{}»")
    public void streamHeader(String name, String wert) {
        assertThat(streamKopf.header().get(name.toLowerCase(java.util.Locale.ROOT))).isEqualTo(wert);
    }

    @Dann("liefert die Antwort die Datei von «{word}» als «{}» mit der Länge der Datei")
    public void dateiAlsStream(String dokument, String typ) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.header("Content-Type")).isEqualTo(typ);
        byte[] inhalt = ("PDF/A " + dokument).getBytes(StandardCharsets.UTF_8);
        assertThat(antwort.asByteArray()).isEqualTo(inhalt);
        assertThat(antwort.header("Content-Length")).isEqualTo(Integer.toString(inhalt.length));
        // nie eine Speicher-Adresse: weder Umleitung noch JSON mit url
        assertThat(antwort.header("Location")).isNull();
        assertThat(antwort.asString()).doesNotContain("X-Amz");
    }

    @Dann("steht in keinem Header der Antwort der Dateiname «{}»")
    public void keinDateiname(String dateiname) {
        assertThat(antwort.headers().asList().toString()).doesNotContain(dateiname);
    }

    @Dann("steht im Zugriffsprotokoll von «{word}» schon vor dem Ende des Streams ein Eintrag HERUNTERLADEN für «{word}»")
    public void protokollVorDemEnde(String mandant, String dokument) throws SQLException {
        // der Stream ist offen und steht (Client liest nicht weiter): der Eintrag muss festgeschrieben sein
        assertThat(hoechsteProtokollId(mandant)).isGreaterThan(protokollStand);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select count(*) from " + new MandantCode(mandant).schema() + ".zugriff_protokoll where id > ? "
                        + "and aktion = 'HERUNTERLADEN' and dokument_id = ?")) {
            abfrage.setLong(1, protokollStand);
            abfrage.setObject(2, dokumente.get(dokument).id());
            try (ResultSet z = abfrage.executeQuery()) {
                z.next();
                assertThat(z.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Dann("hält die API während des Streams keine offene Transaktion")
    public void keineTransaktion() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement abfrage = verbindung.createStatement();
             ResultSet z = abfrage.executeQuery("select count(*) from pg_stat_activity where datname = "
                     + "current_database() and pid <> pg_backend_pid() and state like 'idle in transaction%'")) {
            z.next();
            assertThat(z.getInt(1)).as("offene Transaktionen der API").isZero();
        }
    }

    @Dann("belegt die API noch {int} Plätze für Streams")
    public void belegtePlaetze(int anzahl) {
        assertThat(plaetze.belegt()).isEqualTo(anzahl);
    }

    @Dann("sind nach höchstens {int} Sekunden nur noch {int} Plätze belegt")
    public void nurNochBelegt(int sekunden, int anzahl) throws InterruptedException {
        long bis = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(sekunden);
        while (plaetze.belegt() > anzahl && System.nanoTime() < bis) {
            Thread.sleep(50);
        }
        assertThat(plaetze.belegt()).isEqualTo(anzahl);
    }

    @Dann("sind nach höchstens {int} Sekunden alle Plätze für Streams wieder frei")
    public void plaetzeFrei(int sekunden) throws InterruptedException {
        long bis = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(sekunden);
        while (plaetze.belegt() > 0 && System.nanoTime() < bis) {
            Thread.sleep(50);
        }
        assertThat(plaetze.belegt()).isZero();
    }

    @Dann("stimmt die Prüfsumme der gelesenen Datei mit der von «{word}» überein")
    public void pruefsumme(String dokument) {
        assertThat(gelesenBytes).isEqualTo(streamKopf.laenge());
        assertThat(gelesenSha256).isEqualTo(sha256.get(dokument));
    }

    @Dann("ist der Heap der API nach der Hälfte höchstens um {int} MB gewachsen")
    public void heap(int megabyte) {
        assertThat(heapZuwachs).as("Heap-Zuwachs der API nach der halben Datei, in Bytes")
                .isLessThan((long) megabyte * 1024 * 1024);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    // ---- Fundstelle der Suche (Aufgabe 11.13, Req 5.7)

    /** Das Embedding-Modell (WireMock) bettet jede Frage als Einheitsvektor {@code e0} ein. */
    @Angenommen("das Embedding-Modell bettet jede Frage der Webseiten-Suche gleich ein")
    public void embeddingBereit() {
        embeddingGesetzt = true;
        KiWireMock.zuruecksetzen();
        float[] frage = new float[KiWireMock.DIMENSION];
        frage[0] = 1f;
        String vektor = Vektortext.text(frage);
        KiWireMock.client().register(post(urlPathEqualTo("/v1/embeddings")).atPriority(5).willReturn(okJson(
                "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":" + vektor
                        + "}],\"model\":\"bge_multilingual_gemma2\",\"usage\":{\"prompt_tokens\":"
                        + KiWireMock.TOKENS_JE_TEXT + ",\"total_tokens\":" + KiWireMock.TOKENS_JE_TEXT + "}}")));
    }

    /**
     * Der Reranker (WireMock) liefert die Abschnitte der genannten Dokumente in der Reihenfolge der Tabelle mit dem
     * Wert der Spalte {@code wert}, gleich in welcher Reihenfolge die Suche sie sendet: Je Reihenfolge der Anfrage gibt
     * es einen Stub, der die Texte am Präfix {@code webtest-<name>} erkennt (höchstens 4 Dokumente). Sendet die Suche
     * andere oder mehr Texte, greift kein Stub oder die Zahl der Ergebnisse stimmt nicht, und die Suche fällt auf den
     * Volltext zurück (Schritt «lief die Suche ohne Rückfall auf den Volltext»).
     */
    @Angenommen("der Reranker ordnet die Dokumente so:")
    public void rerankerOrdnet(DataTable tabelle) {
        List<Map<String, String>> zeilen = tabelle.asMaps();
        assertThat(zeilen).hasSizeBetween(1, 4);
        List<String> namen = zeilen.stream().map(z -> z.get("name")).toList();
        for (List<String> gesendet : reihenfolgen(namen)) {
            var stub = post(urlPathEqualTo(KiWireMock.RERANKER_PFAD)).atPriority(4);
            for (int i = 0; i < gesendet.size(); i++) {
                stub = stub.withRequestBody(matchingJsonPath("$.documents[" + i + "]",
                        containing("webtest-" + gesendet.get(i) + " ")));
            }
            List<String> ergebnisse = new ArrayList<>();
            for (Map<String, String> zeile : zeilen) {
                ergebnisse.add("{\"index\":" + gesendet.indexOf(zeile.get("name")) + ",\"relevance_score\":"
                        + zeile.get("wert") + "}");
            }
            KiWireMock.client().register(stub.willReturn(okJson("{\"results\":[" + String.join(",", ergebnisse)
                    + "],\"usage\":{\"prompt_tokens\":10}}")));
        }
    }

    private static List<List<String>> reihenfolgen(List<String> namen) {
        if (namen.size() <= 1) {
            return List.of(namen);
        }
        List<List<String>> alle = new ArrayList<>();
        for (String erster : namen) {
            List<String> rest = new ArrayList<>(namen);
            rest.remove(erster);
            for (List<String> folge : reihenfolgen(rest)) {
                List<String> ganz = new ArrayList<>();
                ganz.add(erster);
                ganz.addAll(folge);
                alle.add(ganz);
            }
        }
        return alle;
    }

    @Dann("lief die Suche ohne Rückfall auf den Volltext")
    public void nichtDegradiert() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<Boolean>path("degradiert")).isFalse();
    }

    /** Spalte {@code seiten}: «von-bis» oder «-» ohne Seitenbereich; der Vektor liegt nahe bei der Frage. */
    @Angenommen("das Dokument «{word}» hat diese Abschnitte nahe der Frage:")
    public void abschnitte(String name, DataTable tabelle) throws SQLException {
        Dok d = dokumente.get(name);
        for (Map<String, String> zeile : tabelle.asMaps()) {
            int nr = Integer.parseInt(zeile.get("nr"));
            String seiten = zeile.get("seiten");
            Integer von = null;
            Integer bis = null;
            if (!"-".equals(seiten)) {
                String[] teile = seiten.split("-");
                von = Integer.valueOf(teile[0]);
                bis = Integer.valueOf(teile[1]);
            }
            float[] vektor = new float[KiWireMock.DIMENSION];
            vektor[0] = 1f;
            vektor[1] = 0.01f * nr;
            ausfuehren("insert into " + new MandantCode(d.mandant()).schema() + ".chunk (dokument_id, nr, text, "
                    + "seite_von, seite_bis, embedding) values (?, ?, ?, ?, ?, ?::public.halfvec)", d.id(), nr,
                    "webtest-" + name + " Abschnittstext-" + nr + " mit Geheimwort-Abschnitt", von, bis,
                    Vektortext.text(vektor));
        }
    }

    @Dann("trägt der Treffer «{word}» die Fundstelle mit Seite {int}")
    public void fundstelle(String name, int seite) {
        Map<String, Object> treffer = treffer(name);
        assertThat(treffer).containsKey("fundstelle");
        assertThat(treffer.get("fundstelle")).isEqualTo(Map.of("seite", seite));
    }

    @Dann("trägt der Treffer «{word}» keine Fundstelle")
    public void keineFundstelle(String name) {
        assertThat(treffer(name)).doesNotContainKey("fundstelle");
    }

    @Dann("nennt die Antwort den Text der Abschnitte nicht")
    public void keinAbschnittstext() {
        assertThat(antwort.asString()).doesNotContain("Geheimwort-Abschnitt").doesNotContain("Abschnittstext");
    }

    private Map<String, Object> treffer(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        String id = dokumente.get(name).id().toString();
        List<Map<String, Object>> treffer = antwort.path("treffer");
        return treffer.stream().filter(x -> id.equals(x.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("Treffer fehlt: " + name));
    }

    // ---- Änderungs-Feed (Aufgabe 11.9)

    @Angenommen("der Zugang «{word}» hat den Cursor «{word}» von «{}» gemerkt")
    public void cursorMerken(String name, String marke, String pfad) {
        antwort = anfrage(name).when().get(pfad);
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<List<Object>>path("eintraege")).isEmpty();
        cursor.put(marke, antwort.path("cursor"));
    }

    @Angenommen("die Webseite «{word}» hat für das Dokument «{word}» die Referenz «{}»")
    public void referenz(String webseite, String dokument, String referenz) throws SQLException {
        Dok d = dokumente.get(dokument);
        ausfuehren("insert into " + new MandantCode(d.mandant()).schema() + ".webseite_referenz (webseite, referenz, "
                + "dokument_id, sha256) values (?, ?, ?, repeat('0', 64))", webseite, referenz, d.id());
    }

    @Wenn("das Dokument «{word}» auf die Stufe «{word}» gesetzt wird")
    public void stufeSetzen(String name, String stufe) throws SQLException {
        Dok d = dokumente.get(name);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set zusatz = jsonb_set(coalesce("
                + "zusatz, '{}'::jsonb), '{zugriff}', to_jsonb(?::text)), version = version + 1 where id = ?", stufe,
                d.id());
    }

    @Wenn("das Dokument «{word}» zurückgezogen wird")
    public void zurueckziehen(String name) throws SQLException {
        veroeffentlicht(name, false);
    }

    @Wenn("das Dokument «{word}» veröffentlicht wird")
    public void veroeffentlichen(String name) throws SQLException {
        veroeffentlicht(name, true);
    }

    private void veroeffentlicht(String name, boolean wert) throws SQLException {
        Dok d = dokumente.get(name);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set zusatz = jsonb_set(coalesce("
                + "zusatz, '{}'::jsonb), '{veroeffentlicht}', to_jsonb(?::boolean)), version = version + 1 "
                + "where id = ?", wert, d.id());
    }

    @Wenn("das Dokument «{word}» eine neue Version erhält")
    public void neueVersion(String name) throws SQLException {
        Dok d = dokumente.get(name);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set version = version + 1 "
                + "where id = ?", d.id());
    }

    @Wenn("das Dokument «{word}» den Status «{word}» erhält")
    public void statusSetzen(String name, String status) throws SQLException {
        Dok d = dokumente.get(name);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set status = ?, version = version + 1 "
                + "where id = ?", status, d.id());
    }

    @Wenn("das Dokument «{word}» die Vertraulichkeit «{word}» erhält")
    public void vertraulichkeitSetzen(String name, String vertraulichkeit) throws SQLException {
        Dok d = dokumente.get(name);
        ausfuehren("update " + new MandantCode(d.mandant()).schema() + ".dokument set vertraulichkeit = ?, "
                + "version = version + 1 where id = ?", vertraulichkeit, d.id());
    }

    @Wenn("der Zugang «{word}» «{}» ab dem Cursor «{word}» abruft")
    public void feedAb(String name, String pfad, String marke) {
        feedAbrufen(name, pfad, marke, null, null);
    }

    @Wenn("der Zugang «{word}» «{}» ab dem Cursor «{word}» mit höchstens {int} Einträgen abruft")
    public void feedAbMax(String name, String pfad, String marke, int max) {
        feedAbrufen(name, pfad, marke, max, null);
    }

    @Wenn("der Zugang «{word}» «{}» ab dem Cursor «{word}» mit der Stufe «{}» im Header abruft")
    public void feedAbMitStufe(String name, String pfad, String marke, String stufe) {
        feedAbrufen(name, pfad, marke, null, stufe);
    }

    private void feedAbrufen(String name, String pfad, String marke, Integer max, String stufe) {
        RequestSpecification spec = anfrage(name).queryParam("seit", cursor.get(marke));
        if (max != null) {
            spec = spec.queryParam("max", max);
        }
        if (stufe != null) {
            spec = spec.header("X-Archiv-Zugriff", stufe).header("X-Archiv-Client", "kein-hash");
        }
        antwort = spec.when().get(pfad);
        if (antwort.statusCode() == 200) {
            cursor.put(marke, antwort.path("cursor"));
        }
    }

    @Wenn("der Zugang «{word}» «{}» mit dem Cursor-Text «{}» abruft")
    public void feedMitText(String name, String pfad, String text) {
        antwort = anfrage(name).queryParam("seit", text).when().get(pfad);
    }

    @Wenn("der Zugang «{word}» «{}» mit höchstens «{}» Einträgen abruft")
    public void feedMitMax(String name, String pfad, String max) {
        antwort = anfrage(name).queryParam("max", max).when().get(pfad);
    }

    @Wenn("der Zugang «{word}» «{}» ohne Cursor abruft")
    public void feedOhneCursor(String name, String pfad) {
        antwort = anfrage(name).when().get(pfad);
    }

    @Wenn("die Einträge des Feeds im Mandanten «{word}» {int} Tage später bereinigt werden")
    public void feedBereinigen(String mandant, int tage) {
        // eigener Thread ohne Request-Kontext wie der Worker; MandantKontext.mit verlangt das
        java.util.concurrent.CompletableFuture.runAsync(() -> aufraeumen.aufraeumen(new MandantCode(mandant),
                Instant.now().plus(Duration.ofDays(tage)))).join();
    }

    @Dann("nennt der Feed genau diese Änderungen:")
    public void feedGenau(DataTable tabelle) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<String> soll = new ArrayList<>();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            soll.add(dokumente.get(zeile.get("dokument")).id() + " " + zeile.get("art") + " " + zeile.get("stufe"));
        }
        List<Map<String, Object>> ist = antwort.path("eintraege");
        assertThat(ist.stream().map(e -> e.get("dokumentId") + " " + e.get("art") + " " + e.get("stufe")).toList())
                .containsExactlyElementsOf(soll);
        ist.forEach(e -> assertThat(Instant.parse((String) e.get("zeit"))).isBefore(Instant.now().plusSeconds(5)));
    }

    @Dann("nennt der Feed keine Änderungen")
    public void feedLeer() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<List<Object>>path("eintraege")).isEmpty();
        assertThat(antwort.<String>path("cursor")).isNotBlank();
    }

    @Dann("nennt der Feed genau diese Erfassungs-Änderungen:")
    public void erfassungGenau(DataTable tabelle) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<String> soll = new ArrayList<>();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            soll.add(dokumente.get(zeile.get("dokument")).id() + " " + zeile.get("referenz") + " " + zeile.get("status")
                    + " " + zeile.get("fehlercode") + " " + zeile.get("version"));
        }
        List<Map<String, Object>> ist = antwort.path("eintraege");
        assertThat(ist.stream().map(e -> e.get("dokumentId") + " " + (e.get("referenz") == null ? "-" : e.get("referenz"))
                + " " + e.get("status") + " " + (e.get("fehlercode") == null ? "-" : e.get("fehlercode")) + " "
                + e.get("version")).toList()).containsExactlyElementsOf(soll);
        ist.forEach(e -> assertThat(e.keySet()).containsExactlyInAnyOrder("dokumentId", "referenz", "status",
                "fehlercode", "version", "zeit"));
    }

    @Dann("hat jeder Eintrag des Feeds nur die Felder «{}»")
    public void feedFelder(String felder) {
        List<Map<String, Object>> ist = antwort.path("eintraege");
        assertThat(ist).isNotEmpty();
        ist.forEach(e -> assertThat(e.keySet()).containsExactlyInAnyOrderElementsOf(Arrays.asList(felder.split(","))));
    }

    // ---- Hilfen

    private RequestSpecification anfrage(String name) {
        assertThat(schluessel).containsKey(name);
        return given().header("Authorization", "Bearer " + schluessel.get(name)).urlEncodingEnabled(false);
    }

    /** Titel der Einträge ohne das Präfix «webtest-»; nur Testdokumente zählen. */
    private List<String> titel() {
        List<String> titel = antwort.path(antwort.path("eintraege") != null ? "eintraege.titel" : "treffer.titel");
        return titel.stream().map(t -> t.replaceFirst("^webtest-", "")).toList();
    }

    private static List<String> erwartet(String namen) {
        return namen.isEmpty() ? List.of() : Arrays.asList(namen.split(","));
    }

    private static String wert(Map<String, String> zeile, String spalte, String standard) {
        String wert = zeile.get(spalte);
        return wert == null ? standard : wert;
    }

    private static String bezeichnerOderRoh(String wert) {
        return wert.matches("[a-z]+") && !"null".equals(wert) && !"true".equals(wert) && !"false".equals(wert)
                ? "\"" + wert + "\"" : wert;
    }

    private static String ohneKennungen(String antwort) {
        return antwort.replaceAll("\"instance\":\"[^\"]*\"", "").replaceAll("\"[a-zA-Z]*[Ii]d\":\"[^\"]*\"", "");
    }

    private long hoechsteProtokollId(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery("select coalesce(max(id), 0) from "
                     + new MandantCode(mandant).schema() + ".zugriff_protokoll")) {
            zeile.next();
            return zeile.getLong(1);
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }
}
