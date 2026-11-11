package ch.urinet.archiv.dokument;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schritte zu Aufgabe 3.4 (Req 2.13, 2.15, 2.16, 2.22, 5.9, 6.3, 6.4, 6.6–6.8, 6.13, 8.2, 8.3, 8.5, 8.8–8.10, 9.8):
 * Bestätigen, Verwerfen, Wiederholen über die REST-Schnittstelle und {@code ARCHIVIEREN} über den Worker. Dokumente
 * liegen wie nach der Verarbeitung mit Original und PDF/A unter {@code eingang/}; nur synthetische Inhalte (Req 16.15).
 */
public class BestaetigenSchritte {

    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    private static final String PDF = "application/pdf";

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    VerarbeitungTestHilfe verarbeitung;

    @Inject
    AblagePort ablage;

    @Inject
    MandantBucket buckets;

    @Inject
    S3Client s3;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    int mailpitPort;

    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<String, Ort> kontakte = new LinkedHashMap<>();
    private final Map<UUID, String> echtePruefsumme = new HashMap<>();
    private Response antwort;

    @After
    public void aufraeumen() throws Exception {
        for (Ort ort : dokumente.values()) {
            auftraege.ausfuehren("delete from public.auftrag where nutzlast ->> 'dokumentId' = '" + ort.id() + "'");
            if (!"ARCHIVIERT".equals(spalte(ort, "status"))) {
                ablage.loeschenEingang(new MandantCode(ort.mandant()), VerarbeitungTestHilfe.JAHR, ort.id());
            }
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        for (Ort kontakt : kontakte.values()) {
            auftraege.ausfuehren("delete from " + schema(kontakt.mandant()) + ".kontakt where id = '" + kontakt.id()
                    + "'");
        }
        dokumente.clear();
        kontakte.clear();
        echtePruefsumme.clear();
    }

    @Angenommen("die Archivierung ist bereit")
    public void bereit() throws SQLException {
        auftraege.vorbereiten();
        auftraege.ausfuehren("delete from public.auftrag where typ in ('ARCHIVIEREN', 'VERARBEITEN', 'KI_VORSCHLAG')");
        for (String mandant : List.of("urinet", "vhf-gsk")) {
            buckets.einrichten(bucket(mandant));
        }
    }

    @Angenommen("im Mandanten «{word}» liegt das Dokument «{word}» im Status «{word}», erfasst von «{word}»")
    public void dokument(String mandant, String name, String status, String erfasser) throws Exception {
        UUID id = mitOriginal(mandant, name, status, erfasser);
        AbgelegteDatei pdfa = ablage.ablegenEingang(new MandantCode(mandant),
                DateiSchluessel.pdfa(VerarbeitungTestHilfe.JAHR, id), new ByteArrayInputStream(inhalt("PDF/A")),
                inhalt("PDF/A").length);
        ausfuehren("update " + schema(mandant) + ".dokument set sha256_pdfa = ?, s3_pdfa = ?, s3_version_pdfa = ?, "
                + "seiten = 1 where id = ?", pdfa.sha256(), VerarbeitungTestHilfe.pdfaSchluessel(id), pdfa.versionId(), id);
    }

    @Angenommen("im Mandanten «{word}» liegt das Dokument «{word}» ohne PDF\\/A im Eingangskorb, erfasst von «{word}»")
    public void ohnePdfa(String mandant, String name, String erfasser) throws Exception {
        UUID id = mitOriginal(mandant, name, "EINGANGSKORB", erfasser);
        ausfuehren("update " + schema(mandant) + ".dokument set pdfa_fehlt = true, "
                + "fehler = 'Keine PDF/A-Fassung möglich' where id = ?", id);
    }

    /** Bestätigte Art mit Vertraulichkeit NORMAL: sichtbar für jede Rolle des Mandanten (A2), auch im Eingangskorb. */
    @Angenommen("das Dokument «{word}» ist für alle Rollen sichtbar")
    public void sichtbar(String name) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("update " + schema(ort.mandant()) + ".dokument set art = 'KORRESPONDENZ', vertraulichkeit = 'NORMAL', "
                + "bestaetigt = now(), bestaetigt_von = 'probe' where id = ?", ort.id());
    }

    @Angenommen("im Mandanten «{word}» gibt es den Kontakt «{word}»")
    public void kontakt(String mandant, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        ausfuehren("insert into " + schema(mandant) + ".kontakt (id, typ, name, name_normalisiert, quelle) "
                + "values (?, 'ORGANISATION', ?, ?, 'TEST')", id, name, name.toLowerCase());
        kontakte.put(name, new Ort(mandant, id));
    }

    @Angenommen("das Dokument «{word}» hat die Art «{word}», die Belegnummer «{word}» und den Partner «{word}»")
    public void beleg(String name, String art, String belegnummer, String partner) throws SQLException {
        Ort ort = ort(name);
        String schema = schema(ort.mandant());
        ausfuehren("update " + schema + ".dokument set art = ?, partner_id = ?, partner_name = ? where id = ?", art,
                kontakte.get(partner).id(), partner, ort.id());
        ausfuehren("insert into " + schema + ".dokument_finanz (dokument_id, belegnummer) values (?, ?)", ort.id(),
                belegnummer);
    }

    @Angenommen("die Belegnummer «{word}» der Art «{word}» gehört dem Dokument «{word}»")
    public void belegnummerEigen(String belegnummer, String art, String name) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("insert into " + schema(ort.mandant()) + ".belegnummer_eigen (art, belegnummer, dokument_id) "
                + "values (?, ?, ?)", art, belegnummer, ort.id());
    }

    @Angenommen("die gespeicherte Prüfsumme des Originals von «{word}» ist verfälscht")
    public void verfaelschen(String name) throws Exception {
        Ort ort = ort(name);
        echtePruefsumme.put(ort.id(), (String) zeile("select sha256 from " + schema(ort.mandant())
                + ".dokument_datei where dokument_id = ? and rolle = 'ORIGINAL'", ort.id()).get("sha256"));
        ausfuehren("update " + schema(ort.mandant()) + ".dokument_datei set sha256 = ? where dokument_id = ? "
                + "and rolle = 'ORIGINAL'", VerarbeitungTestHilfe.sha256(inhalt("falsch")), ort.id());
    }

    @Angenommen("die gespeicherte Prüfsumme des Originals von «{word}» ist wieder richtig")
    public void berichtigen(String name) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("update " + schema(ort.mandant()) + ".dokument_datei set sha256 = ? where dokument_id = ? "
                + "and rolle = 'ORIGINAL'", echtePruefsumme.get(ort.id()), ort.id());
    }

    @Wenn("«{word}» das Dokument «{word}» so bestätigt:")
    public void bestaetigen(String benutzer, String name, Map<String, String> felder) throws SQLException {
        antwort = mitVersion(benutzer, name).contentType(ContentType.JSON).body(body(felder))
                .when().post("/api/v1/dokumente/" + ort(name).id() + "/bestaetigen");
    }

    @Wenn("«{word}» das Dokument «{word}» ohne If-Match bestätigt")
    public void ohneIfMatch(String benutzer, String name) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(Map.of("art", "QUITTUNG")).when().post("/api/v1/dokumente/" + ort(name).id() + "/bestaetigen");
    }

    @Wenn("«{word}» das Dokument «{word}» mit einer veralteten Version bestätigt")
    public void veraltet(String benutzer, String name) throws SQLException {
        int version = ((Number) spalte(ort(name), "version")).intValue();
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"" + (version - 1) + "\"")
                .contentType(ContentType.JSON).body(Map.of("art", "QUITTUNG"))
                .when().post("/api/v1/dokumente/" + ort(name).id() + "/bestaetigen");
    }

    @Wenn("«{word}» das Dokument «{word}» mit dem Grund {string} verwirft")
    public void verwerfen(String benutzer, String name, String grund) throws SQLException {
        antwort = mitVersion(benutzer, name).contentType(ContentType.JSON).body(Map.of("grund", grund))
                .when().post("/api/v1/dokumente/" + ort(name).id() + "/verwerfen");
    }

    @Wenn("«{word}» für das Dokument «{word}» «wiederholen» wählt")
    public void wiederholen(String benutzer, String name) throws SQLException {
        antwort = mitVersion(benutzer, name).when().post("/api/v1/dokumente/" + ort(name).id() + "/wiederholen");
    }

    @Wenn("der Worker die Archivierung ausführt")
    public void worker() throws Exception {
        auftraege.abarbeiten();
    }

    @Wenn("«{word}» das Dokument «{word}» mit Warnungen liest")
    public void lesen(String benutzer, String name) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                .get("/api/v1/dokumente/" + ort(name).id());
    }

    @Wenn("«{word}» den Eingangskorb mit Warnungen abruft")
    public void eingangskorb(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("groesse", 100)
                .when().get("/api/v1/eingangskorb");
    }

    @Dann("meldet das Archiv Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("meldet das Archiv Status {int} mit Fehlercode «{word}»")
    public void fehlercode(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status, "felder")).as(antwort.asString()).isEqualTo(code);
    }

    @Dann("nennt die Antwort die fehlenden Felder {string}")
    public void felder(String felder) {
        assertThat(antwort.jsonPath().<String>getList("felder"))
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(felder.split(",\\s*")));
    }

    @Dann("trägt die Antwort das ETag der neuen Version von «{word}»")
    public void etag(String name) throws SQLException {
        assertThat(antwort.header("ETag")).isEqualTo("\"" + spalte(ort(name), "version") + "\"");
    }

    @Dann("ist das Dokument «{word}» im Status «{word}»")
    public void dokumentStatus(String name, String status) throws SQLException {
        assertThat(spalte(ort(name), "status")).isEqualTo(status);
    }

    /** Spalten von {@code dokument} als Text; {@code null} heisst leer. */
    @Dann("hat das Dokument «{word}» diese Werte:")
    public void werte(String name, Map<String, String> erwartet) throws SQLException {
        for (Map.Entry<String, String> e : erwartet.entrySet()) {
            assertThat(String.valueOf(spalte(ort(name), e.getKey()))).as(e.getKey())
                    .isEqualTo(e.getValue() == null ? "null" : e.getValue());
        }
    }

    @Dann("hat das Dokument «{word}» die Belegnummer «{word}» mit Betrag {word} {word}")
    public void finanz(String name, String belegnummer, String betrag, String waehrung) throws SQLException {
        Ort ort = ort(name);
        Map<String, Object> f = zeile("select belegnummer, betrag, waehrung from " + schema(ort.mandant())
                + ".dokument_finanz where dokument_id = ?", ort.id());
        assertThat(f.get("belegnummer")).isEqualTo("null".equals(belegnummer) ? null : belegnummer);
        assertThat((BigDecimal) f.get("betrag")).isEqualByComparingTo(new BigDecimal(betrag));
        assertThat(f.get("waehrung")).isEqualTo(waehrung);
    }

    @Dann("ist für das Dokument «{word}» ein Auftrag «{word}» eingereiht")
    public void eingereiht(String name, String typ) throws SQLException {
        assertThat(offeneAuftraege(name, typ)).isEqualTo(1);
    }

    @Dann("ist für das Dokument «{word}» kein Auftrag «{word}» eingereiht")
    public void nichtEingereiht(String name, String typ) throws SQLException {
        assertThat(offeneAuftraege(name, typ)).isZero();
    }

    private long offeneAuftraege(String name, String typ) throws SQLException {
        return ((Number) zeile("select count(*) as anzahl from public.auftrag where typ = ? and status = 'OFFEN' "
                + "and nutzlast ->> 'dokumentId' = ?", typ, ort(name).id().toString()).get("anzahl")).longValue();
    }

    @Dann("liegen alle Dateien von «{word}» unter archiv\\/ mit Versions-ID und Sperre bis mindestens heute")
    public void archiviert(String name) throws SQLException {
        Ort ort = ort(name);
        List<String[]> dateien = archivDateien(ort);
        assertThat(dateien).hasSize(2);
        LocalDate heute = LocalDate.now(ZUERICH);
        for (String[] datei : dateien) {
            assertThat(datei[0]).startsWith("archiv/" + VerarbeitungTestHilfe.JAHR + "/" + ort.id() + "/");
            assertThat(datei[1]).isNotBlank();
            var sperre = s3.getObjectRetention(b -> b.bucket(bucket(ort.mandant())).key(datei[0]).versionId(datei[1]))
                    .retention();
            assertThat(sperre.mode()).isEqualTo(ObjectLockRetentionMode.GOVERNANCE);
            assertThat(sperre.retainUntilDate().atZone(ZUERICH).toLocalDate()).isAfterOrEqualTo(heute);
        }
        Object sperreBis = spalte(ort, "sperre_bis");
        assertThat(sperreBis).isNotNull();
        assertThat(((Date) sperreBis).toLocalDate()).isAfterOrEqualTo(heute);
        assertThat(spalte(ort, "archiviert")).isNotNull();
    }

    @Dann("liegt nur das Original von «{word}» unter archiv\\/")
    public void nurOriginal(String name) throws SQLException {
        Ort ort = ort(name);
        List<ObjectVersion> versionen = verarbeitung.objektversionen(ort.mandant(), archivOrdner(ort.id()));
        assertThat(versionen).singleElement().satisfies(v -> assertThat(v.key()).endsWith("/1-original.pdf"));
        assertThat(spalte(ort, "s3_pdfa")).isNull();
    }

    @Dann("liegen die Dateien von «{word}» unter archiv\\/ ohne Sperre")
    public void ohneSperre(String name) throws SQLException {
        Ort ort = ort(name);
        List<String[]> dateien = archivDateien(ort);
        assertThat(dateien).hasSize(2);
        for (String[] datei : dateien) {
            assertThat(datei[0]).startsWith(archivOrdner(ort.id()));
            assertThatThrownBy(() -> s3.getObjectRetention(b -> b.bucket(bucket(ort.mandant())).key(datei[0])
                    .versionId(datei[1]))).isInstanceOf(S3Exception.class);
        }
        assertThat(spalte(ort, "sperre_bis")).isNull();
    }

    @Dann("liegt unter archiv\\/ nichts von «{word}»")
    public void nichtsImArchiv(String name) {
        Ort ort = ort(name);
        assertThat(verarbeitung.objektversionen(ort.mandant(), archivOrdner(ort.id()))).isEmpty();
    }

    @Dann("ist der Eingang von «{word}» leer")
    public void eingangLeer(String name) {
        Ort ort = ort(name);
        assertThat(verarbeitung.versionen(ort.mandant(), VerarbeitungTestHilfe.eingangOrdner(ort.id()))).isEmpty();
    }

    @Dann("liegen die Dateien von «{word}» unverändert im Eingang")
    public void eingangUnveraendert(String name) throws SQLException {
        Ort ort = ort(name);
        List<String> erwartet = new ArrayList<>();
        for (String[] datei : verarbeitung.dateien(ort.mandant(), ort.id()).values()) {
            erwartet.add(datei[1] + "@" + datei[2]);
        }
        Object pdfa = spalte(ort, "s3_pdfa");
        if (pdfa != null) {
            erwartet.add(pdfa + "@" + spalte(ort, "s3_version_pdfa"));
        }
        assertThat(erwartet).allSatisfy(e -> assertThat(e).startsWith(VerarbeitungTestHilfe.eingangOrdner(ort.id())));
        assertThat(verarbeitung.versionen(ort.mandant(), VerarbeitungTestHilfe.eingangOrdner(ort.id())))
                .containsExactlyInAnyOrderElementsOf(erwartet);
    }

    @Dann("enthält das Zugriffsprotokoll für «{word}» je Datei «VERWERFEN» mit Prüfsumme und dem Grund {string}")
    public void protokoll(String name, String grund) throws SQLException {
        Ort ort = ort(name);
        List<String> pruefsummen = new ArrayList<>();
        for (String[] datei : verarbeitung.dateien(ort.mandant(), ort.id()).values()) {
            pruefsummen.add(datei[0]);
        }
        pruefsummen.add((String) spalte(ort, "sha256_pdfa"));
        List<String> protokolliert = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select details ->> 'sha256', details ->> 'grund', benutzer from " + schema(ort.mandant())
                        + ".zugriff_protokoll where dokument_id = ? and aktion = 'VERWERFEN' "
                        + "and details ->> 'sha256' is not null")) {
            abfrage.setObject(1, ort.id());
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    assertThat(z.getString(2)).isEqualTo(grund);
                    assertThat(z.getString(3)).isNotBlank();
                    protokolliert.add(z.getString(1));
                }
            }
        }
        assertThat(protokolliert).containsExactlyInAnyOrderElementsOf(pruefsummen);
    }

    @Dann("hat der Inhaber eine Mail zur gescheiterten Archivierung von «{word}» erhalten, die nur IDs nennt")
    public void mail(String name) throws SQLException {
        Ort ort = ort(name);
        Response nachrichten = given().baseUri("http://localhost:" + mailpitPort)
                .queryParam("query", "subject:\"" + ort.id() + "\"").when().get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<String> ids = nachrichten.jsonPath().getList("messages.ID");
        assertThat(ids).as(nachrichten.asString()).hasSize(1);
        Response nachricht = given().baseUri("http://localhost:" + mailpitPort).when()
                .get("/api/v1/message/" + ids.getFirst());
        assertThat(nachricht.jsonPath().<String>getList("To.Address")).containsExactly("inhaber@example.invalid");
        String text = nachricht.jsonPath().getString("Text");
        assertThat(text).contains(ort.id().toString()).contains(fehlerId((String) spalte(ort, "fehler")));
        assertThat(text).doesNotContain((String) spalte(ort, "titel"));
    }

    @Dann("zeigt das Dokument «{word}» die Warnung «{word}» mit Verweis auf «{word}»")
    public void warnung(String name, String code, String vorhanden) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("id")).isEqualTo(ort(name).id().toString());
        assertThat(antwort.jsonPath().<String>getList("warnungen.code")).containsExactly(code);
        assertThat(antwort.jsonPath().<String>getList("warnungen.vorhandenId"))
                .containsExactly(ort(vorhanden).id().toString());
    }

    @Dann("zeigt das Dokument «{word}» keine Warnung")
    public void keineWarnung(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("warnungen")).isEmpty();
    }

    @Dann("zeigt der Eingangskorb beim Dokument «{word}» die Warnung «{word}» mit Verweis auf «{word}»")
    public void warnungImEingangskorb(String name, String code, String vorhanden) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        String pfad = "eintraege.find { it.id == '" + ort(name).id() + "' }.warnungen";
        assertThat(antwort.jsonPath().<String>getList(pfad + ".code")).containsExactly(code);
        assertThat(antwort.jsonPath().<String>getList(pfad + ".vorhandenId"))
                .containsExactly(ort(vorhanden).id().toString());
    }

    private UUID mitOriginal(String mandant, String name, String status, String erfasser) throws Exception {
        UUID id = verarbeitung.anlegen(mandant, "UPLOAD", status,
                List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf", PDF, inhalt("Original"))));
        dokumente.put(name, new Ort(mandant, id));
        ausfuehren("update " + schema(mandant) + ".dokument set erstellt_von = ? where id = ?",
                Testanmeldung.sub(erfasser), id);
        return id;
    }

    private RequestSpecification mitVersion(String benutzer, String name) throws SQLException {
        return given().auth().oauth2(Testanmeldung.token(benutzer))
                .header("If-Match", "\"" + spalte(ort(name), "version") + "\"");
    }

    private Map<String, Object> body(Map<String, String> felder) {
        Map<String, Object> body = new LinkedHashMap<>();
        felder.forEach((feld, wert) -> body.put(feld.equals("partner") ? "partnerId" : feld, switch (feld) {
            case "partner" -> kontakte.get(wert).id();
            case "betrag" -> new BigDecimal(wert);
            case "geschaeftsjahr" -> Integer.valueOf(wert);
            case "ohnePdfa", "fristAbgelaufenAkzeptiert" -> "ja".equals(wert);
            default -> wert;
        }));
        return body;
    }

    /** Dateien unter {@code archiv/}: Schlüssel und Versions-ID aus {@code dokument_datei} und der PDF/A-Spalte. */
    private List<String[]> archivDateien(Ort ort) throws SQLException {
        List<String[]> dateien = new ArrayList<>();
        for (String[] datei : verarbeitung.dateien(ort.mandant(), ort.id()).values()) {
            dateien.add(new String[]{datei[1], datei[2]});
        }
        if (spalte(ort, "s3_pdfa") != null) {
            dateien.add(new String[]{(String) spalte(ort, "s3_pdfa"), (String) spalte(ort, "s3_version_pdfa")});
        }
        return dateien;
    }

    private static String fehlerId(String fehlertext) {
        assertThat(fehlertext).contains("Fehler-ID ");
        return fehlertext.substring(fehlertext.indexOf("Fehler-ID ") + 10, fehlertext.indexOf("Fehler-ID ") + 46);
    }

    private static byte[] inhalt(String art) {
        return ("%PDF-1.4 synthetisch " + art + " " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
    }

    private Ort ort(String name) {
        Ort ort = dokumente.get(name);
        if (ort == null) {
            throw new IllegalArgumentException("Unbekanntes Testdokument: " + name);
        }
        return ort;
    }

    private Object spalte(Ort ort, String spalte) throws SQLException {
        return zeile("select " + spalte + " from " + schema(ort.mandant()) + ".dokument where id = ?", ort.id())
                .get(spalte);
    }

    private Map<String, Object> zeile(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                abfrage.setObject(i + 1, werte[i]);
            }
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

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }

    private String bucket(String mandant) {
        return new MandantCode(mandant).bucket(bucketPraefix);
    }

    private static String archivOrdner(UUID id) {
        return DateiSchluessel.ordner(Ablageort.ARCHIV, VerarbeitungTestHilfe.JAHR, id);
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
