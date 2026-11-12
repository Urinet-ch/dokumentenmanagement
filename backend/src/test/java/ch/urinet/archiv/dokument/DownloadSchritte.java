package ch.urinet.archiv.dokument;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Aufgabe 4.5, Req 14.6: Download über eine vorsignierte Adresse für die gespeicherte Versions-ID. Die
 * Dateien legt der Ablage-Port synthetisch im Eingang des Mandanten-Buckets (MinIO) ab; die Schlüssel stehen wie nach
 * Erfassung und Archivierung in {@code dokument_datei} bzw. {@code dokument.s3_pdfa}.
 */
public class DownloadSchritte {

    private static final int JAHR = 2026;
    private static final Pattern GUELTIG = Pattern.compile("[?&]X-Amz-Expires=(\\d+)");

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AblagePort ablage;

    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<String, String> pdfaSchluessel = new HashMap<>();
    private final Map<String, Long> protokollStand = new HashMap<>();
    private Response antwort;

    @After
    public void aufraeumen() throws SQLException {
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
            for (UUID id : eintrag.getValue()) {
                TestAblage.loeschen(eintrag.getKey(), "eingang/" + JAHR + "/" + id + "/", k -> true);
            }
        }
        dokumente.clear();
        pdfaSchluessel.clear();
    }

    @Angenommen("im Mandanten «{word}» liegen diese Dokumente mit Dateien:")
    public void anlegen(String mandant, DataTable tabelle) throws SQLException {
        protokollStand.put(mandant, protokollStand(mandant));
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String person = zeile.get("person");
            String name = zeile.get("name");
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, null,
                    zeile.get("vertraulichkeit"), "ja".equals(zeile.get("bestaetigt")),
                    Testanmeldung.sub(zeile.get("erfasser")), person == null ? null : Testanmeldung.sub(person),
                    zeile.get("status")));
            dokumente.put(name, new Ort(mandant, id));
            for (String variante : zeile.get("dateien").split(",")) {
                datei(mandant, id, name, variante.trim());
            }
        }
    }

    private void datei(String mandant, UUID id, String name, String variante) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        switch (variante) {
            case "pdfa" -> {
                AbgelegteDatei d = ablegen(mandant, DateiSchluessel.pdfa(JAHR, id), "PDF/A " + name);
                pdfaSchluessel.put(name, d.schluessel());
                ausfuehren("update " + schema + ".dokument set s3_pdfa = ?, s3_version_pdfa = ?, sha256_pdfa = ? "
                        + "where id = ?", d.schluessel(), d.versionId(), d.sha256(), id);
            }
            case "original" -> zeile(schema, id, "ORIGINAL",
                    ablegen(mandant, DateiSchluessel.original(JAHR, id, 1, "pdf"), "Original " + name));
            case "arbeitsfassung" -> zeile(schema, id, "ARBEITSFASSUNG",
                    ablegen(mandant, DateiSchluessel.arbeitsfassung(JAHR, id, 1), "Arbeitsfassung " + name));
            default -> throw new IllegalArgumentException("Unbekannte Variante: " + variante);
        }
    }

    private void zeile(String schema, UUID id, String rolle, AbgelegteDatei d) throws SQLException {
        ausfuehren("insert into " + schema + ".dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, "
                + "s3_version, mime, groesse) values (?, 1, ?, ?, ?, ?, 'application/pdf', ?)", id, rolle, d.sha256(),
                d.schluessel(), d.versionId(), d.groesse());
    }

    private AbgelegteDatei ablegen(String mandant, DateiSchluessel schluessel, String text) {
        byte[] inhalt = text.getBytes(StandardCharsets.UTF_8);
        return ablage.ablegenEingang(new MandantCode(mandant), schluessel, new ByteArrayInputStream(inhalt),
                inhalt.length);
    }

    @Angenommen("unter dem Schlüssel des PDF\\/A von «{word}» liegt inzwischen eine neuere Version")
    public void neuereVersion(String name) {
        TestAblage.neueVersion(dokumente.get(name).mandant(), pdfaSchluessel.get(name), "neuere Fassung");
    }

    @Wenn("«{word}» die Datei von «{word}» öffnet")
    public void oeffnen(String benutzer, String name) {
        antwort = herunterladen(benutzer, name, null);
    }

    @Wenn("«{word}» die Datei von «{word}» mit «{}» öffnet")
    public void oeffnenMit(String benutzer, String name, String anfrage) {
        antwort = herunterladen(benutzer, name, anfrage);
    }

    @Wenn("«{word}» die Download-Adresse von «{word}» als JSON anfragt")
    public void alsJson(String benutzer, String name) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).redirects().follow(false)
                .accept("application/json").when().get("/api/v1/dokumente/" + dokumente.get(name).id() + "/datei");
    }

    @Dann("liefert das Archiv die höchstens 5 Minuten gültige Adresse als JSON, die nicht zwischengespeichert wird")
    public void json() {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.header("Location")).isNull();
        assertThat(antwort.header("Cache-Control")).contains("no-store");
        String adresse = antwort.jsonPath().getString("url");
        assertThat(adresse).contains("versionId=");
        Matcher gueltig = GUELTIG.matcher(adresse);
        assertThat(gueltig.find()).as("X-Amz-Expires in der Adresse").isTrue();
        assertThat(Integer.parseInt(gueltig.group(1))).isPositive().isLessThanOrEqualTo(300);
        Instant bis = Instant.parse(antwort.jsonPath().getString("gueltigBis"));
        assertThat(bis).isAfter(Instant.now()).isBeforeOrEqualTo(Instant.now().plusSeconds(300));
    }

    @Dann("antwortet das Archiv mit {int} «{word}» ohne Adresse")
    public void fehlerOhneAdresse(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
        assertThat(antwort.header("Location")).isNull();
        assertThat(antwort.asString()).doesNotContain("X-Amz-");
    }

    @Dann("leitet das Archiv auf eine höchstens 5 Minuten gültige Adresse um, die nicht zwischengespeichert wird")
    public void umleitung() {
        assertThat(antwort.statusCode()).isEqualTo(302);
        assertThat(antwort.header("Cache-Control")).contains("no-store");
        String adresse = antwort.header("Location");
        assertThat(adresse).contains("versionId=");
        Matcher gueltig = GUELTIG.matcher(adresse);
        assertThat(gueltig.find()).as("X-Amz-Expires in der Adresse").isTrue();
        assertThat(Integer.parseInt(gueltig.group(1))).isPositive().isLessThanOrEqualTo(300);
    }

    @Und("die Adresse liefert «{}»")
    public void inhalt(String erwartet) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            String adresse = antwort.statusCode() == 200 ? antwort.jsonPath().getString("url")
                    : antwort.header("Location");
            var abruf = http.send(HttpRequest.newBuilder(URI.create(adresse)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(abruf.statusCode()).isEqualTo(200);
            assertThat(abruf.body()).isEqualTo(erwartet);
        }
    }

    @Und("das Zugriffsprotokoll von «{word}» enthält HERUNTERLADEN von «{word}» für «{word}»")
    public void protokoll(String mandant, String benutzer, String name) throws SQLException {
        assertThat(heruntergeladen(mandant, benutzer, name)).isEqualTo(1);
    }

    @Und("das Zugriffsprotokoll von «{word}» enthält kein HERUNTERLADEN von «{word}» für «{word}»")
    public void keinProtokoll(String mandant, String benutzer, String name) throws SQLException {
        assertThat(heruntergeladen(mandant, benutzer, name)).isZero();
    }

    private long heruntergeladen(String mandant, String benutzer, String name) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from " + new MandantCode(mandant).schema()
                        + ".zugriff_protokoll where id > ? and aktion = 'HERUNTERLADEN' and benutzer = ? "
                        + "and dokument_id = ?")) {
            abfrage.setLong(1, protokollStand.get(mandant));
            abfrage.setString(2, Testanmeldung.sub(benutzer));
            abfrage.setObject(3, dokumente.get(name).id());
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    @Dann("antwortet der Download so:")
    public void matrix(DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Response r = herunterladen(zeile.get("benutzer"), zeile.get("dokument"), zeile.get("anfrage"));
            String fall = zeile.get("benutzer") + " → " + zeile.get("dokument") + " " + zeile.get("anfrage") + " ("
                    + zeile.get("grund") + ")";
            int status = Integer.parseInt(zeile.get("status"));
            if (status == 302) {
                assertThat(r.statusCode()).as(fall).isEqualTo(302);
                assertThat(r.header("Location")).as(fall).contains("versionId=");
            } else {
                assertThat(ProblemDetails.pruefen(r, status)).as(fall).isEqualTo(zeile.get("fehlercode"));
                assertThat(r.header("Location")).as(fall).isNull();
            }
        }
    }

    private Response herunterladen(String benutzer, String name, String anfrage) {
        Ort ort = dokumente.get(name);
        UUID id = ort == null ? Testdokumente.NAMEN.get(name) : ort.id();
        RequestSpecification spez = given().auth().oauth2(Testanmeldung.token(benutzer)).redirects().follow(false);
        if (anfrage != null) {
            for (String paar : anfrage.split("&")) {
                String[] teile = paar.split("=", 2);
                spez.queryParam(teile[0], teile[1]);
            }
        }
        return spez.when().get("/api/v1/dokumente/" + id + "/datei");
    }

    private long protokollStand(String mandant) throws SQLException {
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
