package ch.urinet.archiv.wissen;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.domain.Exportdatei;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class WissenExportSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragArbeiter arbeiter;

    private UUID exportId;
    private UUID seitenId;
    private String seitenPfad;
    private Response antwort;

    @Angenommen("eine synthetische freigegebene Wissensseite ist vorhanden")
    public void seiteAnlegen() throws Exception {
        seitenId = UUID.randomUUID();
        seitenPfad = "konzepte/export-" + seitenId;
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("""
                     insert into m_urinet.wissensseite
                     (id,pfad,typ,titel,beschreibung,vertraulichkeit,inhalt,version,erzeugt_von,erzeugt_am,
                      freigegeben_von,freigegeben_am)
                     values (?,?,'Konzept','Probe','Beschreibung','NORMAL','Probetext',1,'probe',now(),'probe',now())
                     """)) {
            sql.setObject(1, seitenId);
            sql.setString(2, seitenPfad);
            sql.executeUpdate();
        }
    }

    @Wenn("der Inhaber einen Wissensexport anfordert und ausführen lässt")
    public void exportAnfordern() throws Exception {
        String token = Testanmeldung.token("inhaber-urinet");
        exportId = UUID.fromString(given().auth().oauth2(token).when().post("/api/v1/wissensbasis/exporte")
                .then().statusCode(202).extract().path("id"));
        long frist = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        do {
            for (var lauf : arbeiter.abholen()) {
                lauf.get(1, TimeUnit.MINUTES);
            }
            antwort = given().auth().oauth2(token).when().get("/api/v1/wissensbasis/exporte/" + exportId)
                    .then().statusCode(200).extract().response();
            if ("OFFEN".equals(antwort.path("status"))) {
                Thread.sleep(100);
            }
        } while ("OFFEN".equals(antwort.path("status")) && System.nanoTime() < frist);
        assertThat((String) antwort.path("status")).isEqualTo("FERTIG");
    }

    @Dann("enthält das ZIP die Seite mit OKF-Frontmatter sowie Index und Log")
    public void paketPruefen() throws Exception {
        Map<String, String> dateien = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download()))) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                dateien.put(eintrag.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(dateien).containsKeys(seitenPfad + ".md", "index.md", "log.md");
        Map<?, ?> kopf = frontmatter(dateien.get(seitenPfad + ".md"));
        assertThat(kopf.keySet().stream().map(Object::toString).toList())
                .contains("type", "title", "description", "tags", "status", "stale_after",
                        "generated", "verified", "sources");
        assertThat(kopf.get("type")).isEqualTo("Konzept");
        assertThat(kopf.get("status")).isEqualTo("stable");
        assertThat(frontmatter(dateien.get("index.md")).get("okf_version")).isEqualTo("0.2");
        assertThat(dateien.get("log.md")).contains("# Update Log");
    }

    @Dann("ist der Download 24 Stunden ab Fertigstellung gültig und seine Prüfsumme protokolliert")
    public void downloadPruefen() throws Exception {
        Instant fertig = Instant.parse(antwort.path("fertig"));
        assertThat(Instant.parse(antwort.path("gueltigBis"))).isEqualTo(fertig.plus(Duration.ofHours(24)));
        String sha256 = Pruefsumme.von(download());
        assertThat((String) antwort.path("sha256")).isEqualTo(sha256);
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where dokument_id = ? and aktion = 'EXPORTIEREN' and details ->> 'sha256' = ?
                     """)) {
            sql.setObject(1, exportId);
            sql.setString(2, sha256);
            try (ResultSet rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }
        }
    }

    private byte[] download() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> download = http.send(
                    HttpRequest.newBuilder(URI.create(antwort.path("url"))).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(download.statusCode()).isEqualTo(200);
            return download.body();
        }
    }

    private Map<?, ?> frontmatter(String markdown) {
        assertThat(markdown).startsWith("---\n");
        int ende = markdown.indexOf("\n---\n", 4);
        assertThat(ende).isGreaterThanOrEqualTo(4);
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(markdown.substring(4, ende));
    }

    @After("@wissen-export")
    public void aufraeumen() throws Exception {
        try {
            exportLoeschen();
        } finally {
            if (seitenId != null) {
                try (Connection c = datenbank.get().getConnection();
                     PreparedStatement sql = c.prepareStatement("delete from m_urinet.wissensseite where id = ?")) {
                    sql.setObject(1, seitenId);
                    sql.executeUpdate();
                }
            }
        }
    }

    private void exportLoeschen() throws Exception {
        if (exportId != null) {
            String key = Exportdatei.wissensSchluessel(exportId);
            try {
                TestAblage.loeschen("urinet", key, key::equals);
            } finally {
                try (Connection c = datenbank.get().getConnection()) {
                    try (PreparedStatement sql = c.prepareStatement(
                            "delete from public.auftrag where typ = 'WISSEN_EXPORT' and nutzlast->>'exportId' = ?")) {
                        sql.setString(1, exportId.toString());
                        sql.executeUpdate();
                    }
                    try (PreparedStatement sql = c.prepareStatement(
                            "delete from m_urinet.wissens_export where id = ?")) {
                        sql.setObject(1, exportId);
                        sql.executeUpdate();
                    }
                    try (var sql = c.createStatement()) {
                        sql.execute("set session_replication_role = replica");
                        try (PreparedStatement log = c.prepareStatement(
                                "delete from m_urinet.zugriff_protokoll where dokument_id = ?")) {
                            log.setObject(1, exportId);
                            log.executeUpdate();
                        } finally {
                            sql.execute("set session_replication_role = origin");
                        }
                    }
                }
            }
        }
    }
}
