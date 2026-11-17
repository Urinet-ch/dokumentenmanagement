package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte für Upload, Referenzen, Bearbeiter und Listen der Webseiten-Erfassung (Spec 03, Aufgabe 11.10 Teil 1). */
public class WebseitenErfassungSchritte {

    private static final String PFAD = "/public/v1/erfassung/dokumente";
    private static final String BEARBEITER = "wp:1";

    private record Zugang(String token, String tokenId, String mandant, String webseite) {
    }

    @Inject
    WebseitenSchluesselVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, Zugang> schluessel = new HashMap<>();
    private final Map<String, byte[]> dateien = new HashMap<>();
    private final Map<String, UUID> idsJeReferenz = new HashMap<>();
    private final Map<String, Set<UUID>> dokumente = new HashMap<>();
    private final Set<String> mandantenMitTestart = ConcurrentHashMap.newKeySet();
    private Response letzte;
    private UUID letzteId;
    private String letzteReferenz;
    private String letzteDatei;
    private String letzterSchluessel;
    private List<Response> paralleleAntworten = List.of();
    private UUID filterDokument;
    private long dokumenteVorher;
    private String gemerktesEtag;

    @After
    public void aufraeumen() throws SQLException {
        for (Map.Entry<String, Set<UUID>> eintrag : dokumente.entrySet()) {
            List<UUID> ids = List.copyOf(eintrag.getValue());
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement(
                         "delete from public.auftrag where typ in ('VERARBEITEN', 'KI_VORSCHLAG', 'ARCHIVIEREN', 'EMBEDDING') "
                                 + "and nutzlast->>'dokumentId' = any (string_to_array(?, ','))")) {
                befehl.setString(1, String.join(",", ids.stream().map(UUID::toString).toList()));
                befehl.executeUpdate();
            }
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), ids);
            TestAblage.loeschen(eintrag.getKey(), "eingang/",
                    key -> ids.stream().anyMatch(id -> key.contains(id.toString())));
        }
        for (String mandant : mandantenMitTestart) {
            ausfuehren("delete from " + new MandantCode(mandant).schema()
                    + ".dokumentart where code = 'WEBTEST_ERFASSUNG'");
        }
        for (Zugang zugang : schluessel.values()) {
            ausfuehren("delete from public.api_client where id = ?", zugang.tokenId());
        }
        schluessel.clear();
        dateien.clear();
        idsJeReferenz.clear();
        dokumente.clear();
        mandantenMitTestart.clear();
        letzte = null;
        letzteId = null;
        letzteReferenz = null;
        letzteDatei = null;
        letzterSchluessel = null;
        paralleleAntworten = List.of();
        filterDokument = null;
        dokumenteVorher = 0;
        gemerktesEtag = null;
    }

    @Angenommen("der Erfassungs-Schlüssel «{word}» für die Webseite «{word}» im Mandanten «{word}» ist angelegt")
    public void erfassungsSchluessel(String name, String webseitenName, String mandant) {
        Zugang zugang = anlegen(mandant, webseitenName, "erfassung");
        schluessel.put(name, zugang);
        Response antwort = mit(name).contentType("application/json").body(Map.of("name", "Testperson"))
                .when().put("/public/v1/erfassung/bearbeiter/" + BEARBEITER);
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(204);
    }

    @Angenommen("der Lese-Schlüssel «{word}» für die Webseite «{word}» im Mandanten «{word}» ist angelegt")
    public void leseSchluessel(String name, String webseitenName, String mandant) {
        schluessel.put(name, anlegen(mandant, webseitenName, "lesen"));
    }

    private Zugang anlegen(String mandant, String webseitenName, String art) {
        String webseite = webseitenName + "-" + UUID.randomUUID().toString().substring(0, 8);
        return anlegen(mandant, webseite, art, false);
    }

    @Angenommen("der Erfassungs-Schlüssel «{word}» für dieselbe Webseite wie «{word}» ist angelegt")
    public void gleicherWebseitenSchluessel(String name, String vorhandenerName) {
        Zugang vorhanden = schluessel.get(vorhandenerName);
        schluessel.put(name, anlegen(vorhanden.mandant(), vorhanden.webseite(), "erfassung", false));
    }

    private Zugang anlegen(String mandant, String webseite, String art, boolean absenderPruefen) {
        String stufe = "lesen".equals(art) ? "vorstand" : null;
        String text = verwaltung.erstellen(mandant, webseite, art, stufe, absenderPruefen).token().text();
        String id = ApiToken.lesen(text).orElseThrow().id();
        return new Zugang(text, id, mandant, webseite);
    }

    private RequestSpecification mit(String name) {
        return given().header("Authorization", "Bearer " + schluessel.get(name).token());
    }

    @Wenn("der Schlüssel «{word}» die Datei «{}» mit Referenz «{}» hochlädt")
    public void hochladen(String name, String dateiname, String referenz) {
        letzte = hochladen(name, dateiname, referenz, true, false);
        antwortMerken(name, dateiname, referenz, letzte);
    }

    @Wenn("der Schlüssel «{word}» dieselbe Datei «{}» mit Referenz «{}» erneut hochlädt")
    public void erneutHochladen(String name, String dateiname, String referenz) {
        letzte = hochladen(name, dateiname, referenz, true, false);
        antwortMerken(name, dateiname, referenz, letzte);
    }

    @Wenn("der Schlüssel «{word}» dieselbe Datei «{}» mit Referenz «{}» hochlädt")
    public void gleicheDateiAndereReferenz(String name, String dateiname, String referenz) {
        letzte = hochladen(name, dateiname, referenz, true, false);
        antwortMerken(name, dateiname, referenz, letzte);
    }

    @Wenn("der Schlüssel «{word}» eine andere Datei «{}» mit Referenz «{}» hochlädt")
    public void andereDateiHochladen(String name, String dateiname, String referenz) {
        letzte = hochladen(name, dateiname, referenz, true, false);
        antwortMerken(name, dateiname, referenz, letzte);
    }

    @Wenn("der Schlüssel «{word}» sendet zusätzlich zur Datei «{}» mit Referenz «{}» ein Multipart-Feld «dateiname»")
    public void unzulaessigesZusatzfeld(String name, String dateiname, String referenz) {
        letzte = hochladen(name, dateiname, referenz, true, true);
        antwortMerken(name, dateiname, referenz, letzte);
    }

    @Wenn("der Schlüssel «{word}» die Datei «{}» mit Referenz «{}» ohne Bearbeiterkennung hochlädt")
    public void ohneBearbeiter(String name, String dateiname, String referenz) {
        letzte = hochladen(name, dateiname, referenz, false, false);
        antwortMerken(name, dateiname, referenz, letzte);
    }

    private Response hochladen(String name, String dateiname, String referenz, boolean mitBearbeiter,
                               boolean dateinameZusatz) {
        RequestSpecification request = mit(name);
        if (mitBearbeiter) {
            request.header("X-Archiv-Bearbeiter", BEARBEITER);
        }
        request.multiPart(new MultiPartSpecBuilder(datei(dateiname)).controlName("datei")
                .fileName(dateiname).mimeType("application/pdf").build());
        request.multiPart("referenz", referenz);
        if (dateinameZusatz) {
            request.multiPart("dateiname", dateiname);
        }
        return request.when().post(PFAD);
    }

    private byte[] datei(String name) {
        return dateien.computeIfAbsent(name, n -> Testdateien.pdf());
    }

    private void antwortMerken(String name, String dateiname, String referenz, Response antwort) {
        letzterSchluessel = name;
        letzteDatei = dateiname;
        letzteReferenz = referenz;
        letzteId = null;
        String id = antwort.jsonPath().getString("id");
        if (id != null) {
            letzteId = UUID.fromString(id);
            Zugang zugang = schluessel.get(name);
            dokumente.computeIfAbsent(zugang.mandant(), k -> ConcurrentHashMap.newKeySet()).add(letzteId);
            idsJeReferenz.putIfAbsent(schluesselKey(name, referenz), letzteId);
        }
    }

    @Wenn("der Schlüssel «{word}» den Status der Referenz «{}» abruft")
    public void statusReferenz(String name, String referenz) {
        letzte = mit(name).queryParam("referenz", referenz).when().get(PFAD);
        letzterSchluessel = name;
        letzteReferenz = referenz;
    }

    @Wenn("der Schlüssel «{word}» das Dokument der Referenz «{}» abruft")
    public void detail(String name, String referenz) {
        UUID id = idsJeReferenz.getOrDefault(schluesselKey(name, referenz), letzteId);
        letzterSchluessel = name;
        letzteId = id;
        letzteReferenz = referenz;
        letzte = mit(name).header("X-Archiv-Bearbeiter", BEARBEITER).when()
                .get(PFAD + "/" + id);
    }

    @Wenn("der Schlüssel «{word}» zwei Uploads derselben Datei «{}» mit Referenz «{}» gleichzeitig sendet")
    public void gleichzeitigHochladen(String name, String dateiname, String referenz) throws Exception {
        datei(dateiname);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Response> erster = executor.submit(() -> {
                start.await();
                return hochladen(name, dateiname, referenz, true, false);
            });
            Future<Response> zweiter = executor.submit(() -> {
                start.await();
                return hochladen(name, dateiname, referenz, true, false);
            });
            start.countDown();
            Response a = erster.get(30, TimeUnit.SECONDS);
            Response b = zweiter.get(30, TimeUnit.SECONDS);
            paralleleAntworten = List.of(a, b);
            letzte = b;
            antwortMerken(name, dateiname, referenz, b);
        } finally {
            executor.shutdownNow();
        }
    }

    @Wenn("der Schlüssel «{word}» den Anzeigenamen des Bearbeiters «{}» auf «{}» ändert")
    public void bearbeiterAendern(String name, String kennung, String anzeigename) {
        letzte = mit(name).contentType("application/json").body(Map.of("name", anzeigename))
                .when().put("/public/v1/erfassung/bearbeiter/" + kennung);
    }

    @Wenn("der Schlüssel «{word}» die Liste mit allen Listenfiltern abruft")
    public void listeMitFiltern(String name) {
        letzterSchluessel = name;
        letzte = mit(name).queryParam("status", "ARCHIVIERT").queryParam("reihe", "WEBTEST_ERFASSUNG")
                .queryParam("jahr", 2026).queryParam("veroeffentlicht", true).queryParam("q", "filterprobe")
                .when().get(PFAD);
    }

    @Wenn("der Schlüssel «{word}» die Liste mit «veroeffentlicht = false» abruft")
    public void listeNichtVeroeffentlicht(String name) {
        letzterSchluessel = name;
        letzte = mit(name).queryParam("veroeffentlicht", false).when().get(PFAD);
    }

    @Wenn("der Schlüssel «{word}» den lesenden Endpunkt «{}» aufruft")
    public void lesendenEndpunkt(String name, String pfad) {
        letzterSchluessel = name;
        letzte = mit(name).when().get(pfad);
    }

    @Angenommen("im Mandanten «{word}» liegt ein synthetisches Dokument mit allen Listenfiltern")
    public void dokumentFuerListenfilter(String mandant) throws SQLException {
        mandantenMitTestart.add(mandant);
        String schema = new MandantCode(mandant).schema();
        ausfuehren("insert into " + schema + ".dokumentart "
                + "(code, name, vertraulichkeit, gesetzliche_pflicht, aktiv) "
                + "values ('WEBTEST_ERFASSUNG', 'Webtest Erfassung', 'NORMAL', false, true) "
                + "on conflict (code) do nothing");
        filterDokument = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(
                mandant, "WEBTEST_ERFASSUNG", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        ausfuehren("update " + schema + ".dokument set titel = ?, zusatz = "
                        + "'{\"jahr\": 2026, \"zugriff\": \"oeffentlich\", \"veroeffentlicht\": true}'::jsonb, "
                        + "volltext = ? where id = ?",
                "webtest-filterprobe", "filterprobe", filterDokument);
        dokumente.computeIfAbsent(mandant, k -> ConcurrentHashMap.newKeySet()).add(filterDokument);
    }

    @Dann("ist die letzte Antwort HTTP {int}")
    public void status(int status) {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(status);
    }

    @Dann("ist die letzte Antwort HTTP {int} mit «{word}»")
    public void statusMitFehlercode(int status, String code) {
        assertThat(ProblemDetails.pruefen(letzte, status, "felder")).isEqualTo(code);
    }

    @Dann("ist die letzte Antwort HTTP 429 mit «{word}», «Retry-After» zwischen {int} und {int} Sekunden und dem Grund «{word}»")
    public void statusGrenze(String code, int von, int bis, String grund) {
        assertThat(ProblemDetails.pruefen(letzte, 429, "grund")).isEqualTo(code);
        assertThat(Integer.parseInt(letzte.header("Retry-After"))).isBetween(von, bis);
        assertThat(letzte.jsonPath().getString("grund")).isEqualTo(grund);
    }

    @Dann("die Upload-Antwort enthält eine ID, den Status und ETag")
    public void uploadAntwort() {
        assertThat(letzteId).isNotNull();
        assertThat(letzte.jsonPath().getString("status")).isNotBlank();
        assertThat(letzte.header("ETag")).isNotBlank();
    }

    @Dann("antwortet der wiederholte Upload HTTP 200 mit derselben Dokument-ID")
    public void idempotenterUpload() {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getString("id"))
                .isEqualTo(idsJeReferenz.get(schluesselKey(letzterSchluessel, letzteReferenz)).toString());
    }

    @Dann("antwortet der Upload HTTP 200 mit «dublette = true» und derselben Dokument-ID")
    public void dokumentDublette() {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getBoolean("dublette")).isTrue();
        assertThat(idsJeReferenz.values()).contains(UUID.fromString(letzte.jsonPath().getString("id")));
    }

    @Dann("die letzte Antwort enthält keinen Dateinamen")
    public void keinDateiname() {
        assertThat(letzte.asString()).doesNotContain("dateiname");
        if (letzteDatei != null) {
            assertThat(letzte.asString()).doesNotContain(letzteDatei);
        }
    }

    @Dann("die Detailansicht kennzeichnet Quelle «{word}»")
    public void quelleInDetail(String quelle) {
        assertThat(letzte.jsonPath().getString("quelle")).isEqualTo(quelle);
    }

    @Dann("der Status enthält die ID des letzten Uploads und die Referenz «{}»")
    public void statusAntwort(String referenz) {
        assertThat(letzte.jsonPath().getString("id")).isEqualTo(letzteId.toString());
        assertThat(letzte.jsonPath().getString("referenz")).isEqualTo(referenz);
        assertThat(letzte.jsonPath().getString("status")).isNotBlank();
    }

    @Dann("das Protokoll enthält den Bearbeiter des Uploads")
    public void protokollBearbeiter() throws SQLException {
        Zugang zugang = schluessel.get(letzterSchluessel);
        String schema = new MandantCode(zugang.mandant()).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select bearbeiter, rolle from " + schema
                     + ".zugriff_protokoll where bearbeiter = ? and aktion = 'ERFASSEN' "
                     + "order by id desc limit 1")) {
            abfrage.setString(1, zugang.webseite() + "/" + BEARBEITER);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString("bearbeiter")).isEqualTo(zugang.webseite() + "/" + BEARBEITER);
                assertThat(zeile.getString("rolle")).isEqualTo("GESCHAEFTSSTELLE");
            }
        }
    }

    @Dann("antwortet die Bearbeitermeldung mit HTTP {int}")
    public void bearbeiterAntwort(int status) {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(status);
    }

    @Dann("das Bearbeiterverzeichnis enthält «{}» für «{word}»")
    public void bearbeiterImVerzeichnis(String name, String schluesselName) {
        Zugang zugang = schluessel.get(schluesselName);
        String kennung = zugang.webseite() + "/" + BEARBEITER;
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select name, rolle from "
                     + new MandantCode(zugang.mandant()).schema()
                     + ".benutzer_verzeichnis where benutzer = ? order by id desc limit 1")) {
            abfrage.setString(1, kennung);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString("name")).contains(name);
                assertThat(zeile.getString("rolle")).isEqualTo("GESCHAEFTSSTELLE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Bearbeiterverzeichnis nicht lesbar", e);
        }
    }

    @Dann("enthält die Liste das synthetische Dokument ohne Dateinamen")
    public void listeEnthaeltDokument() {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getList("eintraege.id", String.class))
                .contains(filterDokument.toString());
        assertThat(letzte.asString()).doesNotContain("dateiname");
    }

    @Dann("ist die Liste leer")
    public void listeLeer() {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getLong("gesamt")).isZero();
        assertThat(letzte.jsonPath().getList("eintraege")).isEmpty();
    }

    @Wenn("der Schlüssel «{word}» sendet die Datei «{}» mit Referenz «{}» und zusätzlich den Teil «{word}»")
    public void zusaetzlicherTeil(String name, String dateiname, String referenz, String teil) {
        RequestSpecification request = mit(name).header("X-Archiv-Bearbeiter", BEARBEITER);
        request.multiPart(new MultiPartSpecBuilder(datei(dateiname)).controlName("datei")
                .fileName(dateiname).mimeType("application/pdf").build());
        request.multiPart("referenz", referenz);
        switch (teil) {
            case "datei" -> request.multiPart(new MultiPartSpecBuilder(datei("zweiter-" + dateiname))
                    .controlName("datei").fileName("zweiter-" + dateiname).mimeType("application/pdf").build());
            case "referenz" -> request.multiPart("referenz", referenz + "-b");
            default -> request.multiPart(teil, "https://example.invalid/datei.pdf");
        }
        letzte = request.when().post(PFAD);
        letzterSchluessel = name;
        letzteDatei = dateiname;
        letzteReferenz = referenz;
    }

    @Wenn("der Schlüssel «{word}» die Datei «{}» mit Referenz «{}» und der Bearbeiterkennung «{}» sendet")
    public void mitBearbeiterkennung(String name, String dateiname, String referenz, String kennung) {
        RequestSpecification request = mit(name).header("X-Archiv-Bearbeiter", kennung);
        request.multiPart(new MultiPartSpecBuilder(datei(dateiname)).controlName("datei")
                .fileName(dateiname).mimeType("application/pdf").build());
        request.multiPart("referenz", referenz);
        letzte = request.when().post(PFAD);
        letzterSchluessel = name;
        letzteDatei = dateiname;
    }

    @Wenn("der Schlüssel «{word}» das Dokument der Referenz «{}» ohne Bearbeiterkennung abruft")
    public void detailOhneBearbeiter(String name, String referenz) {
        UUID id = idsJeReferenz.get(schluesselKey(name, referenz));
        letzte = mit(name).when().get(PFAD + "/" + id);
        letzterSchluessel = name;
    }

    @Wenn("das Dokument der Referenz «{}» von «{word}» wird verworfen")
    public void dokumentVerwerfen(String referenz, String name) throws SQLException {
        UUID id = idsJeReferenz.get(schluesselKey(name, referenz));
        String schema = new MandantCode(schluessel.get(name).mandant()).schema();
        ausfuehren("update " + schema + ".dokument_datei set status = 'VERWORFEN' where dokument_id = ?", id);
        ausfuehren("update " + schema + ".dokument set status = 'VERWORFEN' where id = ?", id);
    }

    @Angenommen("das Dokument der Referenz «{}» von «{word}» hat zusätzlich als Seite 2 die Datei «{}»")
    public void zweiteSeite(String referenz, String name, String dateiname) throws SQLException {
        UUID id = idsJeReferenz.get(schluesselKey(name, referenz));
        String sha = HexFormat.of().formatHex(sha256(datei(dateiname)));
        ausfuehren("insert into " + new MandantCode(schluessel.get(name).mandant()).schema()
                + ".dokument_datei (dokument_id, nr, rolle, sha256, status) values (?, 2, 'ORIGINAL', ?, 'AKTIV')",
                id, sha);
    }

    private static byte[] sha256(byte[] inhalt) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(inhalt);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Wenn("der Schlüssel «{word}» zwei Uploads mit Referenz «{}» gleichzeitig sendet, einmal die Datei «{}» und einmal die Datei «{}»")
    public void gleichzeitigVerschiedeneDateien(String name, String referenz, String datei1, String datei2)
            throws Exception {
        parallel(name, datei1, referenz, datei2, referenz);
    }

    @Wenn("der Schlüssel «{word}» zwei Uploads der Dateien «{}» und «{}» mit den Referenzen «{}» und «{}» gleichzeitig sendet")
    public void gleichzeitigVerschiedeneReferenzen(String name, String datei1, String datei2, String ref1,
                                                   String ref2) throws Exception {
        parallel(name, datei1, ref1, datei2, ref2);
    }

    private void parallel(String name, String datei1, String ref1, String datei2, String ref2) throws Exception {
        datei(datei1);
        datei(datei2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Response> erster = executor.submit(() -> {
                start.await();
                return hochladen(name, datei1, ref1, true, false);
            });
            Future<Response> zweiter = executor.submit(() -> {
                start.await();
                return hochladen(name, datei2, ref2, true, false);
            });
            start.countDown();
            Response a = erster.get(30, TimeUnit.SECONDS);
            Response b = zweiter.get(30, TimeUnit.SECONDS);
            paralleleAntworten = List.of(a, b);
            letzte = b;
            antwortMerken(name, datei1, ref1, a);
            antwortMerken(name, datei2, ref2, b);
        } finally {
            executor.shutdownNow();
        }
    }

    @Angenommen("die Zahl der Dokumente im Mandanten «{word}» wird gemerkt")
    public void dokumenteMerken(String mandant) throws SQLException {
        dokumenteVorher = dokumenteZaehlen(mandant);
    }

    private long dokumenteZaehlen(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("select count(*) from "
                     + new MandantCode(mandant).schema() + ".dokument");
             ResultSet zeile = befehl.executeQuery()) {
            zeile.next();
            return zeile.getLong(1);
        }
    }

    @Wenn("der Schlüssel «{word}» die Liste mit dem unzulässigen Filter «dateiname» abruft")
    public void listeMitDateiname(String name) {
        letzterSchluessel = name;
        letzte = mit(name).queryParam("dateiname", "x.pdf").when().get(PFAD);
    }

    @Wenn("der Schlüssel «{word}» die Liste ohne Filter abruft")
    public void listeOhneFilter(String name) {
        letzterSchluessel = name;
        letzte = mit(name).queryParam("groesse", 100).when().get(PFAD);
    }

    @Dann("hat der Mandant «{word}» die Dokumentzahl um {int} erhöht")
    public void dokumenteMehr(String mandant, int anzahl) throws SQLException {
        assertThat(dokumenteZaehlen(mandant) - dokumenteVorher).isEqualTo(anzahl);
    }

    @Dann("gibt es in der Referenztabelle für «{word}» und Referenz «{}» genau {int} Eintrag")
    public void referenzEintraege(String name, String referenz, int anzahl) throws SQLException {
        Zugang zugang = schluessel.get(name);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("select count(*) from "
                     + new MandantCode(zugang.mandant()).schema()
                     + ".webseite_referenz where webseite = ? and referenz = ?")) {
            befehl.setString(1, zugang.webseite());
            befehl.setString(2, referenz);
            try (ResultSet zeile = befehl.executeQuery()) {
                zeile.next();
                assertThat(zeile.getLong(1)).isEqualTo(anzahl);
            }
        }
    }

    @Dann("liefern die beiden Uploads genau HTTP {int} und HTTP {int}")
    public void genauZweiStatus(int erster, int zweiter) {
        assertThat(paralleleAntworten.stream().map(Response::statusCode).sorted().toList())
                .isEqualTo(List.of(erster, zweiter).stream().sorted().toList());
    }

    @Dann("gehören beide Uploads zur selben Dokument-ID")
    public void beideGleicheId() {
        List<String> ids = paralleleAntworten.stream().filter(a -> a.statusCode() < 300)
                .map(a -> a.jsonPath().getString("id")).toList();
        assertThat(ids).hasSize(paralleleAntworten.size()).doesNotContainNull().containsOnly(ids.getFirst());
    }

    @Dann("haben die beiden Uploads verschiedene Dokument-IDs")
    public void beideVerschiedeneIds() {
        assertThat(paralleleAntworten.stream().map(a -> a.jsonPath().getString("id")).distinct().toList())
                .hasSize(2).doesNotContainNull();
    }

    @Dann("enthält die letzte Antwort keine internen Details")
    public void keineInternenDetails() {
        assertThat(letzte.asString()).doesNotContain("Exception", "at ch.urinet", "org.postgresql", "select ",
                "webseite_referenz");
    }

    @Dann("sind die Dokument-IDs der Referenz «{}» von «{word}» und «{word}» verschieden")
    public void referenzenVerschieden(String referenz, String a, String b) {
        assertThat(idsJeReferenz.get(schluesselKey(a, referenz)))
                .isNotNull().isNotEqualTo(idsJeReferenz.get(schluesselKey(b, referenz)));
    }

    @Dann("hat die Referenz «{}» von «{word}» ein neues Dokument gegenüber «{}»")
    public void neuesDokument(String referenz, String name, String frueher) {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(202);
        assertThat(letzte.jsonPath().getString("id")).isNotNull()
                .isNotEqualTo(idsJeReferenz.get(schluesselKey(name, frueher)).toString());
    }

    @Dann("weist die letzte Liste für das Dokument der Referenz «{}» von «{word}» die Referenz aus")
    public void listeMitReferenz(String referenz, String name) {
        UUID id = idsJeReferenz.get(schluesselKey(name, referenz));
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        List<Map<String, Object>> eintraege = letzte.jsonPath().getList("eintraege");
        assertThat(eintraege).filteredOn(e -> id.toString().equals(e.get("id")))
                .singleElement().satisfies(e -> assertThat(e.get("referenz")).isEqualTo(referenz));
    }

    @Dann("weist kein Eintrag der letzten Liste die Referenz «{}» aus")
    public void listeOhneFremdeReferenz(String referenz) {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getList("eintraege.referenz", String.class)).doesNotContain(referenz);
    }

    private String schluesselKey(String name, String referenz) {
        return name + ":" + schluessel.get(name).webseite() + ":" + referenz;
    }

    @Angenommen("für den Schlüssel «{word}» liegt ein Pflegedokument im Status «{word}» vor")
    public void pflegedokument(String name, String status) throws SQLException {
        Zugang zugang = schluessel.get(name);
        hochladen(name, "pflege.pdf", "pflege");
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(202);
        String schema = new MandantCode(zugang.mandant()).schema();
        mandantenMitTestart.add(zugang.mandant());
        ausfuehren("insert into " + schema + ".dokumentart "
                        + "(code, name, gruppen, pflichtfelder, zusatz_schema, vertraulichkeit, gesetzliche_pflicht, aktiv) "
                        + "values ('WEBTEST_ERFASSUNG', 'Webtest Erfassung', '{frei}', "
                        + "'{titel,zusatz.zugriff}', ?::jsonb, 'NORMAL', false, true) "
                        + "on conflict (code) do nothing",
                """
                {"type":"object","properties":{"zugriff":{"type":"string","enum":["oeffentlich","mitglieder","vorstand"]},
                "veroeffentlicht":{"type":"boolean"},"jahr":{"type":"integer"}},
                "required":["zugriff","veroeffentlicht"],"additionalProperties":false}
                """);
        boolean bestaetigt = status.startsWith("ARCHIV");
        ausfuehren("update " + schema + ".dokument set art = 'WEBTEST_ERFASSUNG', titel = 'Pflegeprobe', "
                        + "status = ?, bestaetigt = " + (bestaetigt ? "now()" : "null")
                        + ", geschaeftsjahr = 2026, zusatz = "
                        + "'{\"zugriff\":\"mitglieder\",\"veroeffentlicht\":false,\"jahr\":2026}'::jsonb where id = ?",
                status, letzteId);
        gemerktesEtag = aktuellesEtag(name);
    }

    private String aktuellesEtag(String name) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement p = c.prepareStatement("select version from "
                     + new MandantCode(schluessel.get(name).mandant()).schema() + ".dokument where id = ?")) {
            p.setObject(1, letzteId);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                return "\"" + r.getInt(1) + "\"";
            }
        }
    }

    @Wenn("der Schlüssel «{word}» das Pflegedokument mit «{word}» und ETag «{}» bearbeitet:")
    public void pflegen(String name, String aktion, String etag, String body) throws SQLException {
        pflegeAnfrage(name, aktion, etag, body, BEARBEITER);
    }

    @Wenn("der Schlüssel «{word}» am Pflegedokument «{word}» mit ETag «{}» ausführt")
    public void pflegenOhneBody(String name, String aktion, String etag) throws SQLException {
        pflegeAnfrage(name, aktion, etag, null, BEARBEITER);
    }

    @Wenn("der Schlüssel «{word}» am Pflegedokument «{word}» ohne gemeldeten Bearbeiter ausführt")
    public void pflegenOhneBearbeiter(String name, String aktion) throws SQLException {
        pflegeAnfrage(name, aktion, "aktuell", "{}", null);
    }

    private void pflegeAnfrage(String name, String aktion, String etag, String body, String bearbeiter)
            throws SQLException {
        RequestSpecification request = mit(name);
        if (bearbeiter != null) {
            request.header("X-Archiv-Bearbeiter", bearbeiter);
        }
        if (!etag.equals("fehlt")) {
            request.header("If-Match", switch (etag) {
                case "aktuell" -> aktuellesEtag(name);
                case "gemerkt" -> gemerktesEtag;
                default -> etag;
            });
        }
        if (body != null || aktion.equals("bestaetigen") || aktion.equals("PATCH")) {
            request.contentType("application/json").body(body == null ? "{}" : body);
        }
        String pfad = PFAD + "/" + letzteId;
        letzte = aktion.equals("PATCH") ? request.when().patch(pfad)
                : request.when().post(pfad + "/" + aktion);
        letzterSchluessel = name;
    }

    @Wenn("die Web-App das Pflegedokument des Schlüssels «{word}» ändert")
    public void webAppAendert(String name) throws SQLException {
        String mandant = schluessel.get(name).mandant();
        Response antwort = given().auth().oauth2(ch.urinet.archiv.gemeinsam.Testanmeldung.token("inhaber-" + mandant))
                .header("If-Match", aktuellesEtag(name)).contentType("application/json")
                .body("{\"titel\":\"Web-App Änderung\",\"grund\":\"Konfliktprobe\"}")
                .when().patch("/api/v1/dokumente/" + letzteId);
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
    }

    @Dann("ist das Pflegedokument «{word}» und trägt eine neue Version")
    public void pflegeStatus(String status) {
        assertThat(letzte.jsonPath().getString("status")).isEqualTo(status);
        assertThat(letzte.header("ETag")).isNotEqualTo(gemerktesEtag);
        assertThat(letzte.asString()).doesNotContain("dateiname", "pflege.pdf");
    }

    @Dann("ist das Pflegedokument des Schlüssels «{word}» nach der Ablehnung unverändert")
    public void pflegeUnveraendert(String name) throws SQLException {
        assertThat(aktuellesEtag(name)).isEqualTo(gemerktesEtag);
        Zugang z = schluessel.get(name);
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement p = c.prepareStatement("select titel, zusatz->>'zugriff' from "
                     + new MandantCode(z.mandant()).schema() + ".dokument where id = ?")) {
            p.setObject(1, letzteId);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("Pflegeprobe");
                assertThat(r.getString(2)).isEqualTo("mitglieder");
            }
        }
    }

    @Angenommen("das Pflegedokument des Schlüssels «{word}» hat Finanz- und Vertragsmetadaten")
    public void finanzUndVertrag(String name) throws SQLException {
        String schema = new MandantCode(schluessel.get(name).mandant()).schema();
        ausfuehren("insert into " + schema + ".dokument_finanz (dokument_id, betrag, waehrung) values (?, 12.35, 'CHF')",
                letzteId);
        ausfuehren("insert into " + schema + ".dokument_vertrag (dokument_id, gueltig_ab) values (?, '2026-01-01')",
                letzteId);
    }

    @Dann("liefert die Detailansicht die Finanz- und Vertragsmetadaten ohne Dateinamen")
    public void finanzUndVertragLesen() {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getString("finanz.betrag")).isEqualTo("12.35");
        assertThat(letzte.jsonPath().getString("finanz.waehrung")).isEqualTo("CHF");
        assertThat(letzte.jsonPath().getString("vertrag.gueltigAb")).isEqualTo("2026-01-01");
        assertThat(letzte.asString()).doesNotContain("dateiname", "pflege.pdf");
    }

    @Dann("ist die Veröffentlichung des Pflegedokuments {word} und der Grund «{}» mit Bearbeiter gesichert")
    public void veroeffentlichungGesichert(String flag, String grund) throws SQLException {
        assertThat(letzte.jsonPath().getBoolean("veroeffentlicht")).isEqualTo(Boolean.parseBoolean(flag));
        Zugang zugang = schluessel.get(letzterSchluessel);
        String schema = new MandantCode(zugang.mandant()).schema();
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement p = c.prepareStatement("select geaendert_von, grund, bearbeiter from " + schema
                     + ".dokument_version where dokument_id = ? order by id desc limit 1")) {
            p.setObject(1, letzteId);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("token:" + zugang.tokenId());
                assertThat(r.getString(2)).isEqualTo(grund);
                assertThat(r.getString(3)).isEqualTo(zugang.webseite() + "/" + BEARBEITER);
            }
        }
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement p = c.prepareStatement("select bearbeiter from " + schema
                     + ".zugriff_protokoll where dokument_id = ? and aktion = 'AENDERN' order by id desc limit 1")) {
            p.setObject(1, letzteId);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo(zugang.webseite() + "/" + BEARBEITER);
            }
        }
    }

    @Wenn("der Schlüssel «{word}» die Prüfdatei des Pflegedokuments abruft")
    public void pruefdatei(String name) {
        letzte = mit(name).header("X-Archiv-Bearbeiter", BEARBEITER).when().get(PFAD + "/" + letzteId + "/datei");
        letzterSchluessel = name;
    }

    @Wenn("der Schlüssel «{word}» dem Pflegedokument {int} MB JSON als Änderung sendet")
    public void zuGrosseAenderung(String name, int mb) {
        // mit «Expect: 100-continue» liest der Client die frühe 413, statt den Rest in eine geschlossene Verbindung zu schreiben
        letzte = mit(name).header("X-Archiv-Bearbeiter", BEARBEITER)
                .config(io.restassured.RestAssured.config().httpClient(
                        io.restassured.config.HttpClientConfig.httpClientConfig()
                                .setParam("http.protocol.expect-continue", true)))
                .contentType("application/json").body(new byte[mb * 1024 * 1024]).when()
                .patch(PFAD + "/" + letzteId);
        letzterSchluessel = name;
    }

    @Wenn("der Schlüssel «{word}» die Prüfdatei ohne Bearbeiter abruft")
    public void pruefdateiOhneBearbeiter(String name) {
        letzte = mit(name).when().get(PFAD + "/" + letzteId + "/datei");
    }

    @Wenn("der Schlüssel «{word}» die Prüfdatei mit HEAD abruft")
    public void pruefdateiHead(String name) {
        letzte = mit(name).header("X-Archiv-Bearbeiter", BEARBEITER).when().head(PFAD + "/" + letzteId + "/datei");
    }

    @Dann("entspricht der Prüfstream dem Original ohne Adresse oder Dateinamen und ist protokolliert")
    public void pruefstream() throws SQLException {
        assertThat(letzte.statusCode()).isEqualTo(200);
        assertThat(letzte.asByteArray()).isEqualTo(dateien.get("pflege.pdf"));
        assertThat(letzte.header("Content-Type")).startsWith("application/pdf");
        assertThat(letzte.header("Content-Length")).isEqualTo(Integer.toString(dateien.get("pflege.pdf").length));
        assertThat(letzte.header("Content-Disposition")).isEqualTo("inline");
        assertThat(letzte.header("Cache-Control")).contains("no-store");
        assertThat(letzte.header("Location")).isNull();
        Zugang z = schluessel.get(letzterSchluessel);
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement p = c.prepareStatement("select bearbeiter from " + new MandantCode(z.mandant()).schema()
                     + ".zugriff_protokoll where dokument_id = ? and aktion = 'HERUNTERLADEN'")) {
            p.setObject(1, letzteId);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo(z.webseite() + "/" + BEARBEITER);
            }
        }
    }

    @Wenn("der Schlüssel «{word}» die Hilfsliste «{word}» abruft")
    public void hilfsliste(String name, String liste) {
        letzte = mit(name).when().get("/public/v1/erfassung/" + liste);
        letzterSchluessel = name;
    }

    @Dann("enthält der Erfassungs-Eingangskorb das Pflegedokument ohne Dateinamen")
    public void pflegeEingangskorb() {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        assertThat(letzte.jsonPath().getList("eintraege.id", String.class)).contains(letzteId.toString());
        assertThat(letzte.asString()).doesNotContain("dateiname", "pflege.pdf");
    }

    @Dann("enthält die Artenliste die Formularfelder der Pflegeart")
    public void artenliste() throws IOException {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(200);
        var arten = new com.fasterxml.jackson.databind.ObjectMapper().readTree(letzte.asString());
        var art = java.util.stream.StreamSupport.stream(arten.spliterator(), false)
                .filter(a -> a.path("code").asText().equals("WEBTEST_ERFASSUNG")).findFirst().orElseThrow();
        assertThat(art.path("zusatzSchema").path("properties").path("zugriff").path("type").asText())
                .isEqualTo("string");
    }

    @Wenn("der Schlüssel «{word}» versucht, das Pflegedokument zu löschen oder zu verwerfen")
    public void keinLoeschen(String name) {
        for (String methode : List.of("DELETE", "POST")) {
            Response antwort = mit(name).header("X-Archiv-Bearbeiter", BEARBEITER).when()
                    .request(methode, PFAD + "/" + letzteId + (methode.equals("POST") ? "/verwerfen" : ""));
            assertThat(antwort.statusCode()).as(antwort.asString()).isIn(404, 405);
        }

    }

    @Dann("erreicht der Lese-Schlüssel «{word}» keinen Erfassungs-Endpunkt des Inventars")
    public void leseSchluesselOhneErfassung(String name) {
        int geprueft = 0;
        for (var endpunkt : WebseitenEndpunkte.alle()) {
            if (endpunkt.art() != ch.urinet.archiv.sicherheit.application.WebseitenZugang.Art.ERFASSUNG) {
                continue;
            }
            String pfad = endpunkt.pfad().replace("{id}", letzteId.toString())
                    .replace("{referenz}", "pflege").replace("{kennung}", "wp:1");
            RequestSpecification request = mit(name).header("If-Match", "\"0\"")
                    .header("X-Archiv-Bearbeiter", BEARBEITER);
            if (endpunkt.medientyp().equals("multipart/form-data")) {
                request.multiPart("datei", "probe.pdf", Testdateien.pdf(), "application/pdf")
                        .multiPart("referenz", "nicht-angenommen");
            } else if (java.util.Set.of("POST", "PATCH", "PUT").contains(endpunkt.verb())) {
                request.contentType(endpunkt.medientyp()).body("{\"name\":\"Prüfung\"}");
            }
            Response antwort = request.when().request(endpunkt.verb(), pfad);
            assertThat(antwort.statusCode()).as(endpunkt.verb() + " " + pfad + ": " + antwort.asString())
                    .isEqualTo(403);
            geprueft++;
        }
        assertThat(geprueft).isGreaterThanOrEqualTo(13);
    }

    @Dann("zeigt eine Gegenprobe ohne Referenzsperre beim Schlüssel «{word}» den Datenbankkonflikt")
    public void ohneReferenzsperre(String name) throws Exception {
        hochladen(name, "gegenprobe-a.pdf", "gegenprobe-a");
        status(202);
        UUID a = letzteId;
        hochladen(name, "gegenprobe-b.pdf", "gegenprobe-b");
        status(202);
        UUID b = letzteId;
        CyclicBarrier gelesen = new CyclicBarrier(2);
        Zugang z = schluessel.get(name);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> erster = pool.submit(() -> ohneSperreEintragen(z, a, "a".repeat(64), gelesen));
            Future<String> zweiter = pool.submit(() -> ohneSperreEintragen(z, b, "b".repeat(64), gelesen));
            assertThat(List.of(erster.get(20, TimeUnit.SECONDS), zweiter.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("angenommen", "23505");
        } finally {
            pool.shutdownNow();
        }
    }

    private String ohneSperreEintragen(Zugang z, UUID id, String hash, CyclicBarrier gelesen) throws Exception {
        String tabelle = new MandantCode(z.mandant()).schema() + ".webseite_referenz";
        try (Connection c = datenbank.get().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("select dokument_id from " + tabelle
                        + " where webseite = ? and referenz = 'gegenprobe-ohne-lock'")) {
                    p.setString(1, z.webseite());
                    try (ResultSet r = p.executeQuery()) {
                        assertThat(r.next()).isFalse();
                    }
                }
                gelesen.await(10, TimeUnit.SECONDS);
                try (PreparedStatement p = c.prepareStatement("insert into " + tabelle
                        + " (webseite, referenz, dokument_id, sha256) values (?, 'gegenprobe-ohne-lock', ?, ?)")) {
                    p.setQueryTimeout(10);
                    p.setString(1, z.webseite());
                    p.setObject(2, id);
                    p.setString(3, hash);
                    p.executeUpdate();
                }
                c.commit();
                return "angenommen";
            } catch (SQLException e) {
                c.rollback();
                if (!"23505".equals(e.getSQLState())) {
                    throw e;
                }
                return e.getSQLState();
            }
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
