package ch.urinet.archiv.wissen.adapter.out.okf;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.domain.Exportdatei;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantVollexportDaten;
import ch.urinet.archiv.mandant.application.MandantVollexportSchreiber;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.wissen.application.WissenOkfExportPort;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@Tag("start-4")
@TestProfile(OkfExportDatenTest.ExportProfil.class)
class OkfExportDatenTest {
    public static class ExportProfil implements QuarkusTestProfile {
        private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.devservices.db-name", "archiv_okf_export_" + SUFFIX);
        }
    }

    @Inject
    WissenOkfExportPort export;
    @Inject
    MandantKontext kontext;
    @Inject
    MandantProvisionierung provisionierung;
    @Inject
    Instance<AgroalDataSource> datenbank;
    @Inject
    AuftragArbeiter arbeiter;

    @Test
    void angeforderterExportWirdGeschriebenSigniertUndProtokolliert() throws Exception {
        MandantCode code = new MandantCode("urinet");
        if (!vorhanden(code)) {
            provisionierung.anlegen(code.wert(), null, null);
        }
        MandantCode fremder = new MandantCode("vhf-gsk");
        if (!vorhanden(fremder)) {
            provisionierung.anlegen(fremder.wert(), null, null);
        }
        UUID seitenId = UUID.randomUUID();
        String pfad = "konzepte/export-" + seitenId;
        UUID fremdeSeitenId = UUID.randomUUID();
        String fremderPfad = "konzepte/export-" + fremdeSeitenId;
        UUID exportId = null;
        try {
            seite(code, seitenId, pfad);
            seite(fremder, fremdeSeitenId, fremderPfad);
            String token = Testanmeldung.token("inhaber-urinet");
            var angefordert = given().auth().oauth2(token).when().post("/api/v1/wissensbasis/exporte")
                    .then().statusCode(202).extract().jsonPath();
            assertThat(angefordert.getString("status")).isEqualTo("OFFEN");
            exportId = UUID.fromString(angefordert.getString("id"));
            given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).when()
                    .get("/api/v1/wissensbasis/exporte/" + exportId).then().statusCode(404);
            long frist = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
            while ("OFFEN".equals(exportStatus(code, exportId)) && System.nanoTime() < frist) {
                var laeufe = arbeiter.abholen();
                if (laeufe.isEmpty()) {
                    Thread.sleep(100);
                }
                for (var lauf : laeufe) {
                    lauf.get(1, TimeUnit.MINUTES);
                }
            }
            var antwort = given().auth().oauth2(token).when().get("/api/v1/wissensbasis/exporte/" + exportId)
                    .then().statusCode(200).extract().jsonPath();
            assertThat(antwort.getString("status")).isEqualTo("FERTIG");
            assertThat(antwort.getString("url")).isNotBlank();
            Instant fertig = Instant.parse(antwort.getString("fertig"));
            assertThat(Instant.parse(antwort.getString("gueltigBis"))).isEqualTo(fertig.plus(Duration.ofHours(24)));
            String key = Exportdatei.wissensSchluessel(exportId);
            String version;
            try (Connection c = datenbank.get().getConnection();
                 PreparedStatement sql = c.prepareStatement("select s3_version from " + code.schema()
                         + ".wissens_export where id = ?")) {
                sql.setObject(1, exportId);
                try (ResultSet rs = sql.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    version = rs.getString(1);
                }
            }
            byte[] zip = TestAblage.lesen(code.wert(), key, version);
            assertThat(antwort.getString("sha256")).isEqualTo(Pruefsumme.von(zip));
            assertThat(dateinamen(zip)).contains(pfad + ".md", "index.md", "log.md", "paket.yaml");
            assertThat(dateinamen(zip)).doesNotContain(fremderPfad + ".md");
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<byte[]> download = http.send(
                        HttpRequest.newBuilder(URI.create(antwort.getString("url"))).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(download.statusCode()).isEqualTo(200);
                assertThat(Pruefsumme.von(download.body())).isEqualTo(Pruefsumme.von(zip));
            }
            try (Connection c = datenbank.get().getConnection();
                 PreparedStatement sql = c.prepareStatement("select count(*) from " + code.schema()
                         + ".zugriff_protokoll where dokument_id = ? and aktion = 'EXPORTIEREN' "
                         + "and details ->> 'sha256' = ?")) {
                sql.setObject(1, exportId);
                sql.setString(2, Pruefsumme.von(zip));
                try (ResultSet rs = sql.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
                }
            }
        } finally {
            try {
                if (exportId != null) {
                    String key = Exportdatei.wissensSchluessel(exportId);
                    try {
                        TestAblage.loeschen(code.wert(), key, key::equals);
                    } finally {
                        try (Connection c = datenbank.get().getConnection()) {
                            try (PreparedStatement sql = c.prepareStatement(
                                    "delete from public.auftrag where typ = 'WISSEN_EXPORT' and nutzlast->>'exportId' = ?")) {
                                sql.setString(1, exportId.toString());
                                sql.executeUpdate();
                            }
                            try (PreparedStatement sql = c.prepareStatement(
                                    "delete from " + code.schema() + ".wissens_export where id = ?")) {
                                sql.setObject(1, exportId);
                                sql.executeUpdate();
                            }
                            try (var sql = c.createStatement()) {
                                sql.execute("set session_replication_role = replica");
                                try (PreparedStatement log = c.prepareStatement(
                                        "delete from " + code.schema() + ".zugriff_protokoll where dokument_id = ?")) {
                                    log.setObject(1, exportId);
                                    log.executeUpdate();
                                } finally {
                                    sql.execute("set session_replication_role = origin");
                                }
                            }
                        }
                    }
                }
            } finally {
                try {
                    loeschen(code, seitenId);
                } finally {
                    loeschen(fremder, fremdeSeitenId);
                }
            }
        }
    }

    private String exportStatus(MandantCode code, UUID id) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("select status from " + code.schema()
                     + ".wissens_export where id = ?")) {
            sql.setObject(1, id);
            try (ResultSet rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    @Test
    void geschaeftsstelleErhaeltBeiEinstellungen403OhneAenderung() throws SQLException {
        MandantCode code = new MandantCode("vhf-gsk");
        if (!vorhanden(code)) {
            provisionierung.anlegen(code.wert(), null, null);
        }
        String vorher = einstellungen(code);
        String token = Testanmeldung.token("geschaeftsstelle-vhf-gsk");
        given().auth().oauth2(token).when().get("/api/v1/wissensbasis/einstellungen").then().statusCode(403);
        given().auth().oauth2(token).contentType("application/json")
                .body("{\"budgetTokens\":100,\"lintBudgetTokens\":100,\"monatsbudgetTokens\":100}")
                .when().put("/api/v1/wissensbasis/einstellungen").then().statusCode(403);
        assertThat(einstellungen(code)).isEqualTo(vorher);
    }

    @Test
    void exportIstNurFuerA19Erlaubt() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!vorhanden(new MandantCode(code))) {
                provisionierung.anlegen(code, null, null);
            }
        }
        for (String benutzer : List.of("mitarbeiter-urinet", "treuhaender-urinet", "geschaeftsstelle-vhf-gsk")) {
            String token = Testanmeldung.token(benutzer);
            given().auth().oauth2(token).when().post("/api/v1/wissensbasis/exporte").then().statusCode(403);
            given().auth().oauth2(token).when().get("/api/v1/wissensbasis/exporte/" + UUID.randomUUID())
                    .then().statusCode(403);
        }
    }

    @Test
    void inhaberSpeichertNurGueltigeEinstellungenImEigenenMandanten() throws SQLException {
        MandantCode eigener = new MandantCode("urinet");
        MandantCode fremder = new MandantCode("vhf-gsk");
        for (MandantCode code : List.of(eigener, fremder)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        String vorher = einstellungen(eigener);
        String fremdVorher = einstellungen(fremder);
        String token = Testanmeldung.token("inhaber-urinet");
        String url = "/api/v1/wissensbasis/einstellungen";
        String urspruenglicheWerte = given().auth().oauth2(token).when().get(url).then()
                .statusCode(200).extract().asString();
        try {
            given().auth().oauth2(token).contentType("application/json")
                    .body("{\"budgetTokens\":1234,\"lintBudgetTokens\":2345,\"monatsbudgetTokens\":3456}")
                    .when().put(url).then().statusCode(200).body("budgetTokens", is(1234));
            given().auth().oauth2(token).contentType("application/json")
                    .body("{\"budgetTokens\":0,\"lintBudgetTokens\":2345,\"monatsbudgetTokens\":3456}")
                    .when().put(url).then().statusCode(422);
            given().auth().oauth2(token).when().get(url).then().statusCode(200)
                    .body("budgetTokens", is(1234)).body("monatsbudgetTokens", is(3456));
            assertThat(einstellungen(fremder)).isEqualTo(fremdVorher);
        } finally {
            if (vorher == null) {
                try (Connection c = datenbank.get().getConnection();
                     PreparedStatement sql = c.prepareStatement("delete from " + eigener.schema()
                             + ".wissens_einstellung where id = 1")) {
                    sql.executeUpdate();
                }
            } else {
                given().auth().oauth2(token).contentType("application/json").body(urspruenglicheWerte)
                        .when().put(url).then().statusCode(200);
            }
        }
    }

    private String einstellungen(MandantCode code) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("select row_to_json(e)::text from " + code.schema()
                     + ".wissens_einstellung e where id = 1");
             ResultSet rs = sql.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void exportLiestNurDasSchemaDesAuftrags(@TempDir Path temp) throws Exception {
        MandantCode erster = new MandantCode("urinet");
        MandantCode zweiter = new MandantCode("vhf-gsk");
        for (MandantCode code : List.of(erster, zweiter)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String pfad1 = "konzepte/probe-" + id1;
        String pfad2 = "konzepte/probe-" + id2;
        try {
            seite(erster, id1, pfad1);
            seite(zweiter, id2, pfad2);
            byte[] zip1 = exportieren(erster);
            byte[] zip2 = exportieren(zweiter);
            assertThat(dateinamen(zip1)).contains(pfad1 + ".md").doesNotContain(pfad2 + ".md");
            assertThat(dateinamen(zip2)).contains(pfad2 + ".md").doesNotContain(pfad1 + ".md");
            byte[] paket = dateiBytes(vollexportieren(erster, temp), "wissensbasis.zip");
            assertThat(dateinamen(paket)).contains(pfad1 + ".md").doesNotContain(pfad2 + ".md");
        } finally {
            loeschen(erster, id1);
            loeschen(zweiter, id2);
        }
    }

    @Test
    void veralteteHuelleErhoehtKontextstufeUndPersonaldokumentBrichtAb() throws Exception {
        MandantCode code = new MandantCode("urinet");
        if (!vorhanden(code)) {
            provisionierung.anlegen(code.wert(), null, null);
        }
        UUID id = UUID.randomUUID();
        UUID dokument = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(code.wert(), null, "NORMAL", true, "probe", null, "ARCHIVIERT"));
        try {
            seite(code, id, "konzepte/probe-" + id);
            try (Connection c = datenbank.get().getConnection()) {
                try (PreparedStatement sql = c.prepareStatement("insert into " + code.schema()
                        + ".dokument_datei (dokument_id, nr, rolle, sha256) values (?, 1, 'ORIGINAL', ?)")) {
                    sql.setObject(1, dokument);
                    sql.setString(2, UUID.randomUUID().toString().replace("-", "").repeat(2));
                    sql.executeUpdate();
                }
                try (PreparedStatement sql = c.prepareStatement("insert into " + code.schema()
                        + ".wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von, auszug) "
                        + "values (?, 'q1', ?, 1, 'Probe')")) {
                    sql.setObject(1, id);
                    sql.setObject(2, dokument);
                    sql.executeUpdate();
                }
            }
            Map<?, ?> paket = new Yaml().load(datei(exportieren(code), "paket.yaml"));
            assertThat(paket.get("kontext_stufe")).isEqualTo("INTERN");
            try (Connection c = datenbank.get().getConnection();
                 PreparedStatement sql = c.prepareStatement("update " + code.schema()
                         + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?")) {
                sql.setObject(1, dokument);
                sql.executeUpdate();
            }
            assertThatThrownBy(() -> exportieren(code))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Dokument der Wissenshülle ist nicht exportierbar");
        } finally {
            loeschen(code, id);
            Testdokumente.loeschen(datenbank.get(), code.wert(), List.of(dokument));
        }
    }

    private byte[] exportieren(MandantCode code) throws Exception {
        return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> {
            ByteArrayOutputStream ziel = new ByteArrayOutputStream();
            try {
                export.schreiben(code, ziel);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ziel.toByteArray();
        })).get(1, TimeUnit.MINUTES);
    }

    private byte[] vollexportieren(MandantCode code, Path temp) throws Exception {
        MandantVollexportDaten daten = new MandantVollexportDaten() {
            public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                return List.of();
            }

            public List<Manifestdatei> manifeste() {
                return List.of();
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    throw new AssertionError("Unerwarteter Ablageaufruf: " + methode.getName());
                });
        return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> {
            ByteArrayOutputStream ziel = new ByteArrayOutputStream();
            try {
                new MandantVollexportSchreiber(daten, ablage, export).schreiben(code, UUID.randomUUID(), temp, ziel);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ziel.toByteArray();
        })).get(1, TimeUnit.MINUTES);
    }

    private List<String> dateinamen(byte[] daten) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(daten))) {
            java.util.ArrayList<String> namen = new java.util.ArrayList<>();
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                namen.add(eintrag.getName());
            }
            return namen;
        }
    }

    private String datei(byte[] daten, String name) throws IOException {
        return new String(dateiBytes(daten, name), StandardCharsets.UTF_8);
    }

    private byte[] dateiBytes(byte[] daten, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(daten))) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                if (eintrag.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("Datei im Testpaket fehlt");
    }

    private boolean vorhanden(MandantCode code) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("select 1 from public.mandant where code = ?")) {
            sql.setString(1, code.wert());
            try (ResultSet rs = sql.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void seite(MandantCode code, UUID id, String pfad) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("insert into " + code.schema() + ".wissensseite "
                     + "(id,pfad,typ,titel,beschreibung,vertraulichkeit,inhalt,version,erzeugt_von,erzeugt_am,"
                     + "freigegeben_von,freigegeben_am) values (?,?,'Konzept','Probe','Probe','NORMAL',"
                     + "'Probe',1,'probe',now(),'probe',now())")) {
            sql.setObject(1, id);
            sql.setString(2, pfad);
            sql.executeUpdate();
        }
    }

    private void loeschen(MandantCode code, UUID id) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("delete from " + code.schema() + ".wissensseite where id=?")) {
            sql.setObject(1, id);
            sql.executeUpdate();
        }
    }
}
