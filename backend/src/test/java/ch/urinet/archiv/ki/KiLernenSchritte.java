package ch.urinet.archiv.ki;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Req 7.7, 7.9 und 7.10 (Aufgabe 3.3), im Mandanten {@code urinet}: Beleg verarbeiten, über die REST-API
 * bestätigen, {@code ki_beispiel} und die Zähler der Korrekturquote prüfen, nächsten Beleg verarbeiten und den Aufruf
 * des Sprachmodells (WireMock) auf das Beispiel prüfen. Den Schalter {@code archiv.ki.beispiele.aktiv} setzen nur die
 * Szenarien, danach gilt wieder die Konfiguration. Nur synthetische Werte (Req 16.15).
 */
public class KiLernenSchritte {

    private static final String MANDANT = "urinet";
    private static final String PFAD = "/v1/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MeterRegistry kennzahlen;

    @Inject
    TestBeispielSchalter schalter;

    @Inject
    BenutzerVerzeichnis verzeichnis;

    private final Map<String, UUID> belege = new HashMap<>();
    private final Map<String, String> laeufe = new HashMap<>();
    private final Map<String, Double> zaehlerVorher = new HashMap<>();
    private final Map<String, UUID> kontakte = new HashMap<>();

    @Angenommen("die Zähler der Korrekturquote sind abgelesen")
    public void zaehlerAblesen() {
        for (String name : List.of("archiv.ki.feld.bestaetigt", "archiv.ki.feld.korrigiert")) {
            for (String feld : List.of("titel", "datum", "partnerName", "vertraulichkeit", "art")) {
                zaehlerVorher.put(name + "/" + feld, zaehler(name, feld));
            }
        }
    }

    @Angenommen("der Partner «{}» ist als Kontakt mit der IBAN «{word}» erfasst")
    public void partner(String name, String iban) throws Exception {
        kontakte.put(name, hilfe.kontaktAnlegen(MANDANT, name, iban));
    }

    @Angenommen("der Partner «{}» ist als Kontakt ohne IBAN erfasst")
    public void partnerOhneIban(String name) throws Exception {
        kontakte.put(name, hilfe.kontaktAnlegen(MANDANT, name, null));
    }

    @Angenommen("die Korrekturbeispiele im Prompt sind eingeschaltet")
    public void eingeschaltet() {
        schalter.setzen(true);
    }

    @Angenommen("die Korrekturbeispiele im Prompt sind ausgeschaltet")
    public void ausgeschaltet() {
        schalter.setzen(false);
    }

    @Angenommen("«{word}» hängt den Beleg «{word}» an den Partner «{}»")
    public void umhaengen(String benutzer, String name, String partner) throws Exception {
        UUID id = belege.get(name);
        given().auth().oauth2(Testanmeldung.token(benutzer))
                .header("If-Match", "\"" + hilfe.kiVorschlag(MANDANT, id).get("version") + "\"")
                .contentType(ContentType.JSON).body(Map.of("partnerId", kontakte.get(partner)))
                .when().patch("/api/v1/dokumente/" + id)
                .then().statusCode(200);
    }

    /** Eine protokollierte Anfrage legt den Eintrag an (wie bei jeder ersten Aktion im Mandanten). */
    @Angenommen("«{word}» steht als «{word}» im Benutzerverzeichnis")
    public void imVerzeichnis(String benutzer, String rolle) {
        given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/dokumente/" + belege.get("A"))
                .then().statusCode(200);
        assertThat(verzeichnis.letzter(Optional.of(new MandantCode(MANDANT)), Testanmeldung.sub(benutzer))
                .flatMap(VerzeichnisEintrag::rolle)).contains(rolle);
    }

    @Wenn("der Beleg «{word}» nachträglich auf «{word}» gestuft wird")
    public void umstufen(String name, String vertraulichkeit) throws Exception {
        hilfe.dokumentAendern(MANDANT, belege.get(name), "vertraulichkeit = '" + vertraulichkeit + "'");
    }

    /** Bestätigte Dokumente mit je einem Beispiel «Probe neuer 1» bis «Probe neuer n», das letzte zuletzt. */
    @Angenommen("es gibt {int} neuere bestätigte Korrekturbeispiele mit der IBAN «{word}»")
    public void neuereBeispiele(int anzahl, String iban) throws Exception {
        String schema = new MandantCode(MANDANT).schema();
        for (int i = 1; i <= anzahl; i++) {
            UUID id = UUID.randomUUID();
            belege.put("neu" + i, id);
            auftraege.ausfuehren("insert into " + schema + ".dokument (id, art, titel, quelle, vertraulichkeit, "
                    + "erstellt_von, status, bestaetigt, bestaetigt_von) values ('" + id + "', 'KORRESPONDENZ', "
                    + "'Probe neuer " + i + "', 'UPLOAD', 'NORMAL', 'probe', 'ARCHIVIERT', now(), 'probe')");
            auftraege.ausfuehren("insert into " + schema + ".ki_beispiel (art, korrektur, iban, dokument_id, zeit) "
                    + "values ('KORRESPONDENZ', '{\"titel\": \"Probe neuer " + i + "\"}', array['" + iban + "'], '"
                    + id + "', clock_timestamp())");
        }
    }

    @Angenommen("der Beleg «{word}» als digitales PDF mit «{}» und «{}»")
    public void beleg(String name, String kopf, String zeile) throws Exception {
        String lauf = "Lauf " + HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4));
        UUID id = hilfe.anlegen(MANDANT, "UPLOAD", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf",
                "application/pdf", OcrTestdateien.textPdf(kopf, zeile, "Total CHF 123.40", lauf))));
        hilfe.einreihen(MANDANT, id);
        belege.put(name, id);
        laeufe.put(name, lauf);
    }

    @Angenommen("der Beleg «{word}» wurde von «{word}» erfasst")
    public void erfasser(String name, String benutzer) throws Exception {
        hilfe.dokumentAendern(MANDANT, belege.get(name), "erstellt_von = '" + Testanmeldung.sub(benutzer) + "'");
    }

    /** Bestätigt mit den Werten des Vorschlags samt Tags, ausser Titel und Vertraulichkeit; ohne Archivierung. */
    @Wenn("«{word}» den Beleg «{word}» mit dem Titel «{}» und der Vertraulichkeit «{word}» bestätigt")
    public void bestaetigen(String benutzer, String name, String titel, String vertraulichkeit) throws Exception {
        UUID id = belege.get(name);
        Map<String, Object> dokument = hilfe.kiVorschlag(MANDANT, id);
        JsonNode vorschlag = JSON.readTree((String) dokument.get("ki_vorschlag"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("art", vorschlag.path("art").asText());
        body.put("titel", titel);
        body.put("datum", vorschlag.path("datum").asText());
        Object partner = dokument.get("partner_id");
        body.put("partnerId", partner == null ? null : UUID.fromString((String) partner));
        body.put("vertraulichkeit", vertraulichkeit);
        List<String> tags = new java.util.ArrayList<>();
        vorschlag.path("tags").forEach(tag -> tags.add(tag.asText()));
        body.put("tags", tags);
        given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"" + dokument.get("version") + "\"")
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/dokumente/" + id + "/bestaetigen")
                .then().statusCode(202);
        assertThat(auftraege.ausfuehren("delete from public.auftrag where typ = 'ARCHIVIEREN' and nutzlast ->> "
                + "'dokumentId' = '" + id + "'")).isEqualTo(1);
    }

    @Dann("«ki_beispiel» enthält für den Beleg «{word}» die Korrektur {string} mit der IBAN «{word}»")
    public void beispiel(String name, String korrektur, String iban) throws Exception {
        List<Map<String, Object>> zeilen = beispiele(belege.get(name));
        assertThat(zeilen).hasSize(1);
        Map<String, Object> zeile = zeilen.getFirst();
        assertThat(JSON.readTree((String) zeile.get("korrektur"))).isEqualTo(JSON.readTree(korrektur));
        assertThat((String[]) zeile.get("iban")).containsExactly(iban);
        Object partner = hilfe.kiVorschlag(MANDANT, belege.get(name)).get("partner_id");
        assertThat(zeile).containsEntry("art", "KORRESPONDENZ")
                .containsEntry("partner_id", partner == null ? null : UUID.fromString((String) partner));
    }

    @Dann("«ki_beispiel» enthält keine Zeile für den Beleg «{word}»")
    public void keinBeispiel(String name) throws Exception {
        assertThat(beispiele(belege.get(name))).isEmpty();
    }

    @Dann("die Korrekturquote zählt für «{word}» {int} Bestätigung(en) und {int} Korrektur(en)")
    public void quote(String feld, int bestaetigt, int korrigiert) {
        assertThat(zaehler("archiv.ki.feld.bestaetigt", feld) - zaehlerVorher.get("archiv.ki.feld.bestaetigt/" + feld))
                .as("bestätigt %s", feld).isEqualTo(bestaetigt);
        assertThat(zaehler("archiv.ki.feld.korrigiert", feld) - zaehlerVorher.get("archiv.ki.feld.korrigiert/" + feld))
                .as("korrigiert %s", feld).isEqualTo(korrigiert);
    }

    @Dann("der Aufruf des Sprachmodells für den Beleg «{word}» enthält das Korrekturbeispiel {string}")
    public void mitBeispiel(String name, String zeile) {
        assertThat(aufruf(name)).contains("Korrekturbeispiele").contains(zeile);
    }

    @Dann("der Aufruf des Sprachmodells für den Beleg «{word}» enthält kein Korrekturbeispiel")
    public void ohneBeispiel(String name) {
        assertThat(aufruf(name)).doesNotContain("Korrekturbeispiele");
    }

    @Dann("der Aufruf des Sprachmodells für den Beleg «{word}» enthält kein Korrekturbeispiel «{}»")
    public void ohneDiesesBeispiel(String name, String text) {
        assertThat(aufruf(name)).contains("Korrekturbeispiele").doesNotContain(text);
    }

    @Dann("der Aufruf des Sprachmodells für den Beleg «{word}» nennt die Beispiele «{}»")
    public void einleitung(String name, String text) {
        assertThat(aufruf(name)).contains("Korrekturbeispiele: ").contains(text);
    }

    @After
    public void aufraeumen() throws Exception {
        schalter.setzen(null);
        Testdokumente.loeschen(datenbank.get(), MANDANT, belege.values());
        for (UUID id : kontakte.values()) {
            hilfe.kontaktLoeschen(MANDANT, id);
        }
        kontakte.clear();
        belege.clear();
    }

    private String aufruf(String name) {
        List<LoggedRequest> anfragen = KiWireMock.client().find(postRequestedFor(urlPathEqualTo(PFAD))).stream()
                .filter(a -> a.getBodyAsString().contains(laeufe.get(name))).toList();
        assertThat(anfragen).hasSize(1);
        return anfragen.getFirst().getBodyAsString();
    }

    private double zaehler(String name, String feld) {
        Counter zaehler = kennzahlen.find(name).tags("feld", feld, "mandant", MANDANT).counter();
        return zaehler == null ? 0 : zaehler.count();
    }

    private List<Map<String, Object>> beispiele(UUID dokument) throws Exception {
        List<Map<String, Object>> zeilen = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select art, korrektur::text as korrektur, iban, "
                     + "partner_id from " + new MandantCode(MANDANT).schema()
                     + ".ki_beispiel where dokument_id = ?")) {
            abfrage.setObject(1, dokument);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    Map<String, Object> zeile = new HashMap<>();
                    zeile.put("art", z.getString("art"));
                    zeile.put("korrektur", z.getString("korrektur"));
                    zeile.put("iban", (String[]) z.getArray("iban").getArray());
                    zeile.put("partner_id", z.getObject("partner_id", UUID.class));
                    zeilen.add(zeile);
                }
            }
        }
        return zeilen;
    }
}
