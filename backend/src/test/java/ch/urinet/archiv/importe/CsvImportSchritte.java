package ch.urinet.archiv.importe;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Aufgabe 4.1 (Req 2.18, 13.1, 13.2, 13.10, 13.11): Upload über {@code POST /api/v1/importe/crm},
 * Zuordnung über {@code GET}/{@code PUT /api/v1/importe/crm/zuordnung}. Die Beispieldateien stammen aus Aufgabe 0.4;
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

    private Response antwort;
    private String laufId;
    private Map<String, Object> vorschlag;
    private byte[] hochgeladen;
    private LogMitschnitt mitschnitt;
    private boolean benutzt;

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
                for (String tabelle : List.of("import_zeile", "import_lauf", "import_zuordnung")) {
                    try (PreparedStatement loeschen = verbindung.prepareStatement(
                            "delete from " + schema + "." + tabelle)) {
                        loeschen.executeUpdate();
                    }
                }
            }
        }
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

    @Wenn("«{word}» eine Kontakt-CSV mit {int} Datenzeilen hochlädt")
    public void vieleZeilen(String benutzer, int anzahl) {
        StringBuilder text = new StringBuilder("Name;PLZ\n");
        for (int i = 0; i < anzahl; i++) {
            text.append('N').append(i).append(";6000\n");
        }
        hochladen(benutzer, "kontakt", "false", "kontakte.csv", text.toString().getBytes(StandardCharsets.UTF_8));
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
