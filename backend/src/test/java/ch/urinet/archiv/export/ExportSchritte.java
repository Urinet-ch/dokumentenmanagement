package ch.urinet.archiv.export;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.export.domain.ExportDateiname;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.model.LifecycleRule;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
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
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 02, Aufgabe 6.5 (Req 7.1–7.6): synthetische Belege mit Dateien unter {@code archiv/} in MinIO, wie
 * nach der Archivierung (Schlüssel und Versionen in {@code dokument} bzw. {@code dokument_datei}), dazu synthetische
 * Tages-Manifeste unter {@code manifest/}. Der Auftrag {@code EXPORT_JAHR} läuft über den echten Worker
 * ({@link AuftragTestHilfe#abarbeiten()}).
 */
public class ExportSchritte {

    private static final Pattern GUELTIG = Pattern.compile("[?&]X-Amz-Expires=(\\d+)");
    private static final Pattern PLATZHALTER = Pattern.compile("\\{(sha )?([A-Z_]+)}");

    private record Beleg(String mandant, UUID id, int jahr, String schluessel, String sha256) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragTestHilfe auftraege;

    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    private final Map<String, Beleg> belege = new LinkedHashMap<>();
    private final List<String[]> manifeste = new ArrayList<>();
    private final Map<String, Long> protokollStand = new HashMap<>();
    private Response antwort;
    private UUID exportId;
    private Map<String, byte[]> zip;

    @After("@spec-02")
    public void aufraeumen() throws SQLException {
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        for (Beleg b : belege.values()) {
            jeMandant.computeIfAbsent(b.mandant(), m -> new ArrayList<>()).add(b.id());
            TestAblage.loeschen(b.mandant(), "archiv/" + b.jahr() + "/" + b.id() + "/", k -> true);
        }
        for (Map.Entry<String, List<UUID>> e : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), e.getKey(), e.getValue());
        }
        for (String[] m : manifeste) {
            manifestLoeschen(m[0], LocalDate.parse(m[1]));
            TestAblage.loeschen(m[0], "manifest/" + m[1] + ".", k -> true);
        }
        for (String mandant : List.of("urinet", "vhf-gsk")) {
            if (vorhanden(mandant)) {
                ausfuehren("delete from " + schema(mandant) + ".export");
                TestAblage.loeschen(mandant, "exporte/", k -> true);
            }
        }
        ausfuehren("delete from public.auftrag where typ = 'EXPORT_JAHR'");
        belege.clear();
        manifeste.clear();
        protokollStand.clear();
        exportId = null;
        zip = null;
    }

    @Angenommen("im Mandanten «{word}» gibt es keinen offenen Export")
    public void keinOffenerExport(String mandant) throws SQLException {
        ausfuehren("delete from " + schema(mandant) + ".export");
        ausfuehren("delete from public.auftrag where typ = 'EXPORT_JAHR'");
    }

    @Angenommen("im Mandanten «{word}» liegen diese Belege:")
    public void belegeAnlegen(String mandant, DataTable tabelle) throws SQLException {
        String s = schema(mandant);
        for (Map<String, String> z : tabelle.asMaps()) {
            String name = z.get("name");
            int jahr = Integer.parseInt(z.get("jahr"));
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, z.get("art"),
                    z.get("vertraulichkeit"), true, Testanmeldung.sub("inhaber-urinet"), null, z.get("status")));
            boolean ohnePdfa = "ja".equals(z.get("pdfa_fehlt"));
            String schluessel = "archiv/" + jahr + "/" + id + "/" + (ohnePdfa ? "1-original.pdf" : "pdfa.pdf");
            byte[] inhalt = ((ohnePdfa ? "Original " : "PDF/A ") + name).getBytes(StandardCharsets.UTF_8);
            String version = TestAblage.ablegen(mandant, schluessel, inhalt);
            String sha = Pruefsumme.von(inhalt);
            boolean archiviert = "ARCHIVIERT".equals(z.get("status"));
            ausfuehren("update " + s + ".dokument set datum = ?, geschaeftsjahr = ?, partner_name = ?, pdfa_fehlt = ?, "
                            + "archiviert = ?, s3_pdfa = ?, s3_version_pdfa = ?, sha256_pdfa = ? where id = ?",
                    LocalDate.parse(z.get("datum")), jahr, z.get("partner"), ohnePdfa,
                    archiviert ? OffsetDateTime.now() : null, ohnePdfa ? null : schluessel,
                    ohnePdfa ? null : version, ohnePdfa ? null : sha, id);
            if (ohnePdfa) {
                ausfuehren("insert into " + s + ".dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, "
                        + "s3_version, mime, groesse) values (?, 1, 'ORIGINAL', ?, ?, ?, 'application/pdf', ?)",
                        id, sha, schluessel, version, (long) inhalt.length);
            }
            ausfuehren("insert into " + s + ".dokument_finanz (dokument_id, belegnummer, richtung, betrag) "
                    + "values (?, ?, ?, ?)", id, z.get("belegnummer"), z.get("richtung"), new BigDecimal(z.get("betrag")));
            belege.put(name, new Beleg(mandant, id, jahr, schluessel, sha));
        }
    }

    @Angenommen("im Mandanten «{word}» liegen die Manifeste vom {word} und vom {word} mit Zeitstempel «{word}»")
    public void manifesteAnlegen(String mandant, String erstes, String zweites, String dienst) throws SQLException {
        for (String tag : List.of(erstes, zweites)) {
            manifestAnlegen(mandant, tag, dienst);
        }
    }

    @Angenommen("im Mandanten «{word}» liegt das Manifest vom {word} mit Zeitstempel «{word}»")
    public void manifestAnlegen(String mandant, String tag, String dienst) throws SQLException {
        {
            LocalDate datum = LocalDate.parse(tag);
            manifestLoeschen(mandant, datum);
            manifeste.add(new String[]{mandant, tag});
            Map<String, String[]> teile = new LinkedHashMap<>();
            for (String endung : List.of("json", "sig", dienst + ".tsr", dienst + ".kette.pem")) {
                byte[] inhalt = ("Manifest " + tag + " " + endung).getBytes(StandardCharsets.UTF_8);
                String key = "manifest/" + tag + "." + endung;
                teile.put(endung, new String[]{key, TestAblage.ablegen(mandant, key, inhalt), Pruefsumme.von(inhalt)});
            }
            String s = schema(mandant);
            ausfuehren("insert into " + s + ".manifest (datum, von, bis, sha256, vorher_sha256, anzahl, protokolle, "
                            + "s3_schluessel, s3_version, sig_sha256, sig_s3_version) values (?, null, ?, ?, null, 0, "
                            + "'{}'::jsonb, ?, ?, ?, ?)", datum, OffsetDateTime.now(), teile.get("json")[2],
                    teile.get("json")[0], teile.get("json")[1], teile.get("sig")[2], teile.get("sig")[1]);
            String[] tsr = teile.get(dienst + ".tsr");
            String[] kette = teile.get(dienst + ".kette.pem");
            ausfuehren("insert into " + s + ".manifest_zeitstempel (datum, dienst, gen_time, tsr_sha256, s3_schluessel, "
                            + "s3_version, pruefdaten) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))", datum, dienst,
                    OffsetDateTime.now(), tsr[2], tsr[0], tsr[1], "[{\"art\":\"kette\",\"sha256\":\"" + kette[2]
                            + "\",\"s3_version\":\"" + kette[1] + "\"}]");
        }
    }

    @Angenommen("im Mandanten «{word}» wurde «{word}» am {word} archiviert")
    public void archiviertAm(String mandant, String name, String tag) throws SQLException {
        ausfuehren("update " + schema(mandant) + ".dokument set archiviert = ? where id = ?",
                OffsetDateTime.parse(tag + "T10:00:00+01:00"), belege.get(name).id());
    }

    @Wenn("«{word}» den Jahresexport mit dem Body «{}» anfordert")
    public void anfordernRoh(String benutzer, String body) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType("application/json")
                .body(body).when().post("/api/v1/exporte");
    }

    @Wenn("«{word}» den Export «{word}» abruft")
    public void abrufenRoh(String benutzer, String id) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/exporte/" + id);
    }

    @Wenn("«{word}» den Jahresexport {int} anfordert")
    public void anfordern(String benutzer, int jahr) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType("application/json")
                .body(Map.of("geschaeftsjahr", jahr)).when().post("/api/v1/exporte");
        if (antwort.statusCode() == 202) {
            exportId = UUID.fromString(antwort.jsonPath().getString("id"));
        }
    }

    @Wenn("«{word}» die Liste der Exporte abruft")
    public void liste(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/exporte");
    }

    @Dann("antwortet das Archiv auf die Anforderung mit 202 und Status «{word}»")
    public void angenommen(String status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        assertThat(antwort.jsonPath().getString("status")).isEqualTo(status);
        assertThat(antwort.jsonPath().getString("url")).isNull();
    }

    @Dann("antwortet das Archiv auf die Anforderung mit {int} «{word}»")
    public void abgelehnt(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Und("für «{word}» ist ein Auftrag «{word}» eingereiht")
    public void eingereiht(String mandant, String typ) throws SQLException {
        assertThat(auftraegeVon(mandant, typ)).isEqualTo(1);
    }

    @Und("für «{word}» ist kein Auftrag «{word}» eingereiht")
    public void nichtEingereiht(String mandant, String typ) throws SQLException {
        assertThat(auftraegeVon(mandant, typ)).isZero();
    }

    @Wenn("der Worker die Aufträge abarbeitet")
    public void abarbeiten() throws Exception {
        auftraege.abarbeiten();
    }

    @Dann("ist der Export «{word}» mit {int} Belegen")
    public void fertig(String status, int anzahl) {
        Response liste = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when().get("/api/v1/exporte");
        assertThat(liste.statusCode()).isEqualTo(200);
        assertThat(liste.jsonPath().getString("find { it.id == '" + exportId + "' }.status")).isEqualTo(status);
        assertThat(liste.jsonPath().getInt("find { it.id == '" + exportId + "' }.anzahl")).isEqualTo(anzahl);
        assertThat(liste.jsonPath().getString("find { it.id == '" + exportId + "' }.url")).isNull();
    }

    @Und("das ZIP enthält genau diese Dateien:")
    public void dateien(DataTable tabelle) throws Exception {
        List<String> erwartet = tabelle.asMaps().stream().map(z -> ersetzen(z.get("datei"))).toList();
        assertThat(zip().keySet()).containsExactlyInAnyOrderElementsOf(erwartet);
    }

    @Und("das ZIP enthält diese Dateien:")
    public void enthaelt(DataTable tabelle) throws Exception {
        assertThat(zip().keySet()).containsAll(tabelle.asMaps().stream().map(z -> ersetzen(z.get("datei"))).toList());
    }

    @Und("das ZIP enthält keine Datei «{}»")
    public void enthaeltNicht(String datei) throws Exception {
        assertThat(zip().keySet()).doesNotContain(datei);
    }

    @Und("im ZIP hat «{}» den Inhalt «{}»")
    public void inhalt(String datei, String text) throws Exception {
        assertThat(new String(zip().get(ersetzen(datei)), StandardCharsets.UTF_8)).isEqualTo(text);
    }

    @Und("«belegliste.csv» im ZIP ist UTF-8 mit BOM, Semikolon und CRLF und hat diese Zeilen:")
    public void belegliste(DataTable tabelle) throws Exception {
        byte[] roh = zip().get("belegliste.csv");
        assertThat(Arrays.copyOf(roh, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(roh, 3, roh.length - 3, StandardCharsets.UTF_8);
        assertThat(text).endsWith("\r\n").doesNotContain("\r\n\n");
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
        List<CSVRecord> zeilen = CSVFormat.DEFAULT.builder().setDelimiter(';').get().parse(new StringReader(text))
                .getRecords();
        List<List<String>> erwartet = tabelle.asLists().stream()
                .map(z -> z.stream().map(w -> w == null ? "" : ersetzen(w)).toList()).toList();
        assertThat(zeilen.stream().map(CSVRecord::toList).toList()).containsExactlyInAnyOrderElementsOf(erwartet);
        assertThat(zeilen.getFirst().toList()).isEqualTo(erwartet.getFirst());
    }

    @Und("«pruefsummen.sha256» im ZIP nennt jede andere Datei des ZIP mit ihrer SHA-256")
    public void pruefsummen() throws Exception {
        Map<String, byte[]> dateien = zip();
        String text = new String(dateien.get("pruefsummen.sha256"), StandardCharsets.US_ASCII);
        Map<String, String> summen = new LinkedHashMap<>();
        for (String zeile : text.split("\n")) {
            String[] teile = zeile.split(" {2}", 2);
            assertThat(teile[0]).matches("[0-9a-f]{64}");
            assertThat(summen.put(teile[1], teile[0])).as("doppelt: " + teile[1]).isNull();
        }
        assertThat(text).endsWith("\n");
        assertThat(summen.keySet()).containsExactlyInAnyOrderElementsOf(dateien.keySet().stream()
                .filter(n -> !n.equals("pruefsummen.sha256")).toList());
        summen.forEach((name, sha) -> assertThat(Pruefsumme.von(dateien.get(name))).as(name).isEqualTo(sha));
    }

    @Dann("folgt jeder Name unter «belege\\/» im ZIP dem Muster «<datum>_<art>_<belegnummer|id>_<partner>.pdf» nur mit ASCII ohne Leerzeichen")
    public void muster() throws Exception {
        List<String> namen = zip().keySet().stream().filter(n -> n.startsWith("belege/")).toList();
        assertThat(namen).hasSize(3);
        for (String n : namen) {
            String name = n.substring("belege/".length());
            assertThat(name).matches(ExportDateiname.MUSTER).endsWith(".pdf").doesNotContain(" ");
            assertThat(name.chars().allMatch(c -> c > ' ' && c <= '~')).as(name).isTrue();
        }
    }

    @Wenn("«{word}» den Export abruft")
    public void abrufen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/exporte/" + exportId);
    }

    @Wenn("der Export vor {int} Stunden fertig geworden ist")
    public void zurueckdatieren(int stunden) throws SQLException {
        ausfuehren("update " + schema("urinet") + ".export set fertig = ? where id = ?",
                OffsetDateTime.now().minusHours(stunden), exportId);
    }

    @Dann("liefert das Archiv eine Adresse, die höchstens bis 24 Stunden nach der Fertigstellung gilt und nicht zwischengespeichert wird")
    public void adresse() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.header("Cache-Control")).contains("no-store");
        assertThat(antwort.jsonPath().getString("status")).isEqualTo("FERTIG");
        Instant fertig = Instant.parse(antwort.jsonPath().getString("fertig"));
        Instant gueltigBis = Instant.parse(antwort.jsonPath().getString("gueltigBis"));
        assertThat(gueltigBis).isEqualTo(fertig.plus(24, ChronoUnit.HOURS));
        String url = antwort.jsonPath().getString("url");
        assertThat(url).contains("versionId=", "exporte/" + exportId + ".zip");
        Matcher gueltig = GUELTIG.matcher(url);
        assertThat(gueltig.find()).isTrue();
        long sekunden = Long.parseLong(gueltig.group(1));
        assertThat(sekunden).isPositive().isLessThanOrEqualTo(Instant.now().until(gueltigBis, ChronoUnit.SECONDS) + 1);
    }

    @Und("die Adresse liefert das ZIP mit der SHA-256 des Exports")
    public void zipUeberAdresse() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> abruf = http.send(HttpRequest.newBuilder(URI.create(antwort.jsonPath().getString("url")))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(abruf.statusCode()).isEqualTo(200);
            assertThat(abruf.headers().firstValue("Content-Disposition").orElse(""))
                    .contains("jahresexport-urinet-2025.zip");
            assertThat(Pruefsumme.von(abruf.body())).isEqualTo(antwort.jsonPath().getString("sha256"));
        }
    }

    @Und("hat der Inhaber eine Mail zum Export erhalten, die nur den Link auf die Seite «Export» und die Export-ID nennt")
    public void mail() {
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        Response nachrichten = given().baseUri(mailpit).queryParam("query", "subject:\"" + exportId + "\"")
                .when().get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<String> ids = nachrichten.jsonPath().getList("messages.ID");
        assertThat(ids).as(nachrichten.asString()).hasSize(1);
        Response nachricht = given().baseUri(mailpit).when().get("/api/v1/message/" + ids.getFirst());
        assertThat(nachricht.jsonPath().<String>getList("To.Address")).containsExactly("inhaber@example.invalid");
        String text = nachricht.jsonPath().getString("Text");
        assertThat(text).contains(exportId.toString(), "http://localhost:8080/export");
        assertThat(text).doesNotContain("X-Amz", "versionId", "exporte/", "Müller", "2025-007", "PDF/A", "HYPERLINK",
                antwort.jsonPath().getString("sha256"));
        assertThat(nachricht.jsonPath().getList("Attachments")).isEmpty();
    }

    @Dann("liefert das Archiv den Export mit Status «{word}» ohne Adresse")
    public void ohneAdresse(String status) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().getString("status")).isEqualTo(status);
        assertThat(antwort.jsonPath().getString("url")).isNull();
    }

    @Angenommen("der Stand des Zugriffsprotokolls für den Export von «{word}» ist gemerkt")
    public void standMerken(String mandant) throws SQLException {
        protokollStand.put(mandant, protokollStand(mandant));
    }

    @Dann("enthält das Zugriffsprotokoll von «{word}» für den Export genau diese neuen Einträge:")
    public void protokoll(String mandant, DataTable tabelle) throws SQLException {
        String sha = zipSha(mandant);
        List<List<String>> ist = new ArrayList<>();
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select benutzer, rolle, aktion, details ->> 'sha256' from " + schema(mandant)
                        + ".zugriff_protokoll where dokument_id = ? order by id")) {
            p.setObject(1, exportId, Types.OTHER);
            try (ResultSet z = p.executeQuery()) {
                while (z.next()) {
                    ist.add(Arrays.asList(z.getString(1), z.getString(2), z.getString(3),
                            z.getString(4) == null ? "" : z.getString(4).equals(sha) ? "ZIP" : z.getString(4)));
                }
            }
        }
        List<List<String>> erwartet = tabelle.asMaps().stream().map(z -> Arrays.asList(
                Testanmeldung.sub(z.get("benutzer")), z.get("rolle"), z.get("aktion"),
                z.get("sha256") == null ? "" : z.get("sha256"))).toList();
        assertThat(ist).containsExactlyElementsOf(erwartet);
    }

    @Und("das Zugriffsprotokoll von «{word}» hat genau einen neuen Eintrag VERWEIGERT von «{word}»")
    public void verweigert(String mandant, String benutzer) throws SQLException {
        long stand = protokollStandGemerkt(mandant);
        List<String[]> neu = new ArrayList<>();
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select benutzer, aktion, details ->> 'fehlercode' from " + schema(mandant)
                        + ".zugriff_protokoll where id > ? order by id")) {
            p.setLong(1, stand);
            try (ResultSet z = p.executeQuery()) {
                while (z.next()) {
                    neu.add(new String[]{z.getString(1), z.getString(2), z.getString(3)});
                }
            }
        }
        assertThat(neu).hasSize(1);
        assertThat(neu.getFirst()).containsExactly(Testanmeldung.sub(benutzer), "VERWEIGERT", "NICHT_ERLAUBT");
    }

    @Dann("antwortet der Abruf des Exports so:")
    public void abrufMatrix(DataTable tabelle) {
        for (Map<String, String> z : tabelle.asMaps()) {
            Response r = given().auth().oauth2(Testanmeldung.token(z.get("benutzer"))).when()
                    .get("/api/v1/exporte/" + exportId);
            int status = Integer.parseInt(z.get("status"));
            if (status == 200) {
                assertThat(r.statusCode()).as(z.get("benutzer")).isEqualTo(200);
                assertThat(r.jsonPath().getString("url")).as(z.get("benutzer")).isNotBlank();
            } else {
                assertThat(ProblemDetails.pruefen(r, status)).as(z.get("benutzer")).isEqualTo(z.get("fehlercode"));
            }
        }
    }

    @Dann("liegt das ZIP ohne Sperre unter «exporte\\/<id>.zip» im Bucket von «{word}»")
    public void ohneSperre(String mandant) throws SQLException {
        String[] ablage = ablage(mandant);
        assertThat(ablage[0]).isEqualTo("exporte/" + exportId + ".zip");
        assertThat(TestAblage.versionen(mandant, ablage[0])).containsExactly(ablage[0] + "@" + ablage[1]);
        assertThat(TestAblage.gesperrt(mandant, ablage[0], ablage[1])).isFalse();
    }

    @Und("der Bucket von «{word}» entfernt alles unter «exporte\\/» nach {int} Tagen")
    public void lebenszyklus(String mandant, int tage) {
        List<LifecycleRule> regeln = TestAblage.lebenszyklus(mandant).stream()
                .filter(r -> r.filter() != null && "exporte/".equals(r.filter().prefix())).toList();
        assertThat(regeln).singleElement().satisfies(r -> {
            assertThat(r.statusAsString()).isEqualTo("Enabled");
            assertThat(r.expiration().days()).isEqualTo(tage);
            assertThat(r.noncurrentVersionExpiration().noncurrentDays()).isEqualTo(1);
        });
    }

    private Map<String, byte[]> zip() throws SQLException, IOException {
        if (zip == null) {
            String[] ablage = ablage("urinet");
            zip = new LinkedHashMap<>();
            try (ZipInputStream ein = new ZipInputStream(new ByteArrayInputStream(
                    TestAblage.lesen("urinet", ablage[0], ablage[1])), StandardCharsets.UTF_8)) {
                for (ZipEntry e = ein.getNextEntry(); e != null; e = ein.getNextEntry()) {
                    assertThat(zip.put(e.getName(), ein.readAllBytes())).as("doppelt: " + e.getName()).isNull();
                }
            }
        }
        return zip;
    }

    /** Schlüssel und Versions-ID des ZIP aus {@code export}. */
    private String[] ablage(String mandant) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select s3_schluessel, s3_version, sha256 from " + schema(mandant) + ".export where id = ?")) {
            p.setObject(1, exportId, Types.OTHER);
            try (ResultSet z = p.executeQuery()) {
                assertThat(z.next()).as("Export " + exportId).isTrue();
                assertThat(z.getString(1)).as("Export fertig").isNotNull();
                return new String[]{z.getString(1), z.getString(2), z.getString(3)};
            }
        }
    }

    private String zipSha(String mandant) throws SQLException {
        return ablage(mandant)[2];
    }

    /** {@code {NAME}} → Dokument-ID, {@code {sha NAME}} → SHA-256 der Datei des Belegs. */
    private String ersetzen(String text) {
        Matcher m = PLATZHALTER.matcher(text);
        StringBuilder aus = new StringBuilder();
        while (m.find()) {
            Beleg b = belege.get(m.group(2));
            assertThat(b).as("Beleg " + m.group(2)).isNotNull();
            m.appendReplacement(aus, m.group(1) == null ? b.id().toString() : b.sha256());
        }
        return m.appendTail(aus).toString();
    }

    private long auftraegeVon(String mandant, String typ) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select count(*) from public.auftrag where mandant = ? and typ = ?")) {
            p.setString(1, mandant);
            p.setString(2, typ);
            try (ResultSet z = p.executeQuery()) {
                z.next();
                return z.getLong(1);
            }
        }
    }

    private long protokollStandGemerkt(String mandant) {
        Long stand = protokollStand.get(mandant);
        assertThat(stand).as("Stand des Zugriffsprotokolls von " + mandant + " gemerkt").isNotNull();
        return stand;
    }

    private long protokollStand(String mandant) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); Statement befehl = v.createStatement();
             ResultSet z = befehl.executeQuery("select coalesce(max(id), 0) from " + schema(mandant)
                     + ".zugriff_protokoll")) {
            z.next();
            return z.getLong(1);
        }
    }

    private void manifestLoeschen(String mandant, LocalDate datum) throws SQLException {
        if (!vorhanden(mandant)) {
            return;
        }
        String s = schema(mandant);
        try (Connection v = datenbank.get().getConnection()) {
            v.setAutoCommit(false);
            try (Statement befehl = v.createStatement()) {
                // manifest und manifest_zeitstempel sind nur Einfügen; replica schaltet die Trigger ab (nur in Tests)
                befehl.execute("set local session_replication_role = replica");
                for (String tabelle : List.of("manifest_zeitstempel", "manifest")) {
                    try (PreparedStatement p = v.prepareStatement("delete from " + s + "." + tabelle + " where datum = ?")) {
                        p.setObject(1, datum);
                        p.executeUpdate();
                    }
                }
                v.commit();
            } catch (SQLException e) {
                v.rollback();
                throw e;
            } finally {
                v.setAutoCommit(true);
            }
        }
    }

    private boolean vorhanden(String mandant) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select 1 from public.mandant where code = ?")) {
            p.setString(1, mandant);
            try (ResultSet z = p.executeQuery()) {
                return z.next();
            }
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                if (werte[i] instanceof UUID uuid) {
                    p.setObject(i + 1, uuid, Types.OTHER);
                } else if (werte[i] instanceof OffsetDateTime zeit) {
                    p.setObject(i + 1, zeit, Types.TIMESTAMP_WITH_TIMEZONE);
                } else {
                    p.setObject(i + 1, werte[i]);
                }
            }
            p.executeUpdate();
        }
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
