package ch.urinet.archiv.ki;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.ki.adapter.out.langchain4j.AntwortSchema;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Req 7.1–7.3, 7.5, 7.8, 7.11 und 7.12 (Aufgabe 3.2), immer im Mandanten {@code urinet}. Das Sprachmodell
 * ist der WireMock aus {@link KiWireMock}; Antworten im Format der OpenAI-kompatiblen Schnittstelle, nur Testwerte.
 */
public class KiVorschlagSchritte {

    private static final String MANDANT = "urinet";
    private static final String PFAD = "/v1/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Inject
    AuftragTestHilfe auftraege;

    private WireMock modell;
    private UUID id;
    private long verbrauchStand;
    /** Kennungen des Mandanten vor dem Szenario, wenn ein Schritt sie geändert hat. */
    private String kennungenVorher;
    private boolean kennungenGeaendert;
    /** Kontakt aus dem Szenario, wird danach gelöscht. */
    private UUID kontakt;
    /** {@code version} des Dokuments direkt nach der Änderung durch den Benutzer. */
    private int versionBenutzer;
    /** {@code version} aus der Nutzlast von {@code KI_VORSCHLAG}. */
    private int versionNachVerarbeitung;
    /** Zweiter Kontakt aus dem Szenario, wird danach gelöscht. */
    private UUID andererKontakt;

    /** Ohne Korrekturbeispiele früherer Szenarien: Sie kämen sonst in den Prompt (Aufgabe 3.3). */
    @Angenommen("das Sprachmodell ist bereit")
    public void bereit() throws Exception {
        modell = KiWireMock.zuruecksetzen();
        for (String code : List.of("urinet", "vhf-gsk")) {
            auftraege.ausfuehren("delete from " + new MandantCode(code).schema() + ".ki_beispiel");
        }
        verbrauchStand = hilfe.kiVerbrauchStand(MANDANT);
    }

    /** Nach jedem Szenario (auch ausserhalb von KI): keine Stubs und Szenario-Zustände für die nächsten Tests. */
    @After
    public void sprachmodellZuruecksetzen() {
        KiWireMock.zuruecksetzen();
    }

    @Angenommen("der Mandant «{word}» hat die UID «{}»")
    public void uid(String mandant, String uid) throws Exception {
        kennungenVorher = hilfe.kennungen(mandant);
        kennungenGeaendert = true;
        ObjectNode kennungen = JSON.createObjectNode().put("uid", uid);
        kennungen.putArray("ibans");
        hilfe.kennungenSetzen(mandant, JSON.writeValueAsString(kennungen));
    }

    @After
    public void kennungenZuruecksetzen() throws Exception {
        if (kennungenGeaendert) {
            hilfe.kennungenSetzen(MANDANT, kennungenVorher);
            kennungenGeaendert = false;
        }
        if (kontakt != null) {
            hilfe.kontaktLoeschen(MANDANT, kontakt);
            kontakt = null;
        }
        if (andererKontakt != null) {
            hilfe.kontaktLoeschen(MANDANT, andererKontakt);
            andererKontakt = null;
        }
    }

    @Angenommen("im Mandanten gibt es den Kontakt «{}» mit der IBAN «{word}»")
    public void kontakt(String name, String iban) throws Exception {
        kontakt = hilfe.kontaktAnlegen(MANDANT, name, iban);
    }

    /** Wie bei der Erfassung gesetzt: vor der Verarbeitung, darum ohne eigene {@code version}. */
    @Angenommen("der Beleg ist schon mit dem Kontakt «{}» ohne IBAN verknüpft")
    public void schonVerknuepft(String name) throws Exception {
        andererKontakt = hilfe.kontaktAnlegen(MANDANT, name, null);
        auftraege.ausfuehren("update " + new MandantCode(MANDANT).schema() + ".dokument set partner_id = '"
                + andererKontakt + "', partner_name = '" + name.replace("'", "''") + "' where id = '" + id + "'");
    }

    @Dann("das Dokument bleibt mit dem Kontakt «{}» verknüpft, der Abgleich fand den Kontakt mit der IBAN")
    public void partnerBleibt(String name) throws Exception {
        assertThat(hilfe.kiVorschlag(MANDANT, id)).containsEntry("partner_id", andererKontakt.toString())
                .containsEntry("partner_name", name).containsEntry("ki_partner_id", kontakt.toString());
    }

    /** Wie im Eingangskorb (Req 10.4): PATCH mit {@code If-Match} der aktuellen {@code version}. */
    @Wenn("«{word}» den Titel des Dokuments auf «{}» ändert")
    public void titelAendern(String benutzer, String titel) throws Exception {
        given().auth().oauth2(Testanmeldung.token(benutzer))
                .header("If-Match", "\"" + hilfe.kiVorschlag(MANDANT, id).get("version") + "\"")
                .contentType(ContentType.JSON).body(Map.of("titel", titel))
                .when().patch("/api/v1/dokumente/" + id)
                .then().statusCode(200);
        versionBenutzer = ((Number) hilfe.kiVorschlag(MANDANT, id).get("version")).intValue();
    }

    @Dann("das Dokument hat den Titel «{}»")
    public void titel(String titel) throws Exception {
        assertThat(hilfe.kiVorschlag(MANDANT, id)).containsEntry("titel", titel);
    }

    @Dann("der Auftrag «KI_VORSCHLAG» trägt die Version des Dokuments nach der Verarbeitung")
    public void versionInNutzlast() throws Exception {
        Object version = auftraege.wert(hilfe.auftraege("KI_VORSCHLAG", id).getFirst(),
                "(nutzlast->>'" + VerarbeitenHandler.DOKUMENT_VERSION + "')::int");
        assertThat(version).as("Version in der Nutzlast").isNotNull();
        versionNachVerarbeitung = ((Number) version).intValue();
        assertThat(((Number) hilfe.kiVorschlag(MANDANT, id).get("version")).intValue())
                .isEqualTo(versionNachVerarbeitung);
    }

    @Dann("das Dokument ist «{word}» ohne Vorschlag und in der Version nach der Verarbeitung")
    public void unveraendertNachVerarbeitung(String status) throws Exception {
        Map<String, Object> dokument = hilfe.kiVorschlag(MANDANT, id);
        assertThat(dokument).containsEntry("status", status).containsEntry("ki_vorschlag", null)
                .containsEntry("ki_sicherheit", null);
        assertThat(((Number) dokument.get("version")).intValue()).isEqualTo(versionNachVerarbeitung);
    }

    /** Wie ein Auftrag, der vor dem Deploy dieser Änderung eingereiht wurde. */
    @Angenommen("die Nutzlast des Auftrags «KI_VORSCHLAG» enthält keine Version")
    public void ohneVersion() throws Exception {
        auftraege.aendern(hilfe.auftraege("KI_VORSCHLAG", id).getFirst(),
                "nutzlast = nutzlast - '" + VerarbeitenHandler.DOKUMENT_VERSION + "'");
    }

    @Wenn("der Auftrag «KI_VORSCHLAG» wieder fällig wird und der Worker ihn bearbeitet")
    public void einmalWiederholen() throws Exception {
        auftraege.aendern(hilfe.auftraege("KI_VORSCHLAG", id).getFirst(), "nicht_vor = now()");
        hilfe.abarbeiten();
    }

    @Angenommen("ein Beleg als verschlüsseltes PDF")
    public void verschluesselt() throws Exception {
        anlegen("UPLOAD", new Quelle(DateiRolle.ORIGINAL, 1, "pdf", "application/pdf",
                OcrTestdateien.verschluesseltesPdf()));
    }

    @Angenommen("ein Beleg als digitales PDF mit «{}» und «{}»")
    public void digitalesPdf(String kopf, String zeile) throws Exception {
        anlegen("UPLOAD", new Quelle(DateiRolle.ORIGINAL, 1, "pdf", "application/pdf",
                OcrTestdateien.textPdf(kopf, zeile, "Total CHF 123.40", "zahlbar innert 30 Tagen", kennung())));
    }

    @Angenommen("ein Beleg als Foto mit «{}»")
    public void foto(String text) throws Exception {
        anlegen("SCAN", new Quelle(DateiRolle.ORIGINAL, 1, "jpg", "image/jpeg",
                OcrTestdateien.jpeg(OcrTestdateien.seite(0, text, "Total CHF 4.50", kennung()))));
    }

    @Angenommen("ein Beleg als gescanntes PDF mit «{}» und einem Swiss QR Code über CHF {word}")
    public void qrPdf(String text, String betrag) throws Exception {
        BufferedImage seite = OcrTestdateien.seite(0, text, "Rechnung F-77", kennung());
        var matrix = new QRCodeWriter().encode(spc(betrag), BarcodeFormat.QR_CODE, 300, 300,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET, "UTF-8"));
        Graphics2D g = seite.createGraphics();
        g.drawImage(MatrixToImageWriter.toBufferedImage(matrix), 400, 1300, null);
        g.dispose();
        anlegen("UPLOAD", new Quelle(DateiRolle.ORIGINAL, 1, "pdf", "application/pdf",
                OcrTestdateien.bildPdf(List.of(seite))));
    }

    @Angenommen("das Sprachmodell schlägt «{word}» mit Belegnummer «{}» und Vertraulichkeit «{word}» vor")
    public void schlaegtVor(String art, String belegnummer, String vertraulichkeit) throws Exception {
        modell.register(post(urlPathEqualTo(PFAD)).willReturn(okJson(antwort(modellVorschlag(art, belegnummer,
                vertraulichkeit)))));
    }

    @Angenommen("das Sprachmodell schlägt «{word}» mit Belegnummer «{}» und Vertraulichkeit «{word}» erst nach {int} Sekunden vor")
    public void schlaegtVerzoegertVor(String art, String belegnummer, String vertraulichkeit, int sekunden)
            throws Exception {
        modell.register(post(urlPathEqualTo(PFAD)).willReturn(okJson(antwort(modellVorschlag(art, belegnummer,
                vertraulichkeit))).withFixedDelay(sekunden * 1000)));
    }

    /**
     * Der Worker läuft im Hintergrund; sobald der WireMock die Anfrage erhalten hat (vor der verzögerten Antwort), ändert
     * bzw. bestätigt der Schritt das Dokument. Das liegt damit sicher zwischen Laden und Speichern des Vorschlags.
     */
    @Wenn("der Worker die Aufträge bearbeitet und das Dokument während des Aufrufs {word} wird")
    public void aendernWaehrendAufruf(String aenderung) throws Exception {
        String setzen = switch (aenderung) {
            case "geändert" -> "titel = 'Titel des Benutzers'";
            case "bestätigt" -> "bestaetigt = clock_timestamp(), bestaetigt_von = 'test-benutzer'";
            default -> throw new IllegalArgumentException("Unbekannte Änderung: " + aenderung);
        };
        CompletableFuture<Void> lauf = CompletableFuture.runAsync(() -> {
            try {
                hilfe.abarbeiten();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            long ende = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (anfragen().isEmpty()) {
                assertThat(lauf.isDone() || System.nanoTime() > ende).as("Sprachmodell aufgerufen").isFalse();
                Thread.sleep(20);
            }
            hilfe.dokumentAendern(MANDANT, id, setzen);
            versionBenutzer = ((Number) hilfe.kiVorschlag(MANDANT, id).get("version")).intValue();
        } finally {
            lauf.get(5, TimeUnit.MINUTES);
        }
    }

    @Dann("das Dokument ist «{word}» ohne Vorschlag und in der Version des Benutzers")
    public void unveraendertNachBenutzer(String status) throws Exception {
        Map<String, Object> dokument = hilfe.kiVorschlag(MANDANT, id);
        assertThat(dokument).containsEntry("status", status).containsEntry("ki_vorschlag", null)
                .containsEntry("ki_sicherheit", null);
        assertThat(((Number) dokument.get("version")).intValue()).isEqualTo(versionBenutzer);
    }

    @Angenommen("das Sprachmodell antwortet zuerst ausserhalb des Schemas und danach mit «{word}»")
    public void nachfrage(String art) throws Exception {
        modell.register(post(urlPathEqualTo(PFAD)).inScenario("nachfrage").whenScenarioStateIs(STARTED)
                .willReturn(okJson(antwort("{\"art\": \"RECHNUNG\", \"titel\": "))).willSetStateTo("nachgefragt"));
        modell.register(post(urlPathEqualTo(PFAD)).inScenario("nachfrage").whenScenarioStateIs("nachgefragt")
                .willReturn(okJson(antwort(modellVorschlag(art, "2026-124", "INTERN")))));
    }

    @Angenommen("das Sprachmodell antwortet immer ausserhalb des Schemas")
    public void immerFalsch() throws Exception {
        modell.register(post(urlPathEqualTo(PFAD)).willReturn(okJson(antwort("kein JSON"))));
    }

    @Angenommen("das Sprachmodell antwortet immer mit «{}»")
    public void immerInhalt(String inhalt) throws Exception {
        modell.register(post(urlPathEqualTo(PFAD)).willReturn(okJson(antwort(inhalt))));
    }

    @Angenommen("das Sprachmodell antwortet immer ohne Nachricht")
    public void ohneNachricht() throws Exception {
        ObjectNode a = (ObjectNode) JSON.readTree(antwort(""));
        ((ObjectNode) a.path("choices").get(0).path("message")).putNull("content");
        modell.register(post(urlPathEqualTo(PFAD)).willReturn(okJson(JSON.writeValueAsString(a))));
    }

    @Wenn("der Auftrag «KI_VORSCHLAG» zweimal wieder fällig wird und der Worker ihn bearbeitet")
    public void zweimalWiederholen() throws Exception {
        long auftrag = hilfe.auftraege("KI_VORSCHLAG", id).getFirst();
        for (int i = 0; i < 2; i++) {
            auftraege.aendern(auftrag, "nicht_vor = now()");
            hilfe.abarbeiten();
        }
    }

    @Dann("ist der Auftrag «KI_VORSCHLAG» nach {int} Versuch(en) «{word}»")
    public void auftragVersuche(int versuche, String status) throws Exception {
        var zeile = auftraege.zeile(hilfe.auftraege("KI_VORSCHLAG", id).getFirst());
        assertThat(zeile.status()).isEqualTo(status);
        assertThat(zeile.versuche()).isEqualTo(versuche);
    }

    @Dann("das Dokument ist mit dem Kontakt verknüpft und heisst dort «{}»")
    public void partnerVerknuepft(String name) throws Exception {
        assertThat(hilfe.kiVorschlag(MANDANT, id)).containsEntry("partner_id", kontakt.toString())
                .containsEntry("partner_name", name);
    }

    @Dann("der Aufruf enthält kein Seitenbild")
    public void keinSeitenbild() {
        assertThat(anfragen()).allSatisfy(a -> assertThat(a.getBodyAsString()).doesNotContain("data:image/"));
    }

    @Angenommen("das Sprachmodell antwortet mit HTTP {int}")
    public void httpFehler(int status) {
        modell.register(post(urlPathEqualTo(PFAD)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"Testausfall\"}}")));
    }

    @Dann("wurde das Sprachmodell {int}-mal aufgerufen")
    public void aufrufe(int anzahl) {
        assertThat(anfragen()).hasSize(anzahl);
    }

    @Dann("der Aufruf verlangt Modell «{}» mit Temperatur 0 und einem strikten JSON-Schema")
    public void parameter(String name) throws Exception {
        JsonNode anfrage = JSON.readTree(anfragen().getFirst().getBodyAsString());
        assertThat(anfrage.path("model").asText()).isEqualTo(name);
        assertThat(List.of("temperature", "top_p", "presence_penalty", "frequency_penalty"))
                .allSatisfy(feld -> assertThat(anfrage.has(feld)).as(feld).isTrue());
        assertThat(anfrage.path("temperature").decimalValue()).isEqualByComparingTo("0");
        assertThat(anfrage.path("top_p").decimalValue()).isEqualByComparingTo("1");
        assertThat(anfrage.path("presence_penalty").decimalValue()).isEqualByComparingTo("0");
        assertThat(anfrage.path("frequency_penalty").decimalValue()).isEqualByComparingTo("0");
        assertThat(anfrage.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(anfrage.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(anfrage.path("response_format").path("json_schema").path("schema").path("properties").fieldNames())
                .toIterable().contains("art", "titel", "datum", "partnerName", "belegnummer", "richtung", "betrag",
                        "waehrung", "faelligkeit", "vertraulichkeit", "tags", "sicherheit");
        assertThat(anfrage.path("response_format").path("json_schema").path("schema"))
                .isEqualTo(JSON.readTree(AntwortSchema.json()));
    }

    @Dann("der Aufruf enthält den Belegtext «{}» und die Dokumentarten des Mandanten")
    public void inhalt(String text) {
        String anfrage = anfragen().getFirst().getBodyAsString();
        assertThat(anfrage).contains(text).contains("LIEFERANTENBELEG").contains("AUSGANGSRECHNUNG");
    }

    /** Req 3.12: weder der Name noch seine Wörter in der Form von {@code archiv_dateiname_text}, ohne Gross/klein. */
    @Dann("wurde das Sprachmodell aufgerufen, aber kein Aufruf enthält «{}» oder «{}»")
    public void ohneDateiname(String erstes, String zweites) {
        assertThat(anfragen()).isNotEmpty().allSatisfy(a -> assertThat(a.getBodyAsString())
                .doesNotContainIgnoringCase(erstes).doesNotContainIgnoringCase(zweites));
    }

    @Dann("der Aufruf enthält ein Seitenbild als JPEG")
    public void seitenbild() {
        assertThat(anfragen().getFirst().getBodyAsString()).contains("data:image/jpeg;base64,");
    }

    @Dann("der Aufruf enthält die IBAN «{}» aus dem Swiss QR Code")
    public void qrImAufruf(String iban) {
        assertThat(anfragen().getFirst().getBodyAsString()).contains(iban);
    }

    @Dann("der zweite Aufruf nennt den Fehlerhinweis «{}»")
    public void nachfrageText(String grund) {
        assertThat(anfragen().get(0).getBodyAsString()).doesNotContain(grund);
        assertThat(anfragen().get(1).getBodyAsString()).contains(grund);
    }

    @Dann("das Dokument ist «{word}» mit der Art «{word}» und der Vertraulichkeit «{word}»")
    public void vorschlag(String status, String art, String vertraulichkeit) throws Exception {
        Map<String, Object> dokument = hilfe.kiVorschlag(MANDANT, id);
        assertThat(dokument).containsEntry("status", status).containsEntry("bestaetigt", null);
        JsonNode vorschlag = JSON.readTree((String) dokument.get("ki_vorschlag"));
        assertThat(vorschlag.path("art").asText()).isEqualTo(art);
        assertThat(vorschlag.path("vertraulichkeit").asText()).isEqualTo(vertraulichkeit);
    }

    @Dann("das Dokument ist «{word}» ohne Vorschlag")
    public void ohneVorschlag(String status) throws Exception {
        Map<String, Object> dokument = hilfe.kiVorschlag(MANDANT, id);
        assertThat(dokument).containsEntry("status", status).containsEntry("ki_vorschlag", null)
                .containsEntry("ki_sicherheit", null).containsEntry("fehler", null);
    }

    @Dann("der Vorschlag enthält Titel, Belegdatum, Partner, Belegnummer, Richtung, Betrag, Währung, Fälligkeit und Tags")
    public void felder() throws Exception {
        JsonNode vorschlag = vorschlag();
        assertThat(vorschlag.path("titel").asText()).isEqualTo("Rechnung Büromaterial");
        assertThat(vorschlag.path("datum").asText()).isEqualTo("2026-03-10");
        assertThat(vorschlag.path("partnerName").asText()).isEqualTo("Muster Bürobedarf AG");
        assertThat(vorschlag.path("belegnummer").asText()).isEqualTo("2026-123");
        assertThat(vorschlag.path("richtung").asText()).isEqualTo("AUSGABE");
        assertThat(vorschlag.path("betrag").decimalValue()).isEqualByComparingTo("123.40");
        assertThat(vorschlag.path("waehrung").asText()).isEqualTo("CHF");
        assertThat(vorschlag.path("faelligkeit").asText()).isEqualTo("2026-04-09");
        assertThat(vorschlag.path("tags")).extracting(JsonNode::asText).containsExactly("buero");
    }

    @Dann("jede Sicherheit des Vorschlags liegt zwischen 0 und 1")
    public void sicherheiten() throws Exception {
        JsonNode sicherheit = sicherheit();
        assertThat(sicherheit.fieldNames()).toIterable().contains("art", "titel", "datum", "partnerName",
                "belegnummer", "richtung", "betrag", "waehrung", "faelligkeit", "vertraulichkeit");
        sicherheit.elements().forEachRemaining(wert ->
                assertThat(wert.decimalValue()).isBetween(BigDecimal.ZERO, BigDecimal.ONE));
    }

    @Dann("der Vorschlag hat Betrag {word}, Währung «{word}», IBAN «{}» und Partner «{}»")
    public void qrWerte(String betrag, String waehrung, String iban, String partner) throws Exception {
        JsonNode vorschlag = vorschlag();
        assertThat(vorschlag.path("betrag").decimalValue()).isEqualByComparingTo(betrag);
        assertThat(vorschlag.path("waehrung").asText()).isEqualTo(waehrung);
        assertThat(vorschlag.path("partnerIban").asText()).isEqualTo(iban);
        assertThat(vorschlag.path("partnerName").asText()).isEqualTo(partner);
    }

    @Dann("die Sicherheit von «{word}» ist {word}")
    public void sicherheit(String feld, String wert) throws Exception {
        assertThat(sicherheit().path(feld).decimalValue()).isEqualByComparingTo(wert);
    }

    @Dann("die Sicherheit von «{word}» ist höchstens {word}")
    public void sicherheitHoechstens(String feld, String wert) throws Exception {
        assertThat(sicherheit().path(feld).decimalValue()).isLessThanOrEqualTo(new BigDecimal(wert));
    }

    @Dann("der Auftrag «KI_VORSCHLAG» des Belegs ist «{word}»")
    public void auftrag(String status) throws Exception {
        assertThat(hilfe.auftragStatus("KI_VORSCHLAG", id)).containsExactly(status);
    }

    @Dann("«ki_verbrauch» enthält {int} Zeile(n) «{word}» mit {int} Tokens ein und {int} Tokens aus")
    public void verbrauch(int anzahl, String zweck, int ein, int aus) throws Exception {
        assertThat(hilfe.kiVerbrauch(MANDANT, verbrauchStand)).hasSize(anzahl)
                .allSatisfy(z -> assertThat(z).containsExactly(zweck, "google/gemma-4-31B-it", ein, aus));
    }

    private void anlegen(String quelle, Quelle datei) throws Exception {
        id = hilfe.anlegen(MANDANT, quelle, List.of(datei));
        hilfe.einreihen(MANDANT, id);
    }

    private List<LoggedRequest> anfragen() {
        return modell.find(postRequestedFor(urlPathEqualTo(PFAD)));
    }

    private JsonNode vorschlag() throws Exception {
        return JSON.readTree((String) hilfe.kiVorschlag(MANDANT, id).get("ki_vorschlag"));
    }

    private JsonNode sicherheit() throws Exception {
        return JSON.readTree((String) hilfe.kiVorschlag(MANDANT, id).get("ki_sicherheit"));
    }

    /** Antwort des Modells nach dem Antwortschema; Werte, die die Nachbearbeitung nicht ändert, sind fest. */
    private static String modellVorschlag(String art, String belegnummer, String vertraulichkeit) throws Exception {
        ObjectNode v = JSON.createObjectNode();
        v.put("art", art);
        v.put("titel", "Rechnung Büromaterial");
        v.put("datum", "2026-03-10");
        v.put("partnerName", "Muster Bürobedarf AG");
        v.putNull("partnerUid");
        v.putNull("partnerIban");
        v.putNull("partnerEmail");
        v.put("belegnummer", belegnummer);
        v.put("richtung", "AUSGABE");
        v.put("betrag", new BigDecimal("123.40"));
        v.put("waehrung", "CHF");
        v.put("faelligkeit", "2026-04-09");
        v.putNull("qrReferenz");
        v.put("vertraulichkeit", vertraulichkeit);
        v.putArray("tags").add("buero");
        ObjectNode s = v.putObject("sicherheit");
        for (String feld : List.of("art", "titel", "datum", "partnerName", "belegnummer", "richtung", "betrag",
                "waehrung", "faelligkeit", "vertraulichkeit")) {
            s.put(feld, new BigDecimal("0.95"));
        }
        return JSON.writeValueAsString(v);
    }

    /** Chat-Completion-Antwort der OpenAI-kompatiblen Schnittstelle mit {@code inhalt} als Nachricht. */
    private static String antwort(String inhalt) throws Exception {
        ObjectNode a = JSON.createObjectNode();
        a.put("id", "test-" + UUID.randomUUID());
        a.put("object", "chat.completion");
        a.put("created", 1773565200L);
        a.put("model", "google/gemma-4-31B-it");
        ObjectNode wahl = a.putArray("choices").addObject();
        wahl.put("index", 0);
        wahl.putObject("message").put("role", "assistant").put("content", inhalt);
        wahl.put("finish_reason", "stop");
        a.putObject("usage").put("prompt_tokens", 1200).put("completion_tokens", 80).put("total_tokens", 1280);
        return JSON.writeValueAsString(a);
    }

    /** Swiss Payments Code 2.0 mit Testwerten (wie {@code ZxingQrLeserTest}). */
    private static String spc(String betrag) {
        return String.join("\n", "SPC", "0200", "1", "CH4431999123000889012", "S", "Lieferant GmbH", "Teststrasse",
                "1", "6000", "Luzern", "CH", "", "", "", "", "", "", "", betrag, "CHF", "S", "Testfirma AG",
                "Testweg", "2", "6460", "Altdorf", "CH", "QRR", "210000000003139471430009017", "Rechnung", "EPD");
    }

    private static String kennung() {
        return "Lauf " + HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4));
    }
}
