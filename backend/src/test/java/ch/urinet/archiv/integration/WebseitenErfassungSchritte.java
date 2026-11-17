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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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

    @After
    public void aufraeumen() throws SQLException {
        for (Map.Entry<String, Set<UUID>> eintrag : dokumente.entrySet()) {
            List<UUID> ids = List.copyOf(eintrag.getValue());
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement(
                         "delete from public.auftrag where typ in ('VERARBEITEN', 'KI_VORSCHLAG') "
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
        assertThat(ProblemDetails.pruefen(letzte, status, "fehlercode")).isEqualTo(code);
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

    @Dann("liefern beide Uploads HTTP 200 oder 202 und dieselbe Dokument-ID")
    public void beideUploadsGleich() {
        assertThat(paralleleAntworten).hasSize(2).allSatisfy(a -> assertThat(a.statusCode()).isIn(200, 202));
        assertThat(paralleleAntworten.getFirst().jsonPath().getString("id"))
                .isNotNull().isEqualTo(paralleleAntworten.getLast().jsonPath().getString("id"));
    }

    private String schluesselKey(String name, String referenz) {
        return name + ":" + schluessel.get(name).webseite() + ":" + referenz;
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
