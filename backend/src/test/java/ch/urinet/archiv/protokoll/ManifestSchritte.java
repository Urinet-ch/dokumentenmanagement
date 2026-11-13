package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.adapter.in.kommando.ManifestKommando;
import ch.urinet.archiv.protokoll.application.ManifestErzeugen;
import ch.urinet.archiv.protokoll.domain.Jcs;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zum Tages-Manifest (Aufgabe 5.2, features/protokoll/manifest.feature); nur synthetische Daten. */
public class ManifestSchritte {

    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    ManifestKommando kommando;

    @ConfigProperty(name = "archiv.test.tsa-port")
    Optional<Integer> tsaPort;

    @ConfigProperty(name = "archiv.manifest.oeffentlicher-schluessel")
    Optional<String> oeffentlicherSchluessel;

    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    private final Map<String, String> mandanten = new HashMap<>();
    private final Map<String, UUID> dokumente = new HashMap<>();
    private final List<StubMapping> stoerungen = new ArrayList<>();
    /** Erwartete kanonische Zeile je {@code <tabelle>/<id>}, unabhängig von {@code JdbcManifestDaten} gebildet. */
    private final Map<String, Map<String, Object>> zeilen = new LinkedHashMap<>();
    private long auftrag;
    private List<String> versionenVorher = List.of();
    private String ausgabe = "";

    @After("@spec-01 and (@req-15.1 or @req-15.4 or @req-15.6 or @req-15.7 or @req-15.8 or @req-15.11 or @req-16.7)")
    public void aufraeumen() {
        if (!stoerungen.isEmpty()) {
            WireMock admin = new WireMock("localhost", tsaPort.orElseThrow());
            stoerungen.forEach(admin::removeStubMapping);
            stoerungen.clear();
        }
    }

    /** Fester Code mit Test-Seed unter {@code seed/<code>/}; Alias = Code. */
    @Angenommen("der Mandant «{word}» ist am «{word}» angelegt")
    public void mandant(String code, String datum) throws SQLException {
        provisionierung.anlegen(code, null, null);
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "update public.mandant set erstellt = ? where code = ?")) {
            p.setTimestamp(1, Timestamp.from(zeit(datum, "08:00")));
            p.setString(2, code);
            assertThat(p.executeUpdate()).isEqualTo(1);
        }
        mandanten.put(code, code);
    }

    @Angenommen("im Mandanten «{word}» wurde das Dokument «{word}» am «{word}» um «{word}» archiviert, aufzubewahren bis «{word}»")
    public void dokument(String alias, String name, String datum, String uhrzeit, String aufbewahrenBis)
            throws SQLException {
        UUID id = UUID.randomUUID();
        String schema = schema(alias);
        try (Connection v = datenbank.get().getConnection()) {
            try (PreparedStatement p = v.prepareStatement("insert into " + schema + ".dokument (id, quelle, "
                    + "erstellt_von, status, archiviert, aufbewahren_bis) values (?, 'UPLOAD', 'test', 'ARCHIVIERT', ?, ?)")) {
                p.setObject(1, id);
                p.setTimestamp(2, Timestamp.from(zeit(datum, uhrzeit)));
                p.setObject(3, LocalDate.parse(aufbewahrenBis, DATUM));
                p.executeUpdate();
            }
            for (String rolle : List.of("ORIGINAL", "ARBEITSFASSUNG")) {
                try (PreparedStatement p = v.prepareStatement("insert into " + schema + ".dokument_datei "
                        + "(dokument_id, nr, rolle, sha256, s3_schluessel, s3_version) values (?, 1, ?, ?, ?, ?)")) {
                    p.setObject(1, id);
                    p.setString(2, rolle);
                    p.setString(3, zufallsHash());
                    p.setString(4, "archiv/2026/" + id + "/" + rolle.toLowerCase());
                    p.setString(5, "v-" + rolle.toLowerCase());
                    p.executeUpdate();
                }
            }
        }
        dokumente.put(alias + "/" + name, id);
    }

    @Angenommen("im Mandanten «{word}» steht am «{word}» um «{word}» ein Eintrag im Zugriffsprotokoll")
    public void protokoll(String alias, String datum, String uhrzeit) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "insert into " + schema(alias) + ".zugriff_protokoll (zeit, benutzer, rolle, aktion) "
                        + "values (?, 'test', 'INHABER', 'ANZEIGEN')")) {
            p.setTimestamp(1, Timestamp.from(zeit(datum, uhrzeit)));
            p.executeUpdate();
        }
    }

    @Angenommen("das Dokument «{word}» von «{word}» hat eine PDF\\/A-Fassung")
    public void pdfa(String name, String alias) throws SQLException {
        UUID id = dokumente.get(alias + "/" + name);
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement("update "
                + schema(alias) + ".dokument set sha256_pdfa = ?, s3_pdfa = ?, s3_version_pdfa = 'v-pdfa' where id = ?")) {
            p.setString(1, zufallsHash());
            p.setString(2, "archiv/2026/" + id + "/pdfa.pdf");
            p.setObject(3, id);
            assertThat(p.executeUpdate()).isEqualTo(1);
        }
    }

    @Angenommen("im Mandanten «{word}» steht am «{word}» um «{word}» ein Eintrag im Zugriffsprotokoll zum Dokument «{word}» mit IP und Details")
    public void protokollVoll(String alias, String datum, String uhrzeit, String name) throws Exception {
        UUID dokument = dokumente.get(alias + "/" + name);
        Instant zeit = zeit(datum, uhrzeit);
        String details = "{\"seite\": 2, \"grund\": \"Prüfung «Blatt»\", \"felder\": [\"betrag\", \"datum\"]}";
        long id;
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "insert into " + schema(alias) + ".zugriff_protokoll (zeit, benutzer, rolle, aktion, dokument_id, ip, "
                        + "details) values (?, 'inhaber', 'INHABER', 'HERUNTERLADEN', ?, '192.0.2.7', cast(? as jsonb)) "
                        + "returning id")) {
            p.setTimestamp(1, Timestamp.from(zeit));
            p.setObject(2, dokument);
            p.setString(3, details);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                id = r.getLong(1);
            }
        }
        Map<String, Object> zeile = new LinkedHashMap<>();
        zeile.put("id", id);
        zeile.put("zeit", zeit.toString());
        zeile.put("benutzer", "inhaber");
        zeile.put("rolle", "INHABER");
        zeile.put("aktion", "HERUNTERLADEN");
        zeile.put("dokument_id", dokument.toString());
        zeile.put("ip", "192.0.2.7/32");
        zeile.put("details", JSON.readValue(details, Map.class));
        zeilen.put("zugriff_protokoll/" + id, zeile);
    }

    @Angenommen("im Mandanten «{word}» steht am «{word}» um «{word}» eine Metadaten-Version des Dokuments «{word}»")
    public void version(String alias, String datum, String uhrzeit, String name) throws Exception {
        UUID dokument = dokumente.get(alias + "/" + name);
        Instant zeit = zeit(datum, uhrzeit);
        String daten = "{\"betrag\": \"1234.50\", \"waehrung\": \"CHF\", \"bestaetigt\": true, \"leer\": null}";
        long id;
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "insert into " + schema(alias) + ".dokument_version (zeit, dokument_id, version, daten, geaendert_von, "
                        + "grund) values (?, ?, 3, cast(? as jsonb), 'inhaber', 'Probe') returning id")) {
            p.setTimestamp(1, Timestamp.from(zeit));
            p.setObject(2, dokument);
            p.setString(3, daten);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                id = r.getLong(1);
            }
        }
        Map<String, Object> zeile = new LinkedHashMap<>();
        zeile.put("id", id);
        zeile.put("zeit", zeit.toString());
        zeile.put("dokument_id", dokument.toString());
        zeile.put("version", 3L);
        zeile.put("daten", JSON.readValue(daten, Map.class));
        zeile.put("geaendert_von", "inhaber");
        zeile.put("grund", "Probe");
        zeilen.put("dokument_version/" + id, zeile);
    }

    @Angenommen("der Pfad «{word}» des Zeitstempeldiensts antwortet mit HTTP {int}")
    public void pfadStoerung(String pfad, int status) {
        WireMock admin = new WireMock("localhost", tsaPort.orElseThrow());
        stoerungen.add(admin.register(any(urlPathEqualTo(pfad)).atPriority(1)
                .willReturn(aResponse().withStatus(status))));
    }

    @Angenommen("der Zeitstempeldienst «{word}» antwortet mit HTTP {int}")
    public void stoerung(String dienst, int status) {
        WireMock admin = new WireMock("localhost", tsaPort.orElseThrow());
        stoerungen.add(admin.register(any(urlPathMatching("/" + dienst + "/.*")).atPriority(1)
                .willReturn(aResponse().withStatus(status))));
    }

    @Wenn("der Manifest-Lauf für «{word}» bis «{word}» läuft")
    public void lauf(String alias, String bis) throws Exception {
        versionenVorher = TestAblage.versionen(code(alias), "manifest/");
        auftrag = auftraege.rohEinreihen(code(alias), ManifestErzeugen.TYP,
                "{\"" + ManifestErzeugen.BIS_TAG + "\": " + LocalDate.parse(bis, DATUM).toEpochDay() + "}");
        auftraege.abarbeiten();
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
    }

    @Wenn("der Manifest-Lauf für «{word}» bis «{word}» nochmals läuft")
    public void nochmals(String alias, String bis) throws Exception {
        lauf(alias, bis);
    }

    @Wenn("der Manifest-Lauf für «{word}» bis «{word}» bis zum letzten Versuch läuft")
    public void bisZumLetztenVersuch(String alias, String bis) throws Exception {
        auftrag = auftraege.rohEinreihen(code(alias), ManifestErzeugen.TYP,
                "{\"" + ManifestErzeugen.BIS_TAG + "\": " + LocalDate.parse(bis, DATUM).toEpochDay() + "}");
        for (int runde = 0; runde < 5; runde++) {
            auftraege.abarbeiten();
            if (!auftraege.zeile(auftrag).status().equals("OFFEN")) {
                return;
            }
            auftraege.aendern(auftrag, "nicht_vor = now()");
        }
    }

    @Dann("endet der Auftrag im Status «{word}» nach {int} Versuchen")
    public void auftragEndet(String status, int versuche) throws SQLException {
        AuftragTestHilfe.Zeile zeile = auftraege.zeile(auftrag);
        assertThat(zeile.status()).as(zeile.toString()).isEqualTo(status);
        if (status.equals("FEHLER")) {
            assertThat(zeile.versuche()).isEqualTo(versuche);
        } else {
            // erfolgreicher letzter Versuch: zwei Fehlversuche gezählt
            assertThat(zeile.versuche()).isEqualTo(versuche - 1);
        }
    }

    @Dann("hat «{word}» genau je ein Manifest für die Tage vom «{word}» bis «{word}»")
    public void manifeste(String alias, String von, String bis) throws SQLException {
        List<LocalDate> erwartet = new ArrayList<>();
        for (LocalDate d = LocalDate.parse(von, DATUM); !d.isAfter(LocalDate.parse(bis, DATUM)); d = d.plusDays(1)) {
            erwartet.add(d);
        }
        assertThat(daten(alias, "select datum from %s.manifest order by datum")).containsExactlyElementsOf(erwartet);
        for (LocalDate d : erwartet) {
            assertThat(TestAblage.versionen(code(alias), "manifest/" + d + ".json")).hasSize(1);
        }
    }

    @Dann("den Tag «{word}» holt für «{word}» ein Folgeauftrag mit neuen Versuchen")
    public void folgeauftrag(String datum, String alias) throws SQLException {
        long bisTag = LocalDate.parse(datum, DATUM).toEpochDay();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select status, versuche from public.auftrag where mandant = ? and typ = ? "
                        + "and id > ? and (nutzlast ->> ?)::bigint = ?")) {
            abfrage.setString(1, code(alias));
            abfrage.setString(2, ManifestErzeugen.TYP.wert());
            abfrage.setLong(3, auftrag);
            abfrage.setString(4, ManifestErzeugen.BIS_TAG);
            abfrage.setLong(5, bisTag);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).as("Folgeauftrag").isTrue();
                assertThat(zeile.getString("status")).isEqualTo("OFFEN");
                assertThat(zeile.getInt("versuche")).isLessThan(Wiederholung.MAX_VERSUCHE - 1);
                assertThat(zeile.next()).as("nur ein Folgeauftrag").isFalse();
            }
        }
    }

    @Dann("«{word}» hat kein Manifest")
    public void keinManifest(String alias) throws SQLException {
        assertThat(daten(alias, "select datum from %s.manifest")).isEmpty();
        assertThat(daten(alias, "select datum from %s.manifest_zeitstempel")).isEmpty();
    }

    @Dann("die Manifeste von «{word}» wurden in Datumsreihenfolge erzeugt")
    public void reihenfolge(String alias) throws SQLException {
        List<Object> nachErstellung = daten(alias, "select datum from %s.manifest order by erstellt, datum");
        List<Object> nachDatum = daten(alias, "select datum from %s.manifest order by datum");
        assertThat(nachErstellung).containsExactlyElementsOf(nachDatum);
    }

    @Dann("unter «manifest\\/» von «{word}» ist keine Datei dazugekommen")
    public void nichtsDazu(String alias) {
        assertThat(TestAblage.versionen(code(alias), "manifest/")).containsExactlyInAnyOrderElementsOf(versionenVorher);
    }

    @Dann("das Manifest «{word}» von «{word}» beginnt die Kette mit «vorher_sha256» null")
    public void kettenanfang(String datum, String alias) throws Exception {
        JsonNode m = manifest(alias, datum);
        assertThat(m.get("vorher_sha256").isNull()).isTrue();
        assertThat(m.get("vorher").isNull()).isTrue();
        assertThat(m.get("von").isNull()).isTrue();
    }

    @Dann("das Manifest «{word}» von «{word}» enthält das Dokument «{word}» mit den Varianten «{word}» samt SHA-256 und Versions-ID")
    public void eintrag(String datum, String alias, String name, String varianten) throws Exception {
        UUID id = dokumente.get(alias + "/" + name);
        Map<String, String[]> erwartet = new HashMap<>();
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select lower(rolle), sha256, s3_version from " + schema(alias) + ".dokument_datei where dokument_id = ? "
                        + "union all select 'pdfa', sha256_pdfa, s3_version_pdfa from " + schema(alias)
                        + ".dokument where id = ? and sha256_pdfa is not null")) {
            p.setObject(1, id);
            p.setObject(2, id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    erwartet.put(r.getString(1), new String[] {r.getString(2), r.getString(3)});
                }
            }
        }
        List<String> gefunden = new ArrayList<>();
        for (JsonNode e : manifest(alias, datum).get("eintraege")) {
            if (e.get("dokument").asText().equals(id.toString())) {
                String variante = e.get("variante").asText();
                gefunden.add(variante);
                assertThat(e.get("sha256").asText()).isEqualTo(erwartet.get(variante)[0]);
                assertThat(e.get("s3_version").asText()).isEqualTo(erwartet.get(variante)[1]);
            }
        }
        assertThat(gefunden).containsExactlyInAnyOrder(varianten.split(","));
    }

    @Dann("das Manifest «{word}» von «{word}» enthält den Hash von {int} Zeile\\(n) aus «{word}» und {int} Zeile\\(n) aus «{word}»")
    public void protokolle(String datum, String alias, int anzahlA, String tabelleA, int anzahlB, String tabelleB)
            throws Exception {
        JsonNode prot = manifest(alias, datum).get("protokolle");
        assertThat(prot.get(tabelleA).get("anzahl").asInt()).isEqualTo(anzahlA);
        assertThat(prot.get(tabelleB).get("anzahl").asInt()).isEqualTo(anzahlB);
        // je Zeile ein Blatt (design.md «Manifest»), Wurzel = SHA-256 über die Blätter in id-Reihenfolge
        for (Map.Entry<String, Integer> t : Map.of(tabelleA, anzahlA, tabelleB, anzahlB).entrySet()) {
            List<Object> blaetter = daten(alias, "select sha256 from %s.protokoll_blatt where tabelle = '" + t.getKey()
                    + "' and manifest_datum = '" + LocalDate.parse(datum, DATUM) + "' order by zeile_id");
            assertThat(blaetter).as(t.getKey()).hasSize(t.getValue());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            blaetter.forEach(b -> sha.update(HEX.parseHex((String) b)));
            assertThat(prot.get(t.getKey()).get("sha256").asText()).as(t.getKey()).isEqualTo(HEX.formatHex(sha.digest()));
        }
    }

    @Dann("das Blatt jeder Protokollzeile mit IP, Details oder Metadaten von «{word}» ist der SHA-256 ihrer kanonischen Form über alle Spalten")
    public void blaetter(String alias) throws Exception {
        assertThat(zeilen).isNotEmpty();
        for (Map.Entry<String, Map<String, Object>> e : zeilen.entrySet()) {
            String[] teile = e.getKey().split("/");
            List<Object> blatt = daten(alias, "select sha256 from %s.protokoll_blatt where tabelle = '" + teile[0]
                    + "' and zeile_id = " + Long.parseLong(teile[1]));
            assertThat(blatt).as(e.getKey()).hasSize(1);
            assertThat(blatt.getFirst()).as(e.getKey()).isEqualTo(sha256(Jcs.utf8(e.getValue())));
            // jede Spalte zählt: ein anderer Wert ergäbe ein anderes Blatt
            for (String spalte : e.getValue().keySet()) {
                Map<String, Object> geaendert = new LinkedHashMap<>(e.getValue());
                geaendert.put(spalte, "geändert");
                assertThat(sha256(Jcs.utf8(geaendert))).as(e.getKey() + " " + spalte).isNotEqualTo(blatt.getFirst());
            }
        }
        assertThat(daten(alias, "select count(*) from %s.protokoll_blatt")).containsExactly(
                daten(alias, "select (select count(*) from %s.zugriff_protokoll) + (select count(*) from "
                        + schema(alias) + ".dokument_version)").getFirst());
    }

    @Dann("das Manifest «{word}» von «{word}» nennt nur den eigenen Mandanten und genau seine Dokumente")
    public void nurEigene(String datum, String alias) throws Exception {
        JsonNode m = manifest(alias, datum);
        assertThat(m.get("mandant").asText()).isEqualTo(code(alias));
        Set<String> erwartet = new TreeSet<>();
        dokumente.forEach((schluessel, id) -> {
            if (schluessel.startsWith(alias + "/")) {
                erwartet.add(id.toString());
            }
        });
        Set<String> gefunden = new TreeSet<>();
        m.get("eintraege").forEach(e -> gefunden.add(e.get("dokument").asText()));
        assertThat(gefunden).isEqualTo(erwartet);
    }

    @Dann("das Manifest «{word}» von «{word}» hält nur den Zeitstempel von «{word}» fest, unter «manifest\\/» liegt «{word}», aber kein «{word}»")
    public void nurFestgehalten(String datum, String alias, String dienst, String vorhanden, String fehlt)
            throws SQLException {
        LocalDate tag = LocalDate.parse(datum, DATUM);
        assertThat(daten(alias, "select dienst from %s.manifest_zeitstempel where datum = '" + tag + "'"))
                .containsExactly(dienst);
        assertThat(TestAblage.versionen(code(alias), "manifest/" + tag + "." + vorhanden)).hasSize(1);
        assertThat(TestAblage.versionen(code(alias), "manifest/" + tag + "." + fehlt)).isEmpty();
    }

    @Wenn("unter «{word}» von «{word}» eine weitere Version abgelegt wird")
    public void weitereVersion(String schluessel, String alias) {
        TestAblage.neueVersion(code(alias), schluessel, "{}");
    }

    @Dann("die Manifeste von «{word}» bilden eine Hash-Kette, die Signatur, Zeitstempel und Prüfdaten des Vortags erneuert")
    public void kette(String alias) throws Exception {
        List<Object> daten = daten(alias, "select datum from %s.manifest order by datum");
        assertThat(daten).hasSizeGreaterThan(1);
        for (int i = 1; i < daten.size(); i++) {
            LocalDate tag = (LocalDate) daten.get(i);
            LocalDate vortag = (LocalDate) daten.get(i - 1);
            JsonNode m = manifest(alias, tag);
            JsonNode vorher = manifest(alias, vortag);
            assertThat(m.get("vorher_sha256").asText()).isEqualTo(sha256(datei(alias, vortag, "json")));
            assertThat(m.get("von").asText()).isEqualTo(vorher.get("bis").asText());
            assertThat(m.get("vorher").get("sig_sha256").asText()).isEqualTo(sha256(datei(alias, vortag, "sig")));
            List<String> dienste = new ArrayList<>();
            for (JsonNode z : m.get("vorher").get("zeitstempel")) {
                String dienst = z.get("dienst").asText();
                dienste.add(dienst);
                assertThat(z.get("tsr_sha256").asText()).isEqualTo(sha256(datei(alias, vortag, dienst + ".tsr")));
                String pruef = dienst.equals("haupt") ? "ocsp.der" : "crl.der";
                assertThat(z.get("pruefdaten_sha256")).extracting(JsonNode::asText).containsExactly(
                        sha256(datei(alias, vortag, dienst + ".kette.pem")),
                        sha256(datei(alias, vortag, dienst + "." + pruef)));
            }
            assertThat(dienste).containsExactly("haupt", "zweit");
        }
    }

    @Dann("jedes Manifest von «{word}» ist mit dem öffentlichen Manifest-Schlüssel gültig signiert")
    public void signiert(String alias) throws Exception {
        String b64 = oeffentlicherSchluessel.orElseThrow().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PublicKey schluessel = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        for (Object d : daten(alias, "select datum from %s.manifest order by datum")) {
            LocalDate tag = (LocalDate) d;
            assertThat(ManifestSignatur.pruefen(schluessel, datei(alias, tag, "json"), datei(alias, tag, "sig")))
                    .as(tag.toString()).isTrue();
        }
    }

    @Dann("jedes Manifest von «{word}» trägt je einen gültigen Zeitstempel von «{word}» und «{word}» über SHA-256 von Manifest und Signatur")
    public void gestempelt(String alias, String erster, String zweiter) throws Exception {
        for (Object d : daten(alias, "select datum from %s.manifest order by datum")) {
            LocalDate tag = (LocalDate) d;
            byte[] imprint = ManifestSignatur.imprint(datei(alias, tag, "json"), datei(alias, tag, "sig"));
            for (String dienst : List.of(erster, zweiter)) {
                TimeStampToken token = new TimeStampResponse(datei(alias, tag, dienst + ".tsr")).getTimeStampToken();
                assertThat(token.getTimeStampInfo().getMessageImprintDigest()).isEqualTo(imprint);
                assertThat(token.getTimeStampInfo().getPolicy().getId()).isEqualTo(TsaTestRessource.POLICY);
            }
            assertThat(daten(alias, "select dienst from %s.manifest_zeitstempel where datum = '" + tag
                    + "' order by dienst")).containsExactly(erster, zweiter);
        }
    }

    @Dann("das Manifest «{word}» von «{word}» trägt nur einen Zeitstempel von «{word}»")
    public void nurEinStempel(String datum, String alias, String dienst) throws SQLException {
        LocalDate tag = LocalDate.parse(datum, DATUM);
        assertThat(daten(alias, "select dienst from %s.manifest_zeitstempel where datum = '" + tag + "'"))
                .containsExactly(dienst);
        assertThat(TestAblage.versionen(code(alias), "manifest/" + tag + "."))
                .filteredOn(k -> k.endsWith(".tsr@" + k.substring(k.indexOf('@') + 1)))
                .extracting(k -> k.substring(0, k.indexOf('@')))
                .containsExactly("manifest/" + tag + "." + dienst + ".tsr");
    }

    @Dann("enthält die Anfrage an «{word}» für das Manifest «{word}» von «{word}» nur den Hash, Nonce und «certReq»")
    public void nurHash(String dienst, String datum, String alias) throws Exception {
        LocalDate tag = LocalDate.parse(datum, DATUM);
        byte[] imprint = ManifestSignatur.imprint(datei(alias, tag, "json"), datei(alias, tag, "sig"));
        List<LoggedRequest> anfragen = new WireMock("localhost", tsaPort.orElseThrow())
                .find(postRequestedFor(urlPathEqualTo("/" + dienst + "/tsr")));
        List<TimeStampRequest> passend = new ArrayList<>();
        for (LoggedRequest anfrage : anfragen) {
            TimeStampRequest tsq = new TimeStampRequest(anfrage.getBody());
            if (Arrays.equals(tsq.getMessageImprintDigest(), imprint)) {
                passend.add(tsq);
            }
        }
        assertThat(passend).hasSize(1);
        TimeStampRequest tsq = passend.getFirst();
        assertThat(tsq.getMessageImprintAlgOID().getId()).isEqualTo("2.16.840.1.101.3.4.2.1");
        assertThat(tsq.getNonce()).isNotNull();
        assertThat(tsq.getCertReq()).isTrue();
        assertThat(tsq.getReqPolicy()).isNull();
        assertThat(tsq.hasExtensions()).isFalse();
        // Anfrage nur aus Version, Hash, Nonce und certReq: neu kodiert gleich lang wie der gesendete Body
        TimeStampRequest minimal = new org.bouncycastle.tsp.TimeStampRequestGenerator() {
            {
                setCertReq(true);
            }
        }.generate(org.bouncycastle.tsp.TSPAlgorithms.SHA256, imprint, tsq.getNonce());
        assertThat(tsq.getEncoded()).isEqualTo(minimal.getEncoded());
    }

    @Dann("liegen für das Manifest «{word}» von «{word}» {int} Dateien unter «manifest\\/», jede im Modus «{word}» gesperrt")
    public void gesperrt(String datum, String alias, int anzahl, String modus) {
        LocalDate tag = LocalDate.parse(datum, DATUM);
        List<String> versionen = TestAblage.versionen(code(alias), "manifest/" + tag + ".");
        assertThat(versionen).hasSize(anzahl);
        for (String kv : versionen) {
            String[] teile = kv.split("@", 2);
            ObjectLockRetention sperre = TestAblage.sperre(code(alias), teile[0], teile[1]);
            assertThat(sperre.modeAsString()).as(kv).isEqualTo(modus);
            assertThat(sperre.retainUntilDate()).as(kv).isAfter(Instant.now());
        }
    }

    @Dann("die Datenbank von «{word}» referenziert das Manifest «{word}» mit Pfad, Versions-ID und Sperre")
    public void referenziert(String alias, String datum) throws Exception {
        LocalDate tag = LocalDate.parse(datum, DATUM);
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select s3_schluessel, s3_version, sha256, sperre_bis from " + schema(alias) + ".manifest where datum = ?")) {
            p.setObject(1, tag);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("manifest/" + tag + ".json");
                assertThat(TestAblage.versionen(code(alias), r.getString(1)))
                        .containsExactly(r.getString(1) + "@" + r.getString(2));
                assertThat(r.getString(3)).isEqualTo(sha256(datei(alias, tag, "json")));
                // Soll-Frist per Unit-Test (ManifestErzeugenTest.sperre); in test ist S3 auf einen Tag gekappt
                assertThat(r.getObject(4, OffsetDateTime.class).toInstant())
                        .isEqualTo(TestAblage.sperre(code(alias), r.getString(1), r.getString(2)).retainUntilDate());
            }
        }
    }

    @Dann("das Fenster des Manifests «{word}» von «{word}» umfasst {int} Stunden")
    public void fenster(String datum, String alias, int stunden) throws SQLException {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                "select von, bis from " + schema(alias) + ".manifest where datum = ?")) {
            p.setObject(1, LocalDate.parse(datum, DATUM));
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                Duration dauer = Duration.between(r.getObject(1, OffsetDateTime.class),
                        r.getObject(2, OffsetDateTime.class));
                assertThat(dauer).isEqualTo(Duration.ofHours(stunden));
            }
        }
    }

    @Dann("das Prüfwerkzeug meldet für «{word}» vom «{word}» bis «{word}» «{}» mit Exit-Code {int}")
    public void pruefwerkzeug(String alias, String von, String bis, String ergebnis, int exitCode) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        int code = kommando.ausfuehren(List.of("pruefen", LocalDate.parse(von, DATUM).toString(),
                        LocalDate.parse(bis, DATUM).toString(), "--mandant", code(alias)),
                new PrintStream(aus, true, StandardCharsets.UTF_8), new PrintStream(fehler, true, StandardCharsets.UTF_8));
        ausgabe = aus.toString(StandardCharsets.UTF_8);
        assertThat(code).as(ausgabe + fehler.toString(StandardCharsets.UTF_8)).isEqualTo(exitCode);
        assertThat(ausgabe).contains(ergebnis);
    }

    @Dann("die Ausgabe des Prüfwerkzeugs nennt «{}»")
    public void ausgabe(String text) {
        assertThat(ausgabe).contains(text);
    }

    @Dann("hat der Inhaber eine Mail zum fehlenden Manifest «{word}» von «{word}» erhalten, die nur IDs nennt")
    public void mail(String datum, String alias) throws SQLException {
        LocalDate tag = LocalDate.parse(datum, DATUM);
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        Response nachrichten = given().baseUri(mailpit)
                .queryParam("query", "subject:\"Manifest " + tag + " für " + code(alias) + "\"").when()
                .get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<String> ids = nachrichten.jsonPath().getList("messages.ID");
        assertThat(ids).as(nachrichten.asString()).hasSize(1);
        Response nachricht = given().baseUri(mailpit).when().get("/api/v1/message/" + ids.getFirst());
        assertThat(nachricht.jsonPath().<String>getList("To.Address")).containsExactly("inhaber@example.invalid");
        String text = nachricht.jsonPath().getString("Text");
        assertThat(text).contains(auftraege.zeile(auftrag).fehlerId());
        for (UUID id : dokumente.values()) {
            assertThat(text).doesNotContain(id.toString());
        }
        for (Object sha : daten(alias, "select sha256 from %s.dokument_datei")) {
            assertThat(text).doesNotContain((String) sha);
        }
    }

    private JsonNode manifest(String alias, String datum) throws Exception {
        return manifest(alias, LocalDate.parse(datum, DATUM));
    }

    private JsonNode manifest(String alias, LocalDate tag) throws Exception {
        return JSON.readTree(datei(alias, tag, "json"));
    }

    /** Einzige Version von {@code manifest/<tag>.<endung>}. */
    private byte[] datei(String alias, LocalDate tag, String endung) {
        String schluessel = "manifest/" + tag + "." + endung;
        List<String> versionen = TestAblage.versionen(code(alias), schluessel).stream()
                .filter(k -> k.startsWith(schluessel + "@")).toList();
        assertThat(versionen).as(schluessel).hasSize(1);
        return TestAblage.lesen(code(alias), schluessel, versionen.getFirst().substring(schluessel.length() + 1));
    }

    private List<Object> daten(String alias, String sqlMitSchema) throws SQLException {
        List<Object> liste = new ArrayList<>();
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(
                String.format(sqlMitSchema, schema(alias))); ResultSet r = p.executeQuery()) {
            while (r.next()) {
                Object wert = r.getObject(1);
                liste.add(wert instanceof java.sql.Date d ? d.toLocalDate() : wert);
            }
        }
        return liste;
    }

    private String code(String alias) {
        String code = mandanten.get(alias);
        assertThat(code).as("Mandant " + alias).isNotNull();
        return code;
    }

    private String schema(String alias) {
        return new MandantCode(code(alias)).schema();
    }

    private static Instant zeit(String datum, String uhrzeit) {
        return LocalDateTime.of(LocalDate.parse(datum, DATUM), LocalTime.parse(uhrzeit)).atZone(ZUERICH).toInstant();
    }

    private static String sha256(byte[] inhalt) throws Exception {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt));
    }

    private static String zufallsHash() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return HEX.formatHex(b);
    }
}
