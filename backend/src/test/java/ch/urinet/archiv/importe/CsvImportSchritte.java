package ch.urinet.archiv.importe;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.runtime.configuration.MemorySize;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu den Aufgaben 4.1 bis 4.3 (Req 2.18, 13.1–13.11): Upload über {@code POST /api/v1/importe/crm},
 * Zuordnung über {@code GET}/{@code PUT /api/v1/importe/crm/zuordnung}, Vorschau über {@code GET /api/v1/importe/{id}},
 * Übernahme über {@code POST /api/v1/importe/{id}/uebernehmen} und Protokoll über {@code GET /api/v1/importe}. Die Beispieldateien stammen aus Aufgabe 0.4;
 * ihre Werte werden nur verglichen, nie ausgegeben.
 */
public class CsvImportSchritte {

    private static final String UPLOAD = "/api/v1/importe/crm";
    private static final String ZUORDNUNG = "/api/v1/importe/crm/zuordnung";
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final List<String> SCHEMAS = List.of(new MandantCode("urinet").schema(),
            new MandantCode("vhf-gsk").schema());

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    S3Client s3;

    private Response antwort;
    private String laufId;
    private Map<String, Object> vorschlag;
    private byte[] hochgeladen;
    private LogMitschnitt mitschnitt;
    private boolean benutzt;
    private int erwarteteZeilen;
    private final Map<String, UUID> kontakte = new LinkedHashMap<>();

    @After
    public void aufraeumen() throws SQLException {
        if (mitschnitt != null) {
            mitschnitt.close();
            mitschnitt = null;
        }
        if (!benutzt) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String schema : SCHEMAS) {
                for (String tabelle : List.of("import_zeile", "import_lauf", "import_zuordnung", "verkaufschance")) {
                    try (PreparedStatement loeschen = verbindung.prepareStatement(
                            "delete from " + schema + "." + tabelle)) {
                        loeschen.executeUpdate();
                    }
                }
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + schema + ".kontakt where id = any(?)")) {
                    loeschen.setArray(1, verbindung.createArrayOf("uuid", kontakte.values().toArray()));
                    loeschen.executeUpdate();
                }
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + schema + ".kontakt where quelle = 'CSV_IMPORT'")) {
                    loeschen.executeUpdate();
                }
            }
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "delete from public.auftrag where typ in ('CSV_VORSCHAU', 'CSV_UEBERNEHMEN')")) {
                loeschen.executeUpdate();
            }
        }
        kontakte.clear();
    }

    @Angenommen("im Mandanten «{word}» gibt es diese Kontakte für den Abgleich:")
    public void kontakteFuerAbgleich(String mandant, DataTable tabelle) throws SQLException {
        benutzt = true;
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into " + new MandantCode(mandant).schema() + ".kontakt (id, crm_id, "
                        + "typ, name, vorname, name_normalisiert, plz, email, quelle, nicht_im_export) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, 'MANUELL', ?)")) {
            for (Map<String, String> zeile : tabelle.asMaps()) {
                UUID id = UUID.randomUUID();
                boolean person = "PERSON".equals(zeile.get("typ"));
                einfuegen.setObject(1, id);
                einfuegen.setString(2, zeile.get("crm_id"));
                einfuegen.setString(3, zeile.get("typ"));
                einfuegen.setString(4, zeile.get("name"));
                einfuegen.setString(5, zeile.get("vorname"));
                einfuegen.setString(6, person ? Normalisierer.personName(zeile.get("vorname"), zeile.get("name"))
                        : Normalisierer.name(zeile.get("name")));
                einfuegen.setString(7, zeile.get("plz"));
                einfuegen.setString(8, zeile.get("email"));
                einfuegen.setBoolean(9, Boolean.parseBoolean(zeile.get("nicht_im_export")));
                einfuegen.executeUpdate();
                kontakte.put(zeile.get("kontakt"), id);
            }
        }
    }

    @Wenn("«{word}» den Lauf abfragt")
    public void laufAbfragen(String benutzer) {
        seiteAbfragen(benutzer, 0, 100);
    }

    @Wenn("«{word}» den Lauf «{}» abfragt")
    public void andererLauf(String benutzer, String id) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(UPLOAD.replace("/crm", "/") + id);
    }

    @Dann("steht der Lauf auf «{word}» ohne Zähler der Vorschau")
    public void ohneVorschau(String status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        String pfad = antwort.<Object>path("lauf") == null ? "" : "lauf.";
        assertThat(antwort.<String>path(pfad + "status")).isEqualTo(status);
        assertThat(List.of("neu", "geaendert", "unveraendert", "dubletten"))
                .allSatisfy(feld -> assertThat(antwort.<Object>path(pfad + feld)).as(feld).isNull());
    }

    @Dann("sind für den Lauf {int} Aufträge «{word}» eingereiht")
    public void auftraege(int anzahl, String typ) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from public.auftrag where typ = ? "
                        + "and nutzlast ->> 'laufId' = ?")) {
            abfrage.setString(1, typ);
            abfrage.setString(2, laufId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getInt(1)).isEqualTo(anzahl);
            }
        }
    }

    @Wenn("«{word}» die Seite {int} mit {int} Zeilen des Laufs abfragt")
    public void seiteAbfragen(String benutzer, int seite, int groesse) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("seite", seite)
                .queryParam("groesse", groesse).when().get(UPLOAD.replace("/crm", "/") + laufId);
    }

    @Dann("zeigt die Vorschau {int} neu, {int} geändert, {int} unverändert, {int} mögliche Dubletten, {int} fehlerhaft "
            + "und {int} übersprungen")
    public void vorschau(int neu, int geaendert, int unveraendert, int dubletten, int fehler, int uebersprungen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("lauf.status")).isEqualTo("VORSCHAU");
        assertThat(List.of(antwort.<Integer>path("lauf.neu"), antwort.<Integer>path("lauf.geaendert"),
                antwort.<Integer>path("lauf.unveraendert"), antwort.<Integer>path("lauf.dubletten"),
                antwort.<Integer>path("lauf.fehler"), antwort.<Integer>path("lauf.uebersprungen")))
                .containsExactly(neu, geaendert, unveraendert, dubletten, fehler, uebersprungen);
    }

    @Dann("zeigt die Vorschau diese Zeilen:")
    public void vorschauZeilen(DataTable erwartet) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        Map<UUID, String> kennung = new HashMap<>();
        kontakte.forEach((name, id) -> kennung.put(id, name));
        List<Map<String, Object>> ist = antwort.path("eintraege");
        List<Map<String, String>> soll = erwartet.asMaps();
        assertThat(ist).hasSameSizeAs(soll);
        for (int i = 0; i < soll.size(); i++) {
            Map<String, String> s = soll.get(i);
            Map<String, Object> z = ist.get(i);
            String nr = s.get("nr");
            assertThat(String.valueOf(z.get("nr"))).isEqualTo(nr);
            assertThat(z.get("ergebnis")).as("Zeile %s", nr).isEqualTo(s.get("ergebnis"));
            assertThat(nullAlsLeer((String) z.get("meldung"))).as("Zeile %s", nr)
                    .isEqualTo(nullAlsLeer(s.get("meldung")));
            String kontakt = z.get("kontaktId") == null ? "" : kennung.get(UUID.fromString((String) z.get("kontaktId")));
            assertThat(kontakt).as("Zeile %s", nr).isEqualTo(nullAlsLeer(s.get("kontakt")));
            @SuppressWarnings("unchecked")
            List<String> kandidaten = ((List<String>) z.get("kandidaten")).stream()
                    .map(id -> kennung.getOrDefault(UUID.fromString(id), "?")).sorted().toList();
            assertThat(String.join(", ", kandidaten)).as("Zeile %s", nr).isEqualTo(nullAlsLeer(s.get("kandidaten")));
        }
    }

    @Dann("enthält die Seite {int} von {int} Zeilen die Zeilen {int} bis {int}")
    public void seite(int seite, int gesamt, int von, int bis) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<Integer>path("seite")).isEqualTo(seite);
        assertThat(antwort.<Integer>path("gesamt")).isEqualTo(gesamt);
        assertThat(antwort.<List<Integer>>path("eintraege.nr"))
                .containsExactlyElementsOf(IntStream.rangeClosed(von, bis).boxed().toList());
    }

    @Dann("enthält das Log keinen der Werte «{}» und «{}»")
    public void logOhneVorschauWerte(String a, String b) {
        assertThat(mitschnitt.volltext()).doesNotContain(a).doesNotContain(b);
    }

    @Angenommen("der CSV-Import schneidet das Log mit")
    public void logMitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Wenn("«{word}» die Beispieldatei «{word}» als Importart «{word}» hochlädt")
    public void beispielHochladen(String benutzer, String datei, String art) {
        hochladen(benutzer, art, "false", datei, datei("csv/" + datei));
    }

    @Wenn("«{word}» die Kontakt-Beispieldatei in der Variante «{word}» hochlädt")
    public void varianteHochladen(String benutzer, String variante) {
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", variante(variante));
    }

    @Wenn("«{word}» die Kontakt-Beispieldatei mit umbenannter letzter Spalte hochlädt")
    public void umbenannt(String benutzer) {
        String text = new String(datei("csv/21crm-kontakte-beispiel.csv"), CP1252);
        int kopfEnde = text.indexOf('\n');
        String kopf = text.substring(0, kopfEnde);
        String neu = kopf.substring(0, kopf.lastIndexOf(';') + 1) + "Neue Spalte" + text.substring(kopfEnde);
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", neu.getBytes(CP1252));
    }

    @Wenn("«{word}» diese Kontakt-CSV hochlädt:")
    public void textHochladen(String benutzer, String text) {
        hochladen(benutzer, "kontakt", "false", "kontakte.csv",
                text.replace("{RIESIG}", "x".repeat(10_001)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Steuerzeichen wiegen in JSON 6 Zeichen ({@code CsvTabelle.jsonZeichen}), so passen zwei Zeilen an der Grenze von
     * 262'144 in die Grenze des Profils {@code test} (512 KiB). Je Feld höchstens 8'738 Zeichen (Feldgrenze 10'000).
     */
    @Wenn("«{word}» eine Kontakt-CSV mit einer Zeile genau an der Zeilengrenze und einer Zeile darüber hochlädt")
    public void zeilengrenzeHochladen(String benutzer) {
        String name = "Grenze AG";
        int rest = CsvTabelle.MAX_ZEILE - name.length();
        String felder = String.join(";", Collections.nCopies(4, "\u0001".repeat(8_738))) + ";"
                + "\u0001".repeat(rest / 6 - 4 * 8_738) + "x".repeat(rest % 6);
        String text = "Name;N1;N2;N3;N4;N5\n" + name + ";" + felder + "\n" + "Darüber AG;" + felder + "\n"
                + "Danach AG;;;;;\n";
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Wenn("«{word}» diese Kontakt-CSV im Zeichensatz «{word}» hochlädt:")
    public void textInZeichensatzHochladen(String benutzer, String zeichensatz, String text) {
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", text.getBytes(Charset.forName(zeichensatz)));
    }

    @Wenn("«{word}» eine Kontakt-CSV mit {int} Spalten hochlädt")
    public void vieleSpalten(String benutzer, int spalten) {
        StringBuilder kopf = new StringBuilder("Name");
        StringBuilder zeile = new StringBuilder("A");
        for (int i = 1; i < spalten; i++) {
            kopf.append(";S").append(i);
            zeile.append(';');
        }
        hochladen(benutzer, "kontakt", "false", "kontakte.csv",
                (kopf + "\n" + zeile + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Wenn("«{word}» die Verkaufschancen-Kopfzeile aus 21.CRM mit einer erfundenen Zeile hochlädt")
    public void verkaufschancenHochladen(String benutzer) {
        String kopf = new String(datei("csv/21crm-verkaufschancen-beispiel.csv"), CP1252).strip();
        String zeile = "x" + ";x".repeat(kopf.split(";", -1).length - 1);
        hochladen(benutzer, "verkaufschance", "false", "verkaufschancen.csv",
                (kopf + "\n" + zeile + "\n").getBytes(CP1252));
    }

    @Wenn("«{word}» eine Kontakt-CSV mit {int} Datenzeilen hochlädt")
    public void vieleZeilen(String benutzer, int anzahl) {
        StringBuilder text = new StringBuilder("Name;PLZ\n");
        for (int i = 0; i < anzahl; i++) {
            text.append('N').append(i).append(";6000\n");
        }
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Wenn("«{word}» eine UTF-16-Datei mit {int} Byte über der Grenze des CSV-Imports hochlädt")
    public void ueberDerGrenze(String benutzer, int mehr) {
        byte[] inhalt = new byte[Math.toIntExact(grenze() + mehr)];
        byte[] utf16 = "Name;PLZ\nA;6000\n".getBytes(StandardCharsets.UTF_16LE);
        for (int i = 0; i < inhalt.length; i++) {
            inhalt[i] = utf16[i % utf16.length];
        }
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", inhalt);
    }

    @Wenn("«{word}» eine Kontakt-CSV mit genau der Grenze des CSV-Imports hochlädt")
    public void anDerGrenze(String benutzer) {
        int grenze = Math.toIntExact(grenze());
        String kopf = "Name;Notiz\n";
        String zeile = "A;" + "x".repeat(97) + "\n";
        StringBuilder text = new StringBuilder(kopf);
        erwarteteZeilen = 0;
        while (text.length() + zeile.length() <= grenze) {
            text.append(zeile);
            erwarteteZeilen++;
        }
        int rest = grenze - text.length();
        if (rest >= 3) {
            text.append("A;").append("x".repeat(rest - 3)).append('\n');
            erwarteteZeilen++;
        } else {
            text.append("\n".repeat(rest));
        }
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Dann("nimmt der CSV-Import die Datei an der Grenze mit allen Datenzeilen an")
    public void anDerGrenzeAngenommen() {
        assertThat(hochgeladen).hasSize(Math.toIntExact(grenze()));
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("lauf.sha256")).isEqualTo(sha256(hochgeladen));
        assertThat(antwort.<Integer>path("lauf.zeilen")).isEqualTo(erwarteteZeilen);
        assertThat(antwort.<Integer>path("lauf.fehler")).isZero();
    }

    private static long grenze() {
        return ConfigProvider.getConfig().getValue("archiv.import.max-groesse", MemorySize.class).asLongValue();
    }

    @Wenn("«{word}» eine Kontakt-CSV mit art «{}» und vollexport «{}» hochlädt")
    public void parameterHochladen(String benutzer, String art, String vollexport) {
        hochladen(benutzer, art, vollexport, "kontakte.csv", "Name\nA\n".getBytes(StandardCharsets.UTF_8));
    }

    private void hochladen(String benutzer, String art, String vollexport, String dateiname, byte[] inhalt) {
        benutzt = true;
        hochgeladen = inhalt;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("art", art)
                .queryParam("vollexport", vollexport).multiPart("datei", dateiname, inhalt, "text/csv")
                .when().post(UPLOAD);
        if (antwort.statusCode() == 200 || antwort.statusCode() == 202) {
            laufId = antwort.path("lauf.id");
            vorschlag = antwort.path("vorschlagZuordnung");
        }
    }

    @Wenn("«{word}» den Vorschlag für den Lauf bestätigt")
    public void vorschlagBestaetigen(String benutzer) {
        assertThat(vorschlag).as("Vorschlag aus dem letzten Upload").isNotNull();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("laufId", laufId);
        body.put("profil", vorschlag.get("profil"));
        body.put("felder", vorschlag.get("felder"));
        zuordnen(benutzer, "kontakt", body);
    }

    @Wenn("«{word}» den Vorschlag für den Verkaufschancen-Lauf bestätigt")
    public void verkaufschancenVorschlagBestaetigen(String benutzer) {
        assertThat(vorschlag).as("Vorschlag aus dem letzten Upload").isNotNull();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("laufId", laufId);
        body.put("felder", vorschlag.get("felder"));
        zuordnen(benutzer, "verkaufschance", body);
    }

    @Wenn("«{word}» für den Lauf die Kontakt-Zuordnung bestätigt:")
    public void felderBestaetigen(String benutzer, DataTable felder) {
        Map<String, Integer> zuordnung = new LinkedHashMap<>();
        felder.asMap(String.class, Integer.class).forEach((feld, position) ->
                zuordnung.put((String) feld, (Integer) position));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("laufId", laufId);
        body.put("felder", zuordnung);
        zuordnen(benutzer, "kontakt", body);
    }

    @Wenn("«{word}» für einen unbekannten Lauf die Kontakt-Zuordnung «21.CRM» bestätigt")
    public void unbekannterLauf(String benutzer) {
        zuordnen(benutzer, "kontakt", Map.of("laufId", UUID.randomUUID().toString(), "profil", "21.CRM"));
    }

    @Wenn("«{word}» für den Lauf die Kontakt-Zuordnung «21.CRM» bestätigt")
    public void profilBestaetigen(String benutzer) {
        zuordnen(benutzer, "kontakt", Map.of("laufId", laufId, "profil", "21.CRM"));
    }

    @Wenn("«{word}» für den Lauf die Verkaufschancen-Zuordnung «21.CRM» bestätigt")
    public void verkaufschancenProfilBestaetigen(String benutzer) {
        zuordnen(benutzer, "verkaufschance", Map.of("laufId", laufId, "profil", "21.CRM"));
    }

    private void zuordnen(String benutzer, String art, Map<String, ?> body) {
        benutzt = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("art", art)
                .contentType("application/json").body(body).when().put(ZUORDNUNG);
    }

    @Wenn("«{word}» die gespeicherte Kontakt-Zuordnung abfragt")
    public void zuordnungAbfragen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("art", "kontakt").when()
                .get(ZUORDNUNG);
    }

    @Dann("lehnt der CSV-Import mit Status {int} und Fehlercode «{word}» ab")
    public void abgelehnt(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Dann("nennt die Meldung «{}»")
    public void meldung(String text) {
        assertThat(antwort.<String>path("detail")).contains(text);
    }

    @Dann("zeigt der CSV-Import den Zuordnungsdialog mit {int} Spalten und dem Profil «{word}»")
    public void dialogMitProfil(int spalten, String profil) {
        dialog(spalten);
        assertThat(vorschlag.get("profil")).isEqualTo(profil);
    }

    @Dann("zeigt der CSV-Import den Zuordnungsdialog mit {int} Spalten ohne Profil und den Feldern:")
    public void dialogOhneProfil(int spalten, DataTable felder) {
        dialog(spalten);
        assertThat(vorschlag.get("profil")).isNull();
        Map<String, Integer> erwartet = new LinkedHashMap<>();
        felder.asMap(String.class, Integer.class).forEach((feld, position) ->
                erwartet.put((String) feld, (Integer) position));
        assertThat(vorschlag.get("felder")).isEqualTo(erwartet);
    }

    private void dialog(int spalten) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.<String>path("lauf.status")).isEqualTo("ZUORDNUNG_NOETIG");
        assertThat(antwort.<List<Object>>path("spalten")).hasSize(spalten);
        assertThat(antwort.<Integer>path("spalten[0].position")).isZero();
        assertThat(vorschlag).isNotNull();
        assertThat(antwort.<String>path("lauf.sha256")).isEqualTo(sha256(hochgeladen));
    }

    @Dann("erkennt der CSV-Import Zeichensatz «{word}», Trennzeichen «{}» und {int} Datenzeilen")
    public void erkannt(String zeichensatz, String trennzeichen, int zeilen) {
        assertThat(antwort.statusCode()).isIn(200, 202);
        assertThat(antwort.<String>path("lauf.zeichensatz")).isEqualTo(zeichensatz);
        assertThat(antwort.<String>path("lauf.trennzeichen")).isEqualTo(trennzeichen);
        assertThat(antwort.<Integer>path("lauf.zeilen")).isEqualTo(zeilen);
    }

    @Dann("nimmt der CSV-Import die Datei mit Status 202 an, ohne Dialog, Lauf «{word}»")
    public void ohneDialog(String status) {
        assertThat(antwort.statusCode()).isEqualTo(202);
        assertThat(antwort.<String>path("lauf.status")).isEqualTo(status);
        assertThat(vorschlag).isNull();
    }

    @Dann("steht der Lauf auf «{word}» mit {int} Datenzeilen, {int} fehlerhaft und {int} übersprungen")
    public void lauf(String status, int zeilen, int fehler, int uebersprungen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isIn(200, 202);
        String pfad = antwort.<Object>path("lauf") == null ? "" : "lauf.";
        assertThat(antwort.<String>path(pfad + "status")).isEqualTo(status);
        assertThat(antwort.<Integer>path(pfad + "zeilen")).isEqualTo(zeilen);
        assertThat(antwort.<Integer>path(pfad + "fehler")).isEqualTo(fehler);
        assertThat(antwort.<Integer>path(pfad + "uebersprungen")).isEqualTo(uebersprungen);
    }

    @Dann("liefert der CSV-Import die gespeicherte Kontakt-Zuordnung mit dem Profil «{word}»")
    public void gespeichert(String profil) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.<String>path("art")).isEqualTo("kontakt");
        assertThat(antwort.<String>path("profil")).isEqualTo(profil);
        assertThat(antwort.<Map<String, Integer>>path("felder")).containsKey("name");
    }

    @Dann("hat der Lauf im Schema von «{word}» diese Zeilen:")
    public void zeilen(String mandant, DataTable erwartet) throws SQLException {
        List<Map<String, String>> ist = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select nr, coalesce(ergebnis, 'OFFEN'), coalesce(meldung, ''), daten is not null, "
                        + "coalesce(daten::text, '') from " + new MandantCode(mandant).schema()
                        + ".import_zeile where lauf_id = ? order by nr")) {
            abfrage.setObject(1, UUID.fromString(laufId));
            try (ResultSet zeilen = abfrage.executeQuery()) {
                while (zeilen.next()) {
                    Map<String, String> zeile = new LinkedHashMap<>();
                    zeile.put("nr", String.valueOf(zeilen.getInt(1)));
                    zeile.put("ergebnis", zeilen.getString(2));
                    zeile.put("meldung", zeilen.getString(3));
                    zeile.put("werte gespeichert", zeilen.getBoolean(4) ? "ja" : "nein");
                    zeile.put("daten", zeilen.getString(5));
                    ist.add(zeile);
                }
            }
        }
        List<Map<String, String>> soll = erwartet.asMaps();
        assertThat(ist).hasSameSizeAs(soll);
        for (int i = 0; i < soll.size(); i++) {
            Map<String, String> s = soll.get(i);
            Map<String, String> z = ist.get(i);
            assertThat(z.get("nr")).isEqualTo(s.get("nr"));
            assertThat(z.get("ergebnis")).as("Zeile %s", s.get("nr")).isEqualTo(s.get("ergebnis"));
            assertThat(z.get("meldung")).as("Zeile %s", s.get("nr")).isEqualTo(nullAlsLeer(s.get("meldung")));
            assertThat(z.get("werte gespeichert")).as("Zeile %s", s.get("nr")).isEqualTo(s.get("werte gespeichert"));
            String enthaelt = nullAlsLeer(s.get("daten enthalten"));
            if (!enthaelt.isEmpty()) {
                assertThat(z.get("daten")).as("Zeile %s", s.get("nr")).contains(enthaelt);
            }
        }
    }

    @Dann("ist im Schema von «{word}» kein Importlauf gespeichert")
    public void keinLauf(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select count(*) from " + new MandantCode(mandant).schema() + ".import_lauf")) {
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                ergebnis.next();
                assertThat(ergebnis.getInt(1)).isZero();
            }
        }
    }

    @Dann("enthält das Log die Prüfsumme der Datei, aber keinen der Werte «{word}» und «{word}»")
    public void logOhneWerte(String wert, String dateiname) {
        String log = mitschnitt.volltext();
        assertThat(log).contains(sha256(hochgeladen)).doesNotContain(wert).doesNotContain(dateiname);
    }

    @Wenn("«{word}» die Kontakt-CSV mit dem Wert «{word}» unter dem Dateinamen «{word}» hochlädt")
    public void mitWertHochladen(String benutzer, String wert, String dateiname) {
        hochladen(benutzer, "kontakt", "true", dateiname,
                ("Name;PLZ;Notiz\n" + wert + ";" + wert + ";" + wert + "\nB;6000\n").getBytes(StandardCharsets.UTF_8));
    }

    @Wenn("«{word}» diese Kontakt-CSV als Vollexport hochlädt:")
    public void vollexportHochladen(String benutzer, String text) {
        hochladen(benutzer, "kontakt", "true", "kontakte.csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Wenn("«{word}» diese Verkaufschancen-CSV hochlädt:")
    public void verkaufschancenTextHochladen(String benutzer, String text) {
        hochladen(benutzer, "verkaufschance", "false", "verkaufschancen.csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Wenn("«{word}» den Lauf übernimmt")
    public void uebernehmen(String benutzer) {
        uebernehmen(benutzer, laufId, Map.of());
    }

    @Wenn("«{word}» den Lauf «{}» übernimmt")
    public void andererLaufUebernehmen(String benutzer, String id) {
        uebernehmen(benutzer, id, Map.of());
    }

    /** {@code ZUSAMMENFUEHREN:K2} wird zur ID des Kontakts K2 aus dem Hintergrund. */
    @Wenn("«{word}» den Lauf mit diesen Dubletten-Entscheiden übernimmt:")
    public void mitEntscheidenUebernehmen(String benutzer, DataTable tabelle) {
        Map<String, String> entscheide = new LinkedHashMap<>();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String entscheid = zeile.get("entscheid");
            if (entscheid.startsWith("ZUSAMMENFUEHREN:")) {
                String kontakt = entscheid.substring("ZUSAMMENFUEHREN:".length());
                entscheid = "ZUSAMMENFUEHREN:" + Objects.requireNonNull(kontakte.get(kontakt), kontakt);
            }
            entscheide.put(zeile.get("zeile"), entscheid);
        }
        uebernehmen(benutzer, laufId, entscheide);
    }

    private void uebernehmen(String benutzer, String id, Map<String, String> entscheide) {
        benutzt = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType("application/json")
                .body(Map.of("dublettenEntscheide", entscheide)).when()
                .post(UPLOAD.replace("/crm", "/") + id + "/uebernehmen");
    }

    @Dann("nimmt der CSV-Import die Übernahme mit Status 202 an")
    public void uebernahmeAngenommen() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        assertThat(antwort.<String>path("id")).isEqualTo(laufId);
        assertThat(antwort.<String>path("status")).isEqualTo("VORSCHAU");
        assertThat(antwort.<String>path("uebernommenVon")).isNotBlank();
        assertThat(antwort.<Object>path("uebernommen")).isNull();
    }

    @Dann("ist der Lauf von «{word}» übernommen mit {int} angelegt, {int} aktualisiert, {int} ausgelassen und {int} "
            + "gekennzeichnet")
    public void uebernommen(String benutzer, int angelegt, int aktualisiert, int ausgelassen, int gekennzeichnet) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("lauf.status")).isEqualTo("UEBERNOMMEN");
        assertThat(antwort.<String>path("lauf.uebernommenVon")).isEqualTo(subjekt(benutzer));
        assertThat(antwort.<String>path("lauf.uebernommen")).isNotBlank();
        assertThat(List.of(antwort.<Integer>path("lauf.angelegt"), antwort.<Integer>path("lauf.aktualisiert"),
                antwort.<Integer>path("lauf.ausgelassen"), antwort.<Integer>path("lauf.gekennzeichnet")))
                .containsExactly(angelegt, aktualisiert, ausgelassen, gekennzeichnet);
    }

    /** Spalte {@code kontakt}: Kontakt aus dem Hintergrund, sonst die {@code crm_id} eines neuen Kontakts. */
    @Dann("hat der Mandant «{word}» diese Kontakte:")
    public void kontaktePruefen(String mandant, DataTable erwartet) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map<String, String> soll : erwartet.asMaps()) {
                String kennung = soll.get("kontakt");
                UUID id = kontakte.get(kennung);
                try (PreparedStatement abfrage = verbindung.prepareStatement("select crm_id, name, plz, ort, email, "
                        + "quelle, nicht_im_export from " + schema + ".kontakt where "
                        + (id == null ? "crm_id = ?" : "id = ?"))) {
                    abfrage.setObject(1, id == null ? kennung : id);
                    try (ResultSet zeile = abfrage.executeQuery()) {
                        assertThat(zeile.next()).as("Kontakt %s", kennung).isTrue();
                        List<String> ist = new ArrayList<>();
                        for (String spalte : List.of("crm_id", "name", "plz", "ort", "email", "quelle")) {
                            ist.add(nullAlsLeer(zeile.getString(spalte)));
                        }
                        ist.add(String.valueOf(zeile.getBoolean("nicht_im_export")));
                        assertThat(ist).as("Kontakt %s", kennung).containsExactly(nullAlsLeer(soll.get("crm_id")),
                                nullAlsLeer(soll.get("name")), nullAlsLeer(soll.get("plz")),
                                nullAlsLeer(soll.get("ort")), nullAlsLeer(soll.get("email")), soll.get("quelle"),
                                soll.get("nicht_im_export"));
                    }
                }
            }
        }
    }

    @Dann("gibt es im Mandanten «{word}» {int} Kontakte mit dem Namen «{}» aus dem CSV-Import")
    public void anzahlKontakte(String mandant, int anzahl, String name) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from " + new MandantCode(mandant).schema()
                        + ".kontakt where name = ? and quelle = 'CSV_IMPORT'")) {
            abfrage.setString(1, name);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getInt(1)).isEqualTo(anzahl);
            }
        }
    }

    @Dann("hat der Mandant «{word}» diese Verkaufschancen:")
    public void verkaufschancenPruefen(String mandant, DataTable erwartet) throws SQLException {
        Map<UUID, String> kennung = new HashMap<>();
        kontakte.forEach((name, id) -> kennung.put(id, name));
        List<List<String>> ist = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select crm_id, kontakt_id, titel, phase, betrag, waehrung, datum, "
                        + "import_lauf from " + new MandantCode(mandant).schema() + ".verkaufschance order by crm_id");
             ResultSet zeile = abfrage.executeQuery()) {
            while (zeile.next()) {
                assertThat(zeile.getString("waehrung")).isEqualTo("CHF");
                assertThat(zeile.getObject("import_lauf", UUID.class)).isNotNull();
                ist.add(List.of(zeile.getString("crm_id"),
                        kennung.getOrDefault(zeile.getObject("kontakt_id", UUID.class), "?"),
                        nullAlsLeer(zeile.getString("titel")), nullAlsLeer(zeile.getString("phase")),
                        zeile.getBigDecimal("betrag") == null ? "" : zeile.getBigDecimal("betrag").toPlainString(),
                        zeile.getDate("datum") == null ? "" : zeile.getDate("datum").toLocalDate().toString()));
            }
        }
        List<List<String>> soll = erwartet.asMaps().stream().map(z -> List.of(z.get("crm_id"), z.get("kontakt"),
                nullAlsLeer(z.get("titel")), nullAlsLeer(z.get("phase")), nullAlsLeer(z.get("betrag")),
                nullAlsLeer(z.get("datum")))).toList();
        assertThat(ist).containsExactlyElementsOf(soll);
    }

    @Wenn("«{word}» die Import-Läufe abfragt")
    public void laeufeAbfragen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(UPLOAD.replace("/crm", ""));
    }

    @Dann("listet der CSV-Import den Lauf mit Dateiname «{word}», Prüfsumme, Benutzer «{word}» und Zeit")
    public void laufGelistet(String dateiname, String benutzer) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<Integer>path("gesamt")).isEqualTo(1);
        assertThat(antwort.<String>path("eintraege[0].id")).isEqualTo(laufId);
        assertThat(antwort.<String>path("eintraege[0].dateiname")).isEqualTo(dateiname);
        assertThat(antwort.<String>path("eintraege[0].sha256")).isEqualTo(sha256(hochgeladen));
        assertThat(antwort.<String>path("eintraege[0].status")).isEqualTo("UEBERNOMMEN");
        assertThat(antwort.<String>path("eintraege[0].uebernommenVon")).isEqualTo(subjekt(benutzer));
        assertThat(antwort.<String>path("eintraege[0].erstellt")).isNotBlank();
        assertThat(antwort.<String>path("eintraege[0].uebernommen")).isNotBlank();
        assertThat(antwort.<Integer>path("eintraege[0].zeilen")).isEqualTo(2);
        assertThat(antwort.<Integer>path("eintraege[0].ausgelassen")).isNotNull();
    }

    @Dann("listet der CSV-Import {int} Läufe")
    public void anzahlLaeufe(int anzahl) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<Integer>path("gesamt")).isEqualTo(anzahl);
        assertThat(antwort.<List<Object>>path("eintraege")).hasSize(anzahl);
    }

    @Dann("liegt die Originaldatei des Laufs unter «importe\\/<laufId>.csv» mit der Prüfsumme der hochgeladenen Datei")
    public void originaldatei() {
        assertThat(laufId).isNotNull();
        String bucket = new MandantCode("urinet").bucket(
                ConfigProvider.getConfig().getValue("archiv.ablage.bucket-praefix", String.class));
        byte[] gespeichert = s3.getObjectAsBytes(b -> b.bucket(bucket).key("importe/" + laufId + ".csv"))
                .asByteArray();
        assertThat(sha256(gespeichert)).isEqualTo(sha256(hochgeladen));
    }

    /** {@code sub} aus dem Token: Das Archiv protokolliert den Benutzer über seine Keycloak-ID. */
    private static String subjekt(String benutzer) {
        String nutzlast = Testanmeldung.token(benutzer).split("\\.")[1];
        return JsonPath.from(new String(Base64.getUrlDecoder().decode(nutzlast), StandardCharsets.UTF_8))
                .getString("sub");
    }

    private static String nullAlsLeer(String wert) {
        return wert == null ? "" : wert;
    }

    private static byte[] variante(String variante) {
        byte[] original = datei("csv/21crm-kontakte-beispiel.csv");
        String text = new String(original, CP1252);
        return switch (variante) {
            case "original" -> original;
            case "utf8" -> text.getBytes(StandardCharsets.UTF_8);
            case "utf8-bom" -> ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8);
            case "komma-crlf" -> komma(text).getBytes(CP1252);
            default -> throw new IllegalArgumentException("unbekannte Variante " + variante);
        };
    }

    /** Semikolon → Komma nach RFC 4180: Felder mit Komma, Anführungszeichen oder Zeilenumbruch in Anführungszeichen. */
    private static String komma(String text) {
        StringBuilder ergebnis = new StringBuilder();
        StringBuilder feld = new StringBuilder();
        boolean zitiert = false;
        boolean imFeld = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (zitiert) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    feld.append('"');
                    i++;
                } else if (c == '"') {
                    zitiert = false;
                } else {
                    feld.append(c);
                }
            } else if (c == '"' && !imFeld) {
                zitiert = true;
                imFeld = true;
            } else if (c == ';' || c == '\n') {
                ergebnis.append(feldKomma(feld.toString())).append(c == ';' ? "," : "\r\n");
                feld.setLength(0);
                imFeld = false;
            } else {
                feld.append(c);
                imFeld = true;
            }
        }
        if (!feld.isEmpty()) {
            ergebnis.append(feldKomma(feld.toString()));
        }
        return ergebnis.toString();
    }

    private static String feldKomma(String wert) {
        boolean zitieren = wert.indexOf(',') >= 0 || wert.indexOf('"') >= 0 || wert.indexOf('\n') >= 0
                || wert.indexOf('\r') >= 0;
        return zitieren ? '"' + wert.replace("\"", "\"\"") + '"' : wert;
    }

    private static String sha256(byte[] inhalt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] datei(String pfad) {
        try (InputStream inhalt = CsvImportSchritte.class.getClassLoader().getResourceAsStream(pfad)) {
            if (inhalt == null) {
                throw new IllegalStateException("Testdatei fehlt: " + pfad);
            }
            return inhalt.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
