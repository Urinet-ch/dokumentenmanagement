package ch.urinet.archiv.wissen;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Exportdatei;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantVollexportDaten;
import ch.urinet.archiv.mandant.application.MandantVollexportSchreiber;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.wissen.application.WissenOkfExportPort;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class WissenExportSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragArbeiter arbeiter;

    @Inject
    MandantKontext kontext;

    @Inject
    WissenOkfExportPort wissenExport;

    private UUID exportId;
    private UUID seitenId;
    private String seitenPfad;
    private UUID referenzId;
    private final List<UUID> fremdeSeiten = new java.util.ArrayList<>();
    private final List<String> fremdePfade = new java.util.ArrayList<>();
    private String referenzPfad;
    private UUID dokumentId;
    private Response antwort;
    private byte[] vollexport;

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

    @Angenommen("der Mandant «vhf-gsk» hat eine freigegebene und eine interne Wissensseite")
    public void fremdeSeitenAnlegen() throws Exception {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("""
                     insert into m_vhf_gsk.wissensseite
                     (id,pfad,typ,titel,beschreibung,vertraulichkeit,inhalt,version,erzeugt_von,erzeugt_am,
                      freigegeben_von,freigegeben_am)
                     values (?,?,'Konzept','Fremdprobe','Beschreibung',?,'Fremdtext',1,'probe',now(),'probe',now())
                     """)) {
            for (String stufe : List.of("NORMAL", "INTERN")) {
                UUID id = UUID.randomUUID();
                fremdeSeiten.add(id);
                fremdePfade.add("konzepte/fremd-" + stufe.toLowerCase() + "-" + id);
                sql.setObject(1, id);
                sql.setString(2, fremdePfade.get(fremdePfade.size() - 1));
                sql.setString(3, stufe);
                sql.executeUpdate();
            }
        }
    }

    @Angenommen("eine synthetische Wissensseite verweist auf ein Dokument und eine andere Seite")
    public void quellenAnlegen() throws Exception {
        seiteAnlegen();
        referenzId = UUID.randomUUID();
        referenzPfad = "konzepte/quelle-" + referenzId;
        dokumentId = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", null, "NORMAL", true, "probe", null, "ARCHIVIERT"));
        try (Connection c = datenbank.get().getConnection()) {
            try (PreparedStatement sql = c.prepareStatement("""
                    insert into m_urinet.wissensseite
                    (id,pfad,typ,titel,beschreibung,vertraulichkeit,inhalt,version,erzeugt_von,erzeugt_am,
                     freigegeben_von,freigegeben_am)
                    values (?,?,'Konzept','Quelle','Beschreibung','NORMAL','Probetext',1,'probe',now(),'probe',now())
                    """)) {
                sql.setObject(1, referenzId);
                sql.setString(2, referenzPfad);
                sql.executeUpdate();
            }
            try (PreparedStatement sql = c.prepareStatement("""
                    insert into m_urinet.dokument_datei (dokument_id,nr,rolle,sha256)
                    values (?,1,'ORIGINAL',?)
                    """)) {
                sql.setObject(1, dokumentId);
                sql.setString(2, "a".repeat(64));
                sql.executeUpdate();
            }
            try (PreparedStatement sql = c.prepareStatement("""
                    insert into m_urinet.wissensseite_quelle
                    (seite_id,schluessel,dokument_id,seite_von,auszug) values (?,'q1',?,2,'Probe')
                    """)) {
                sql.setObject(1, seitenId);
                sql.setObject(2, dokumentId);
                sql.executeUpdate();
            }
            try (PreparedStatement sql = c.prepareStatement("""
                    insert into m_urinet.wissensseite_quelle (seite_id,schluessel,quell_seite_id)
                    values (?,'q2',?)
                    """)) {
                sql.setObject(1, seitenId);
                sql.setObject(2, referenzId);
                sql.executeUpdate();
            }
            try (PreparedStatement sql = c.prepareStatement(
                    "update m_urinet.wissensseite set inhalt = ? where id = ?")) {
                sql.setString(1, "[Quelle](/" + referenzPfad + ").[^q1] [^q2]");
                sql.setObject(2, seitenId);
                sql.executeUpdate();
            }
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

    @Dann("ist der Export nur für den Inhaber seines Mandanten abrufbar")
    public void exportIsolationPruefen() {
        for (String benutzer : List.of("mitarbeiter-urinet", "treuhaender-urinet", "geschaeftsstelle-vhf-gsk")) {
            given().auth().oauth2(Testanmeldung.token(benutzer))
                    .get("/api/v1/wissensbasis/exporte/" + exportId).then().statusCode(403);
        }
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk"))
                .get("/api/v1/wissensbasis/exporte/" + exportId).then().statusCode(404);
    }

    @Dann("enthält das ZIP keine Seite und keinen Inhalt des Mandanten «vhf-gsk»")
    public void exportOhneFremdseitenPruefen() throws Exception {
        assertThat(fremdePfade).hasSize(2);
        Map<String, String> dateien = dateien();
        assertThat(dateien).containsKey(seitenPfad + ".md");
        for (String pfad : fremdePfade) {
            assertThat(dateien).doesNotContainKey(pfad + ".md");
            assertThat(dateien.values()).noneMatch(inhalt -> inhalt.contains(pfad));
        }
        assertThat(dateien.values()).noneMatch(inhalt -> inhalt.contains("Fremdprobe") || inhalt.contains("Fremdtext"));
    }

    @Dann("enthält das ZIP die Seite mit OKF-Frontmatter sowie Index und Log")
    public void paketPruefen() throws Exception {
        Map<String, String> dateien = dateien();
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

    @Dann("enthält das ZIP URN und Seitenpfad statt Dokumentdateien und schreibt den Markdown-Link um")
    public void verweisePruefen() throws Exception {
        Map<String, String> dateien = dateien();
        assertThat(dateien.keySet()).allMatch(name -> name.endsWith(".md") || name.equals("paket.yaml"));
        String markdown = dateien.get(seitenPfad + ".md");
        assertThat(markdown).contains("[Quelle](/" + referenzPfad + ".md).[^q1] [^q2]");
        List<?> quellen = (List<?>) frontmatter(markdown).get("sources");
        assertThat(quellen).hasSize(2);
        Map<?, ?> dokument = (Map<?, ?>) quellen.get(0);
        Map<?, ?> seite = (Map<?, ?>) quellen.get(1);
        assertThat(dokument.get("id")).isEqualTo("q1");
        assertThat(dokument.get("resource")).isEqualTo("urn:urinet-archiv:urinet:dokument:" + dokumentId + "#page=2");
        assertThat(dokument.get("sha256")).isEqualTo("a".repeat(64));
        assertThat(dokument.get("seite")).isEqualTo("2");
        assertThat(dokument.get("auszug")).isEqualTo("Probe");
        assertThat(seite.get("id")).isEqualTo("q2");
        assertThat(seite.get("resource")).isEqualTo("/" + referenzPfad + ".md");
    }

    @Wenn("ein Vollexport des Mandanten «urinet» mit seiner Wissensbasis erstellt wird")
    public void vollexportErstellen() throws Exception {
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
        Path temp = Files.createTempDirectory("wissen-vollexport-");
        try {
            MandantCode code = new MandantCode("urinet");
            vollexport = CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> {
                ByteArrayOutputStream ziel = new ByteArrayOutputStream();
                try {
                    new MandantVollexportSchreiber(daten, ablage, wissenExport)
                            .schreiben(code, UUID.randomUUID(), temp, ziel);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return ziel.toByteArray();
            })).get(1, TimeUnit.MINUTES);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Dann("enthält der Vollexport ein Wissenspaket mit dieser Seite")
    public void vollexportPruefen() throws Exception {
        byte[] paket = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(vollexport))) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                if ("wissensbasis.zip".equals(eintrag.getName())) {
                    paket = zip.readAllBytes();
                }
            }
        }
        assertThat(paket).isNotNull();
        boolean gefunden = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(paket))) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                gefunden |= (seitenPfad + ".md").equals(eintrag.getName());
            }
        }
        assertThat(gefunden).isTrue();
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

    private Map<String, String> dateien() throws Exception {
        Map<String, String> dateien = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download()))) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                dateien.put(eintrag.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return dateien;
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
            try {
                try (Connection c = datenbank.get().getConnection();
                     PreparedStatement sql = c.prepareStatement("delete from m_urinet.wissensseite where id = ?")) {
                    for (UUID id : new UUID[]{seitenId, referenzId}) {
                        if (id != null) {
                            sql.setObject(1, id);
                            sql.executeUpdate();
                        }
                    }
                }
            } finally {
                try {
                    fremdeSeitenLoeschen();
                } finally {
                    if (dokumentId != null) {
                        Testdokumente.loeschen(datenbank.get(), "urinet", List.of(dokumentId));
                    }
                }
            }
        }
    }

    private void fremdeSeitenLoeschen() throws Exception {
        if (fremdeSeiten.isEmpty()) return;
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("delete from m_vhf_gsk.wissensseite where id = ?")) {
            for (UUID id : fremdeSeiten) {
                sql.setObject(1, id);
                sql.executeUpdate();
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
