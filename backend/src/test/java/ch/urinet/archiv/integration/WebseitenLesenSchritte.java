package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.integration.application.webseite.DateiPlaetze;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.integration.domain.webseite.AlteUrl;
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
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern GUELTIG = Pattern.compile("[?&]X-Amz-Expires=(\\d+)");

    @Inject
    WebseitenSchluesselVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AblagePort ablage;

    @Inject
    DateiPlaetze plaetze;

    private record Dok(String mandant, UUID id) {
    }

    private final Map<String, String> schluessel = new HashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private final Map<String, Dok> dokumente = new LinkedHashMap<>();
    private final Set<String> mandantenMitReihen = new TreeSet<>();
    private long protokollStand;
    private final List<RohClient> offeneStreams = new ArrayList<>();
    private final Map<String, String> sha256 = new HashMap<>();
    private RohClient.Kopf streamKopf;
    private String streamText = "";
    private String gelesenSha256;
    private long gelesenBytes;
    private long heapZuwachs;
    private Response antwort;
    private Response vergleich;
    private boolean embeddingGesetzt;

    @After
    public void aufraeumen() throws SQLException, InterruptedException {
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
        if (embeddingGesetzt) {
            embeddingGesetzt = false;
            KiWireMock.zuruecksetzen();
        }
        dokumente.clear();
        tokenIds.clear();
        schluessel.clear();
        mandantenMitReihen.clear();
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

    /**
     * Spalten (alle ausser name optional): art, ausgabe, jahr, datum, zugriff (Standard oeffentlich),
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
        byte[] inhalt = ("PDF/A " + name).getBytes(StandardCharsets.UTF_8);
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

    @Dann("nennt die Antwort eine Adresse der Datei, die höchstens 5 Minuten gilt")
    public void adresse() {
        String url = antwort.path("url");
        Instant bis = Instant.parse(antwort.path("gueltigBis"));
        assertThat(url).startsWith("http");
        assertThat(bis).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(5));
        Matcher m = GUELTIG.matcher(url);
        if (m.find()) {
            assertThat(Integer.parseInt(m.group(1))).isLessThanOrEqualTo(300);
        }
        assertThat(antwort.<Map<String, Object>>path("$")).containsOnlyKeys("url", "gueltigBis");
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

    @Wenn("der Webseiten-Lesezugang «{word}» die Datei von «{word}» mit der Stufe «{word}» langsam liest")
    public void langsamLesen(String name, String dokument, String stufe) throws IOException, InterruptedException {
        assertThat(schluessel).containsKey(name);
        Runtime laufzeit = Runtime.getRuntime();
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
                    // der Client steht, die Hälfte ist weg: was die API jetzt noch hält, ist ihr Puffer, nicht die Datei
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
