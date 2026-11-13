package ch.urinet.archiv.dokument;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Aufgabe 4.7 (Req 11.1–11.11): {@code POST /api/v1/dokumente/generator} mit einem API-Token vom Scope
 * {@code generator}, Verarbeitung und Archivierung über den Worker; für Req 11.4, 11.5 und 11.9 ruft ein Python-Prozess
 * den echten Client {@code tools/archiv_client.py} gegen den Testserver auf. Jede PDF ist eine frische synthetische
 * Text-PDF (Req 16.15); dieselbe Bezeichnung im Szenario heisst dieselben Bytes.
 */
public class GeneratorSchritte {

    private static final String PFAD = "/api/v1/dokumente/generator";
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    private static final Path TOOLS = Path.of("..", "tools").toAbsolutePath().normalize();
    /** Der Einzeiler, mit dem ein Skript ablegt (Req 11.4); gibt Zustand und ID aus. */
    private static final String EINZEILER = "import json, sys, archiv_client as a; "
            + "e = a.archivieren(sys.argv[1], json.loads(sys.argv[2])); print(e.zustand, e.id or '')";
    private static final String MIT_FEHLER = "import json, sys, archiv_client as a\n"
            + "try:\n    a.archivieren(sys.argv[1], json.loads(sys.argv[2]))\n"
            + "except a.ArchivFehler as f:\n    print('FEHLER', f.status, f.code)\n";

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    VerarbeitungTestHilfe verarbeitung;

    @Inject
    ApiTokenVerwaltung tokens;

    @Inject
    AblagePort ablage;

    private final Map<String, byte[]> pdfs = new HashMap<>();
    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<String, String> tokenJeMandant = new HashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private final List<Ort> kontakte = new ArrayList<>();
    private final Set<String> clientPdfs = new LinkedHashSet<>();
    private String mandant;
    private Response antwort;
    private Path skript;
    private String clientZustand;
    private String clientId;

    @After
    public void aufraeumen() throws Exception {
        for (String pdf : clientPdfs) {
            for (String id : dokumenteZurPdf(mandant, pdf)) {
                dokumente.putIfAbsent("client " + id, new Ort(mandant, UUID.fromString(id)));
            }
        }
        if (skript != null) {
            try (Stream<Path> pfade = Files.walk(skript)) {
                for (Path pfad : pfade.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(pfad);
                }
            }
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        for (Ort ort : dokumente.values()) {
            ausfuehren("delete from public.auftrag where nutzlast ->> 'dokumentId' = ?", ort.id().toString());
            Map<String, Object> z = zeile(ort, "status, eingang");
            if (!z.isEmpty() && !"ARCHIVIERT".equals(z.get("status"))) {
                int jahr = ((Timestamp) z.get("eingang")).toInstant().atZone(ZUERICH).getYear();
                ablage.loeschenEingang(new MandantCode(ort.mandant()), jahr, ort.id());
            }
            jeMandant.computeIfAbsent(ort.mandant(), m -> new ArrayList<>()).add(ort.id());
        }
        for (Map.Entry<String, List<UUID>> e : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), e.getKey(), e.getValue());
        }
        for (Ort kontakt : kontakte) {
            ausfuehren("delete from " + schema(kontakt.mandant()) + ".kontakt where id = ?", kontakt.id());
        }
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
    }

    @Angenommen("der Generator ist bereit")
    public void bereit() throws Exception {
        verarbeitung.vorbereiten();
        ausfuehren("delete from public.auftrag where typ = 'ARCHIVIEREN'");
    }

    @Angenommen("der Generator von «{word}» hat ein API-Token")
    public void token(String code) {
        ApiTokenVerwaltung.Erstellt erstellt = tokens.erstellen(code, "Probe Generator", "generator");
        tokenJeMandant.put(code, erstellt.token().text());
        tokenIds.add(erstellt.token().id());
        mandant = code;
    }

    @Angenommen("im Mandanten «{word}» gibt es den CRM-Kontakt «{}» mit der crmId «{word}»")
    public void kontakt(String code, String name, String crmId) throws SQLException {
        UUID id = UUID.randomUUID();
        ausfuehren("delete from " + schema(code) + ".kontakt where crm_id = ?", crmId);
        ausfuehren("insert into " + schema(code) + ".kontakt (id, typ, name, name_normalisiert, quelle, crm_id) "
                + "values (?, 'ORGANISATION', ?, ?, 'TEST', ?)", id, name, name.toLowerCase(), crmId);
        kontakte.add(new Ort(code, id));
    }

    @Wenn("der Generator die PDF «{word}» mit diesen Metadaten sendet:")
    public void senden(String pdf, String metadaten) {
        antwort = senden(given().header("Authorization", "Bearer " + tokenJeMandant.get(mandant)), pdf, metadaten);
    }

    @Wenn("«{word}» die PDF «{word}» mit diesen Metadaten an den Generator sendet:")
    public void sendenAlsBenutzer(String benutzer, String pdf, String metadaten) {
        antwort = senden(given().auth().oauth2(Testanmeldung.token(benutzer)), pdf, metadaten);
    }

    @Angenommen("die Verarbeitung des Generator-Dokuments «{word}» ist gescheitert")
    public void gescheitert(String name) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("delete from public.auftrag where nutzlast ->> 'dokumentId' = ?", ort.id().toString());
        ausfuehren("update " + schema(ort.mandant()) + ".dokument set status = 'FEHLER', "
                + "fehler = 'Probe: Verarbeitung gescheitert' where id = ?", ort.id());
    }

    @Wenn("«{word}» das Generator-Dokument «{word}» verwirft")
    public void verwerfen(String benutzer, String name) throws SQLException {
        Ort ort = ort(name);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .header("If-Match", "\"" + zeile(ort, "version").get("version") + "\"")
                .contentType(ContentType.JSON).body(Map.of("grund", "Probe: falscher Betrag"))
                .when().post("/api/v1/dokumente/" + ort.id() + "/verwerfen");
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(204);
    }

    @Wenn("der Worker die Generator-Aufträge abarbeitet")
    public void abarbeiten() throws Exception {
        verarbeitung.abarbeiten();
    }

    @Dann("antwortet der Generator mit 202 und dem neuen Dokument «{word}»")
    public void neu(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        assertThat(antwort.<String>path("status")).isEqualTo("NEU");
        UUID id = UUID.fromString(antwort.path("id"));
        assertThat(antwort.header("Location")).endsWith("/api/v1/dokumente/" + id);
        assertThat(dokumente.values()).extracting(Ort::id).doesNotContain(id);
        dokumente.put(name, new Ort(mandant, id));
    }

    @Dann("antwortet der Generator mit 200 und dem vorhandenen Dokument «{word}» im Status «{word}»")
    public void vorhanden(String name, String status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("id")).isEqualTo(ort(name).id().toString());
        assertThat(antwort.<String>path("status")).isEqualTo(status);
        assertThat(antwort.header("Location")).isNull();
    }

    @Dann("antwortet der Generator mit Status {int} und Fehlercode «{word}»")
    public void fehler(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status, "felder")).as(antwort.asString()).isEqualTo(code);
    }

    @Dann("nennt die Generator-Antwort die fehlenden Felder {string}")
    public void felder(String felder) {
        assertThat(antwort.jsonPath().<String>getList("felder"))
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(felder.split(",\\s*")));
    }

    @Dann("gibt es im Mandanten «{word}» kein Dokument zur PDF «{word}»")
    public void keinDokument(String code, String pdf) throws Exception {
        assertThat(dokumenteZurPdf(code, pdf)).isEmpty();
    }

    @Dann("gibt es im Mandanten «{word}» genau ein Dokument zur PDF «{word}»")
    public void einDokument(String code, String pdf) throws Exception {
        assertThat(dokumenteZurPdf(code, pdf)).hasSize(1);
    }

    /** Spalten von {@code dokument} als Text; {@code null} heisst leer, {@code TOKEN} der Principal des Tokens. */
    @Dann("hat das Generator-Dokument «{word}» diese Werte:")
    public void werte(String name, Map<String, String> erwartet) throws SQLException {
        Ort ort = ort(name);
        String principal = ApiToken.principal(ApiToken.lesen(tokenJeMandant.get(ort.mandant())).orElseThrow().id());
        for (Map.Entry<String, String> e : erwartet.entrySet()) {
            String wert = "TOKEN".equals(e.getValue()) ? principal : e.getValue() == null ? "null" : e.getValue();
            assertThat(String.valueOf(zeile(ort, e.getKey()).get(e.getKey()))).as(e.getKey()).isEqualTo(wert);
        }
    }

    /**
     * Req 10.2: {@code ERFASSEN} einmal je Anfrage, mit dem Principal des Tokens; ohne Dokument-ID, weil der Eintrag vor
     * der Aktion entsteht (design.md «Protokoll und Manifest»), ohne Rolle wie jeder Token-Eintrag (Check 002).
     */
    @Dann("steht im Zugriffsprotokoll von «{word}» {int} Mal «{word}» vom Generator, ohne Rolle und Dokument-ID")
    public void protokolliert(String mandant, int anzahl, String aktion) throws SQLException {
        String principal = ApiToken.principal(ApiToken.lesen(tokenJeMandant.get(mandant)).orElseThrow().id());
        assertThat(abfragen("select count(*) from " + schema(mandant) + ".zugriff_protokoll where benutzer = ? "
                + "and aktion = ? and rolle is null and dokument_id is null", principal, aktion))
                .containsExactly(String.valueOf(anzahl));
        assertThat(abfragen("select count(*) from " + schema(mandant) + ".zugriff_protokoll where benutzer = ? "
                + "and aktion = ?", principal, aktion)).containsExactly(String.valueOf(anzahl));
    }

    @Dann("ist für das Generator-Dokument «{word}» ein Auftrag «VERARBEITEN» ohne KI eingereiht")
    public void ohneKiEingereiht(String name) throws SQLException {
        assertThat(abfragen("select nutzlast ->> 'ohneKi' as ohne_ki from public.auftrag where typ = 'VERARBEITEN' "
                + "and status = 'OFFEN' and nutzlast ->> 'dokumentId' = ?", ort(name).id().toString()))
                .singleElement().isEqualTo("true");
    }

    @Dann("ist das Generator-Dokument «{word}» archiviert, ohne KI-Vorschlag")
    public void archiviert(String name) throws SQLException {
        Ort ort = ort(name);
        Map<String, Object> z = zeile(ort, "status, archiviert, s3_pdfa, sperre_bis, eingang");
        assertThat(z.get("status")).isEqualTo("ARCHIVIERT");
        assertThat(z.get("archiviert")).isNotNull();
        assertThat(z.get("s3_pdfa")).asString().startsWith("archiv/");
        assertThat(z.get("sperre_bis")).isNotNull();
        assertThat(verarbeitung.auftraege("KI_VORSCHLAG", ort.id())).isEmpty();
        int jahr = ((Timestamp) z.get("eingang")).toInstant().atZone(ZUERICH).getYear();
        assertThat(verarbeitung.objektversionen(ort.mandant(), "archiv/" + jahr + "/" + ort.id() + "/")).isNotEmpty();
    }

    @Dann("ist das Generator-Dokument «{word}» im Status «{word}»")
    public void status(String name, String status) throws SQLException {
        assertThat(zeile(ort(name), "status").get("status")).isEqualTo(status);
    }

    @Wenn("das Skript die PDF «{word}» mit dem Python-Client und diesen Metadaten ablegt:")
    public void client(String pdf, String metadaten) throws Exception {
        clientAufrufen(pdf, metadaten, "http://localhost:" + RestAssured.port, EINZEILER);
    }

    @Wenn("das Skript die PDF «{word}» mit dem Python-Client und diesen unvollständigen Metadaten ablegt:")
    public void clientMitFehler(String pdf, String metadaten) throws Exception {
        clientAufrufen(pdf, metadaten, "http://localhost:" + RestAssured.port, MIT_FEHLER);
    }

    @Wenn("das Skript bei nicht erreichbarem Archiv die PDF «{word}» mit dem Python-Client und diesen Metadaten ablegt:")
    public void clientOhneArchiv(String pdf, String metadaten) throws Exception {
        int port;
        try (ServerSocket frei = new ServerSocket(0)) {
            port = frei.getLocalPort();
        }
        clientAufrufen(pdf, metadaten, "http://127.0.0.1:" + port, EINZEILER);
    }

    @Dann("meldet der Python-Client «{word}»")
    public void clientMeldet(String zustand) {
        assertThat(clientZustand).isEqualTo(zustand);
    }

    @Dann("meldet der Python-Client den Fehler {int} «{word}»")
    public void clientFehler(int status, String fehlercode) {
        assertThat(clientZustand + " " + clientId).isEqualTo("FEHLER " + status + " " + fehlercode);
    }

    @Dann("meldet der Python-Client «{word}» für das Dokument «{word}»")
    public void clientMeldetDokument(String zustand, String name) {
        clientMeldet(zustand);
        dokumente.put(name, new Ort(mandant, UUID.fromString(clientId)));
    }

    @Dann("liegt die PDF «{word}» im Ordner «archiv-ausstehend» des Skripts")
    public void ausstehend(String pdf) throws IOException {
        assertThat(Files.readAllBytes(skript.resolve("archiv-ausstehend").resolve(pdf + ".pdf"))).isEqualTo(pdfs.get(pdf));
        assertThat(skript.resolve("archiv-ausstehend").resolve(pdf + ".json")).isRegularFile();
    }

    @Dann("liegt im Ordner «archiv-ausstehend» des Skripts keine PDF mehr")
    public void nichtsAusstehend() throws IOException {
        try (Stream<Path> pfade = Files.list(skript.resolve("archiv-ausstehend"))) {
            assertThat(pfade.toList()).isEmpty();
        }
    }

    @Dann("liegt die PDF «{word}» im Ordner «archiv-konflikt» des Skripts und nicht mehr neben dem Skript")
    public void konflikt(String pdf) throws IOException {
        assertThat(Files.readAllBytes(skript.resolve("archiv-konflikt").resolve(pdf + ".pdf"))).isEqualTo(pdfs.get(pdf));
        assertThat(skript.resolve(pdf + ".pdf")).doesNotExist();
        assertThat(skript.resolve("archiv-ausstehend")).doesNotExist();
    }

    private void clientAufrufen(String pdf, String metadaten, String url, String python) throws Exception {
        if (skript == null) {
            skript = Files.createTempDirectory("generator-skript");
        }
        Path tokenDatei = skript.resolve(".token");
        if (!Files.exists(tokenDatei)) {
            Files.createFile(tokenDatei, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tokenDatei, tokenJeMandant.get(mandant));
        }
        Path datei = skript.resolve(pdf + ".pdf");
        Files.write(datei, pdf(pdf));
        clientPdfs.add(pdf);
        Path fehler = skript.resolve(".fehler");
        ProcessBuilder aufruf = new ProcessBuilder("python3", "-c", python, datei.toString(), metadaten)
                .redirectError(fehler.toFile());
        Map<String, String> umgebung = aufruf.environment();
        umgebung.put("PYTHONPATH", TOOLS.toString());
        umgebung.put("PYTHONDONTWRITEBYTECODE", "1");
        umgebung.put("ARCHIV_URL", url);
        umgebung.put("ARCHIV_TOKEN_DATEI", tokenDatei.toString());
        umgebung.put("NO_PROXY", "localhost,127.0.0.1");
        umgebung.put("no_proxy", "localhost,127.0.0.1");
        Process prozess = aufruf.start();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(prozess.waitFor(60, TimeUnit.SECONDS)).as("Python-Client beendet").isTrue();
        assertThat(prozess.exitValue()).as("Exit-Code des Python-Clients: " + Files.readString(fehler)).isZero();
        String[] teile = ausgabe.split(" ", 2);
        clientZustand = teile[0];
        clientId = teile.length > 1 ? teile[1].strip() : "";
    }

    private byte[] pdf(String pdf) {
        return pdfs.computeIfAbsent(pdf, n -> OcrTestdateien.textPdf("Synthetischer Generator-Beleg " + n,
                "Kennung " + UUID.randomUUID()));
    }

    private Response senden(RequestSpecification anfrage, String pdf, String metadaten) {
        byte[] inhalt = pdf(pdf);
        return anfrage.multiPart("datei", pdf + ".pdf", inhalt, "application/pdf")
                .multiPart("metadaten", metadaten).when().post(PFAD);
    }

    private List<String> dokumenteZurPdf(String code, String pdf) throws Exception {
        String sha = VerarbeitungTestHilfe.sha256(pdfs.get(pdf));
        return abfragen("select dokument_id::text from " + schema(code) + ".dokument_datei where sha256 = ? "
                + "and rolle = 'ORIGINAL'", sha);
    }

    private Ort ort(String name) {
        Ort ort = dokumente.get(name);
        if (ort == null) {
            throw new IllegalArgumentException("Unbekanntes Generator-Dokument: " + name);
        }
        return ort;
    }

    private Map<String, Object> zeile(Ort ort, String spalten) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select " + spalten + " from " + schema(ort.mandant()) + ".dokument where id = ?")) {
            abfrage.setObject(1, ort.id());
            try (ResultSet z = abfrage.executeQuery()) {
                Map<String, Object> zeile = new HashMap<>();
                if (z.next()) {
                    for (int i = 1; i <= z.getMetaData().getColumnCount(); i++) {
                        zeile.put(z.getMetaData().getColumnLabel(i), z.getObject(i));
                    }
                }
                return zeile;
            }
        }
    }

    private List<String> abfragen(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                abfrage.setObject(i + 1, werte[i]);
            }
            List<String> ergebnis = new ArrayList<>();
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    ergebnis.add(z.getString(1));
                }
            }
            return ergebnis;
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

    private static String schema(String code) {
        return new MandantCode(code).schema();
    }
}
