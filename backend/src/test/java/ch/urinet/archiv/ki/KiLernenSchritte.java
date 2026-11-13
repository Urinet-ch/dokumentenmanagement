package ch.urinet.archiv.ki;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.domain.MandantCode;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Req 7.7, 7.9 und 7.10 (Aufgabe 3.3), im Mandanten {@code urinet}: Beleg verarbeiten, über die REST-API
 * bestätigen, {@code ki_beispiel} und die Zähler der Korrekturquote prüfen, nächsten Beleg verarbeiten und den Aufruf
 * des Sprachmodells (WireMock) auf das Beispiel prüfen. Nur synthetische Werte (Req 16.15).
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

    private final Map<String, UUID> belege = new HashMap<>();
    private final Map<String, String> laeufe = new HashMap<>();
    private final Map<String, Double> zaehlerVorher = new HashMap<>();
    private UUID kontakt;

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
        kontakt = hilfe.kontaktAnlegen(MANDANT, name, iban);
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

    /** Bestätigt mit den Werten des Vorschlags, ausser Titel und Vertraulichkeit; danach ohne Archivierung. */
    @Wenn("«{word}» den Beleg «{word}» mit dem Titel «{}» und der Vertraulichkeit «{word}» bestätigt")
    public void bestaetigen(String benutzer, String name, String titel, String vertraulichkeit) throws Exception {
        UUID id = belege.get(name);
        Map<String, Object> dokument = hilfe.kiVorschlag(MANDANT, id);
        JsonNode vorschlag = JSON.readTree((String) dokument.get("ki_vorschlag"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("art", vorschlag.path("art").asText());
        body.put("titel", titel);
        body.put("datum", vorschlag.path("datum").asText());
        body.put("partnerId", kontakt);
        body.put("vertraulichkeit", vertraulichkeit);
        given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"" + dokument.get("version") + "\"")
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/dokumente/" + id + "/bestaetigen")
                .then().statusCode(202);
        assertThat(auftraege.ausfuehren("delete from public.auftrag where typ = 'ARCHIVIEREN' and nutzlast ->> "
                + "'dokumentId' = '" + id + "'")).isEqualTo(1);
    }

    @Dann("«ki_beispiel» enthält für den Beleg «{word}» die Korrektur {string} mit der IBAN «{word}» und der Vertraulichkeit «{word}»")
    public void beispiel(String name, String korrektur, String iban, String vertraulichkeit) throws Exception {
        List<Map<String, Object>> zeilen = beispiele(belege.get(name));
        assertThat(zeilen).hasSize(1);
        Map<String, Object> zeile = zeilen.getFirst();
        assertThat(JSON.readTree((String) zeile.get("korrektur"))).isEqualTo(JSON.readTree(korrektur));
        assertThat((String[]) zeile.get("iban")).containsExactly(iban);
        assertThat(zeile).containsEntry("vertraulichkeit", vertraulichkeit).containsEntry("art", "KORRESPONDENZ")
                .containsEntry("partner_id", kontakt);
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

    @After
    public void aufraeumen() throws Exception {
        Testdokumente.loeschen(datenbank.get(), MANDANT, belege.values());
        if (kontakt != null) {
            hilfe.kontaktLoeschen(MANDANT, kontakt);
            kontakt = null;
        }
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
                     + "vertraulichkeit, partner_id from " + new MandantCode(MANDANT).schema()
                     + ".ki_beispiel where dokument_id = ?")) {
            abfrage.setObject(1, dokument);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    Map<String, Object> zeile = new HashMap<>();
                    zeile.put("art", z.getString("art"));
                    zeile.put("korrektur", z.getString("korrektur"));
                    zeile.put("iban", (String[]) z.getArray("iban").getArray());
                    zeile.put("vertraulichkeit", z.getString("vertraulichkeit"));
                    zeile.put("partner_id", z.getObject("partner_id", UUID.class));
                    zeilen.add(zeile);
                }
            }
        }
        return zeilen;
    }
}
