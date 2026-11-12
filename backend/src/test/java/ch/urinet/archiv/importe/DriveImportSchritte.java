package ch.urinet.archiv.importe;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.driveimport.DriveImport;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zum Drive-Import (Spec 01, Requirement 12; Aufgabe 4.8). Das Werkzeug aus {@code tools/drive_import} läuft
 * in-process gegen das Archiv der Testinstanz; Ordner, Dateien und Tokens sind synthetisch und werden aufgeräumt.
 */
public class DriveImportSchritte {

    private static final long UEBER_50_MB = 50L * 1024 * 1024 + 1;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ApiTokenVerwaltung verwaltung;

    private Path verzeichnis;
    /** Pfad relativ zum Startordner → SHA-256 der Datei. */
    private final Map<String, String> pruefsummen = new LinkedHashMap<>();
    private String mandant;
    private String token;
    private Path tokenDatei;
    private final List<String> tokenIds = new ArrayList<>();
    private final List<String> tokenTexte = new ArrayList<>();
    private final Set<UUID> importiert = new LinkedHashSet<>();
    private String ausgabe = "";
    private String fehlerausgabe = "";
    private int exitCode = -1;

    @After
    public void aufraeumen() throws SQLException, IOException {
        if (mandant != null && !importiert.isEmpty()) {
            List<UUID> ids = List.copyOf(importiert);
            ausfuehren("delete from public.auftrag where typ in ('VERARBEITEN', 'KI_VORSCHLAG') "
                    + "and nutzlast->>'dokumentId' = any (string_to_array(?, ','))",
                    String.join(",", ids.stream().map(UUID::toString).toList()));
            Testdokumente.loeschen(datenbank.get(), mandant, ids);
            Set<String> ordner = new LinkedHashSet<>(ids.stream().map(id -> "/" + id + "/").toList());
            TestAblage.loeschen(mandant, "eingang/", s -> ordner.stream().anyMatch(s::contains));
            TestAblage.loeschen(mandant, "vorschau/", s -> ordner.stream().anyMatch(o -> s.startsWith("vorschau" + o)));
        }
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
        if (verzeichnis != null) {
            loeschen(verzeichnis);
        }
    }

    @Angenommen("ein Ordner «{word}» mit diesen synthetischen Dateien:")
    public void ordner(String name, DataTable tabelle) throws IOException {
        verzeichnis = Files.createTempDirectory("drive-import-");
        Path wurzel = verzeichnis.resolve(name);
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Path datei = wurzel.resolve(zeile.get("pfad"));
            Files.createDirectories(datei.getParent());
            byte[] inhalt = inhalt(zeile.get("typ"));
            Files.write(datei, inhalt);
            pruefsummen.put(zeile.get("pfad"), sha256(inhalt));
        }
    }

    @Angenommen("ausserhalb des Ordners liegen diese synthetischen Dateien:")
    public void ausserhalb(DataTable tabelle) throws IOException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Path datei = verzeichnis.resolve("ausserhalb").resolve(zeile.get("pfad"));
            Files.createDirectories(datei.getParent());
            byte[] inhalt = inhalt(zeile.get("typ"));
            Files.write(datei, inhalt);
            pruefsummen.put("ausserhalb/" + zeile.get("pfad"), sha256(inhalt));
        }
    }

    @Angenommen("im Ordner «{word}» liegen diese symbolischen Links nach ausserhalb:")
    public void links(String name, DataTable tabelle) throws IOException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Path link = verzeichnis.resolve(name).resolve(zeile.get("link"));
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, verzeichnis.resolve("ausserhalb").resolve(zeile.get("ziel")));
        }
    }

    @Angenommen("ein Token vom Scope «{word}» für «{word}» liegt in einer Datei mit Rechten {int}")
    public void tokenDatei(String scope, String mandantCode, int rechte) throws IOException {
        mandant = mandantCode;
        token = verwaltung.erstellen(mandantCode, "Probe Drive-Import " + scope, scope).token().text();
        tokenIds.add(ApiToken.lesen(token).orElseThrow().id());
        tokenTexte.add(token);
        tokenDatei = verzeichnis.resolve("token-" + tokenIds.size());
        Files.writeString(tokenDatei, token + "\n");
        Files.setPosixFilePermissions(tokenDatei, PosixFilePermissions.fromString(rechte == 600 ? "rw-------"
                : rechte == 644 ? "rw-r--r--" : "rwxrwxrwx"));
    }

    @Angenommen("der Drive-Import hat den Ordner «{}» schon einmal ins Archiv geladen")
    public void schonGeladen(String ordner) throws SQLException {
        laden(ordner);
        assertThat(exitCode).as(ausgabe + fehlerausgabe).isIn(DriveImport.OK, DriveImport.ABGELEHNT);
    }

    @Wenn("der Drive-Import den Ordner «{}» ins Archiv lädt")
    public void laden(String ordner) throws SQLException {
        starten("--archiv", "http://localhost:" + ConfigProvider.getConfig()
                .getValue("quarkus.http.test-port", Integer.class), "--token-datei", tokenDatei.toString(),
                verzeichnis.resolve(ordner).toString());
    }

    @Wenn("der Drive-Import den Ordner «{}» im Trockenlauf durchgeht")
    public void trockenlauf(String ordner) throws SQLException {
        mandant = "urinet";
        starten("--trockenlauf", "--token-datei", verzeichnis.resolve("fehlt").toString(),
                verzeichnis.resolve(ordner).toString());
    }

    @Dann("endet der Drive-Import mit Exit-Code {int}")
    public void exitCode(int erwartet) {
        assertThat(exitCode).as(ausgabe + fehlerausgabe).isEqualTo(erwartet);
    }

    @Dann("meldet der Drive-Import «{}»")
    public void meldet(String text) {
        assertThat(fehlerausgabe).contains(text);
    }

    @Dann("enthält die Ausgabe des Drive-Imports das Token nicht")
    public void ohneToken() {
        for (String text : tokenTexte) {
            assertThat(ausgabe + fehlerausgabe).doesNotContain(ApiToken.lesen(text).orElseThrow().geheimnis());
        }
    }

    @Dann("hat das Archiv im Mandanten «{word}» diese Dateien mit Quelle «{word}» vom Import-Token angelegt:")
    public void angelegt(String mandantCode, String quelle, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            List<Map<String, String>> dokumente = abfragen("select d.quelle, d.hinweis, d.erstellt_von from "
                    + schema(mandantCode) + ".dokument d join " + schema(mandantCode) + ".dokument_datei f on "
                    + "f.dokument_id = d.id where f.sha256 = ? and f.rolle = 'ORIGINAL'",
                    pruefsumme(zeile.get("pfad")));
            assertThat(dokumente).as(zeile.get("pfad")).singleElement().satisfies(d -> {
                assertThat(d).containsEntry("quelle", quelle).containsEntry("hinweis", zeile.get("hinweis"))
                        .containsEntry("erstellt_von", "token:" + tokenIds.getLast());
            });
        }
    }

    @Dann("nennt der Bericht des Drive-Imports diese Dateien:")
    public void berichtNennt(DataTable tabelle) {
        List<String> zeilen = ausgabe.lines().toList();
        for (Map<String, String> erwartet : tabelle.asMaps()) {
            Pattern muster = Pattern.compile("^" + Pattern.quote(erwartet.get("ergebnis") + ":") + "\\s+"
                    + Pattern.quote(erwartet.get("pfad")) + "(\\s.*)?$");
            String grund = erwartet.get("grund") == null ? "" : erwartet.get("grund");
            assertThat(zeilen).as(ausgabe).anySatisfy(z -> {
                assertThat(z).matches(muster);
                assertThat(z).contains(grund);
            });
        }
    }

    @Dann("steht unter «{}» im Bericht des Drive-Imports genau:")
    public void abschnitt(String titel, DataTable tabelle) {
        List<String> eintraege = abschnitt(titel);
        List<Map<String, String>> erwartet = tabelle.asMaps();
        assertThat(eintraege).as(ausgabe).hasSize(erwartet.size());
        for (Map<String, String> zeile : erwartet) {
            String grund = zeile.get("grund");
            assertThat(eintraege).as(ausgabe).anySatisfy(e -> {
                assertThat(e).startsWith(zeile.get("eintrag"));
                if (grund == null) {
                    assertThat(e).isEqualTo(zeile.get("eintrag"));
                } else {
                    assertThat(e).startsWith(zeile.get("eintrag") + " – ").contains(grund);
                }
            });
        }
    }

    @Dann("geht der Schlussbericht auf: {int} gefunden = {int} übertragen + {int} schon vorhanden + {int} "
            + "übersprungen + {int} abgelehnt")
    public void bilanz(int gefunden, int uebertragen, int vorhanden, int uebersprungen, int abgelehnt) {
        assertThat(gefunden).isEqualTo(uebertragen + vorhanden + uebersprungen + abgelehnt);
        assertThat(ausgabe).contains("Gefunden:        " + gefunden, "Übertragen:      " + uebertragen,
                "Schon vorhanden: " + vorhanden, "Übersprungen:    " + uebersprungen, "Abgelehnt:       " + abgelehnt,
                "Kontrolle: " + gefunden + " = " + uebertragen + " + " + vorhanden + " + " + uebersprungen + " + "
                        + abgelehnt + " (geht auf)");
    }

    @Dann("geht der Bericht des Trockenlaufs auf: {int} gefunden = {int} würde übertragen + {int} übersprungen + "
            + "{int} abgelehnt")
    public void bilanzTrockenlauf(int gefunden, int wuerde, int uebersprungen, int abgelehnt) {
        assertThat(gefunden).isEqualTo(wuerde + uebersprungen + abgelehnt);
        assertThat(ausgabe).contains("Schlussbericht (Trockenlauf: nichts übertragen)",
                "Kontrolle: " + gefunden + " = " + wuerde + " + " + uebersprungen + " + " + abgelehnt + " (geht auf)");
    }

    @Dann("ist im Mandanten «{word}» keine Datei des Ordners gespeichert")
    public void nichtsGespeichert(String mandantCode) throws SQLException {
        gespeichert(mandantCode, Set.of());
    }

    @Dann("hat das Archiv im Mandanten «{word}» nur diese Dateien des Ordners gespeichert:")
    public void nurGespeichert(String mandantCode, DataTable tabelle) throws SQLException {
        gespeichert(mandantCode, Set.copyOf(tabelle.asMaps().stream().map(z -> z.get("pfad")).toList()));
    }

    @Dann("hat das Archiv im Mandanten «{word}» je Datei des Ordners genau ein Dokument")
    public void jeDateiEinDokument(String mandantCode) throws SQLException {
        for (Map.Entry<String, String> datei : pruefsummen.entrySet()) {
            assertThat(abfragen("select dokument_id from " + schema(mandantCode) + ".dokument_datei where sha256 = ? "
                    + "and rolle = 'ORIGINAL'", datei.getValue())).as(datei.getKey()).hasSize(1);
        }
        assertThat(importiert).hasSize(pruefsummen.size());
    }

    @Dann("steht jedes importierte Dokument im Status «{word}» als «{word}», unbestätigt und nicht archiviert")
    public void imEingangskorbAblauf(String status, String vertraulichkeit) throws SQLException {
        assertThat(importiert).isNotEmpty();
        for (UUID id : importiert) {
            Map<String, String> zeile = abfragen("select status, vertraulichkeit, bestaetigt, archiviert, sperre_bis, "
                    + "quelle from " + schema(mandant) + ".dokument where id = ?::uuid", id.toString()).getFirst();
            assertThat(zeile).containsEntry("status", status).containsEntry("vertraulichkeit", vertraulichkeit)
                    .containsEntry("bestaetigt", null).containsEntry("archiviert", null)
                    .containsEntry("sperre_bis", null).containsEntry("quelle", "DRIVE");
        }
    }

    @Dann("ist für jedes importierte Dokument genau ein Auftrag «{word}» eingereiht")
    public void auftrag(String typ) throws SQLException {
        assertThat(importiert).isNotEmpty();
        for (UUID id : importiert) {
            assertThat(abfragen("select status from public.auftrag where typ = ? and mandant = ? "
                    + "and nutzlast->>'dokumentId' = ?", typ, mandant, id.toString())).as(id.toString())
                    .singleElement().satisfies(a -> assertThat(a).containsEntry("status", "OFFEN"));
        }
    }

    @Dann("liegt jede importierte Datei unter «eingang\\/» und keine unter «archiv\\/» im Bucket von «{word}»")
    public void nurImEingang(String mandantCode) throws SQLException {
        assertThat(importiert).isNotEmpty();
        List<String> archiv = TestAblage.versionen(mandantCode, "archiv/");
        for (UUID id : importiert) {
            for (Map<String, String> datei : abfragen("select s3_schluessel from " + schema(mandantCode)
                    + ".dokument_datei where dokument_id = ?::uuid", id.toString())) {
                assertThat(datei.get("s3_schluessel")).startsWith("eingang/").contains("/" + id + "/");
            }
            assertThat(archiv).noneMatch(schluessel -> schluessel.contains(id.toString()));
        }
    }

    private void starten(String... args) throws SQLException {
        StringWriter aus = new StringWriter();
        StringWriter fehler = new StringWriter();
        exitCode = DriveImport.ausfuehren(new PrintWriter(aus), new PrintWriter(fehler), Map.of(), args);
        ausgabe = aus.toString();
        fehlerausgabe = fehler.toString();
        for (String id : tokenIds) {
            for (Map<String, String> zeile : abfragen("select id::text as id from " + schema(mandant)
                    + ".dokument where erstellt_von = ?", "token:" + id)) {
                importiert.add(UUID.fromString(zeile.get("id")));
            }
        }
    }

    private List<String> abschnitt(String titel) {
        List<String> eintraege = new ArrayList<>();
        boolean drin = false;
        for (String zeile : ausgabe.lines().toList()) {
            if (zeile.startsWith(titel + " (")) {
                drin = true;
            } else if (drin && zeile.startsWith("  ")) {
                eintraege.add(zeile.substring(2));
            } else if (drin) {
                break;
            }
        }
        assertThat(drin).as(ausgabe).isTrue();
        return eintraege;
    }

    private void gespeichert(String mandantCode, Set<String> erwartet) throws SQLException {
        for (Map.Entry<String, String> datei : pruefsummen.entrySet()) {
            assertThat(abfragen("select dokument_id from " + schema(mandantCode) + ".dokument_datei where sha256 = ?",
                    datei.getValue())).as(datei.getKey()).hasSize(erwartet.contains(datei.getKey()) ? 1 : 0);
        }
    }

    private String pruefsumme(String pfad) {
        assertThat(pruefsummen).containsKey(pfad);
        return pruefsummen.get(pfad);
    }

    private static byte[] inhalt(String typ) {
        return switch (typ) {
            case "pdf-zu-gross" -> Testdateien.pdf(UEBER_50_MB);
            case "gdoc", "gsheet" -> ("{\"url\":\"https://docs.example/" + UUID.randomUUID() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            case "docx", "xlsx", "zip" -> zip();
            case "eml" -> ("From: probe@example.invalid\r\nSubject: Probe " + UUID.randomUUID() + "\r\n\r\nText\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            default -> Testdateien.erzeugen(typ);
        };
    }

    /** Leeres ZIP (End-of-Central-Directory) mit Zufallskommentar; Office-Dateien sind ebenfalls ZIP. */
    private static byte[] zip() {
        byte[] kommentar = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
        byte[] zip = new byte[22 + kommentar.length];
        zip[0] = 'P';
        zip[1] = 'K';
        zip[2] = 5;
        zip[3] = 6;
        zip[20] = (byte) kommentar.length;
        System.arraycopy(kommentar, 0, zip, 22, kommentar.length);
        return zip;
    }

    private static String sha256(byte[] inhalt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String schema(String mandantCode) {
        return new MandantCode(mandantCode).schema();
    }

    private static void loeschen(Path pfad) throws IOException {
        Files.walkFileTree(pfad, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) throws IOException {
                Files.delete(datei);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path ordner, IOException e) throws IOException {
                Files.delete(ordner);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void ausfuehren(String sql, String... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setString(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }

    private List<Map<String, String>> abfragen(String sql, String... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setString(i + 1, werte[i]);
            }
            List<Map<String, String>> zeilen = new ArrayList<>();
            try (ResultSet zeile = befehl.executeQuery()) {
                int spalten = zeile.getMetaData().getColumnCount();
                while (zeile.next()) {
                    Map<String, String> werteDerZeile = new LinkedHashMap<>();
                    for (int s = 1; s <= spalten; s++) {
                        werteDerZeile.put(zeile.getMetaData().getColumnLabel(s), zeile.getString(s));
                    }
                    zeilen.add(werteDerZeile);
                }
            }
            return zeilen;
        }
    }
}
