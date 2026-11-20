package ch.urinet.archiv.wissen;

import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.gemeinsam.domain.PaketUngueltig;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenPaketImport;
import ch.urinet.archiv.wissen.adapter.out.okf.OkfSchreiber;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketBeispiele;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketException;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketLeser;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class WissenImportSchritte {
    private static final String API = "/api/v1/wissensentwuerfe";
    @Inject Instance<AgroalDataSource> datenbank;
    @Inject MandantProvisionierung provisionierung;
    @Inject MandantKontext kontext;
    @Inject WissenPaketImport paketImport;
    private final Map<String, List<UUID>> dokumente = new LinkedHashMap<>();
    private final List<UUID> entwuerfe = new ArrayList<>();
    private final List<String> tokenIds = new ArrayList<>();
    private String pfad;
    private String sha;
    private String fremdeSha;
    private String importSha;
    private Response antwort;
    private UUID entwurf;
    private long kiVorher;
    private long reservierungenVorher;
    private long auftraegeVorher;

    @Angenommen("synthetische Wissensquellen in zwei Mandanten sind für den Paketimport vorhanden")
    public void vorbereiten() throws SQLException {
        pfad = "konzepte/import-" + UUID.randomUUID();
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (anzahl("select count(*) from public.mandant where code = ?", code) == 0) {
                provisionierung.anlegen(code, null, null);
            }
            dokumente.put(code, new ArrayList<>());
            String hash = Pruefsumme.von(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            quelle(code, hash);
            if ("urinet".equals(code)) {
                sha = hash;
            } else {
                fremdeSha = hash;
            }
        }
        kiVorher = anzahl("select count(*) from m_urinet.ki_verbrauch");
        reservierungenVorher = anzahl("select count(*) from m_urinet.ki_verbrauch_reservierung");
        auftraegeVorher = anzahl("select count(*) from public.auftrag");
    }

    @Wenn("der Inhaber ein gültiges lokales Wissenspaket importiert")
    public void importieren() throws Exception {
        importSha = sha;
        antwort = post(paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage.")));
    }

    @Dann("liegt ausschliesslich ein offener Importentwurf mit SHA-Protokoll vor")
    public void nurEntwurf() throws SQLException {
        antwort.then().statusCode(201);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        assertThat(antwort.header("Location")).endsWith(API + "/" + entwurf);
        assertThat(antwort.jsonPath().getString("status")).isEqualTo("OFFEN");
        assertThat(antwort.jsonPath().getInt("anzahl.neu")).isEqualTo(1);
        assertThat(antwort.jsonPath().getList("befunde")).isEmpty();
        var detail = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).get(API + "/" + entwurf)
                .then().statusCode(200).extract().jsonPath();
        assertThat(detail.getString("entwurf.herkunft.paketSha256"))
                .isEqualTo(antwort.jsonPath().getString("paketSha256"));
        assertThat(detail.getString("entwurf.herkunft.kopf.erzeugtMit")).isEqualTo("lokal");
        assertThat(detail.getString("entwurf.herkunft.kopf.erzeugtVon")).isEqualTo("probe");
        assertThat(detail.getString("aenderungen[0].daten.quellenPruefung[0].zuordnung")).isEqualTo("ZUGEORDNET");
        assertThat(detail.getString("aenderungen[0].daten.quellenPruefung[0].auszug")).isEqualTo("GEFUNDEN");
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad = ?", pfad)).isZero();
        assertThat(anzahl("select count(*) from m_urinet.ki_verbrauch")).isEqualTo(kiVorher);
        assertThat(anzahl("select count(*) from m_urinet.ki_verbrauch_reservierung")).isEqualTo(reservierungenVorher);
        assertThat(anzahl("select count(*) from public.auftrag")).isEqualTo(auftraegeVorher);
        assertThat(anzahl("select count(*) from public.auftrag where nutzlast->>'entwurfId' = ?",
                entwurf.toString())).isZero();
        assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where id = ? and anlass = 'IMPORT'",
                entwurf)).isEqualTo(1);
        assertThat(anzahl("""
                select count(*) from m_urinet.zugriff_protokoll
                where dokument_id = ? and aktion = 'WISSEN_IMPORT'
                  and details->>'sha256' = ? and (details->>'seiten')::int = 1
                  and not jsonb_exists(details, 'inhalt') and not jsonb_exists(details, 'titel')
                """, entwurf, antwort.jsonPath().getString("paketSha256"))).isEqualTo(1);
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).get(API + "/" + entwurf)
                .then().statusCode(404);
    }

    @Wenn("der Inhaber die importierte Änderung mit ihrem ETag annimmt")
    public void annehmen() {
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(204);
    }

    @Dann("ist eine belegte Seite mit externer Provenienz und Import-Abschlusslog freigegeben")
    public void freigegeben() throws SQLException {
        assertThat(anzahl("""
                select count(*) from m_urinet.wissensseite
                where pfad = ? and version = 1 and erzeugt_von = 'extern:probe'
                  and vertraulichkeit_mindest = 'NORMAL'
                """, pfad)).isEqualTo(1);
        assertThat(anzahl("select count(*) from m_urinet.wissens_log where entwurf_id = ? and aktion = 'IMPORT'",
                entwurf)).isEqualTo(1);
        assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where id = ? and status = 'ERLEDIGT'",
                entwurf)).isEqualTo(1);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).post(API + "/" + entwurf + "/neu-pruefen")
                .then().statusCode(409);
    }

    @Wenn("das Wissenspaket die Quellenabweichung {string} enthält")
    public void abweichung(String fall) throws Exception {
        importSha = sha;
        String auszug = "Seite 1";
        switch (fall) {
            case "unbekannt" -> importSha = Pruefsumme.von(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            case "fremd" -> importSha = fremdeSha;
            case "PERSONAL" -> ausfuehren("update m_urinet.dokument set vertraulichkeit = 'PERSONAL' where id = ?",
                    dokumente.get("urinet").getFirst());
            case "falsche Art" -> ausfuehren("update m_urinet.dokument set art = 'AUSGANGSRECHNUNG' where id = ?",
                    dokumente.get("urinet").getFirst());
            case "Auszug fehlt" -> auszug = null;
            case "Auszug falsch" -> auszug = "Nicht belegter Text";
            case "Seitentext fehlt" -> ausfuehren("update m_urinet.dokument set s3_pdfa = null where id = ?",
                    dokumente.get("urinet").getFirst());
            default -> throw new IllegalArgumentException("Unbekannter Testfall");
        }
        antwort = post(paket("lokal", "NORMAL", seite(pfad, importSha, auszug, null, "Aussage.")));
        antwort.then().statusCode(201);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
    }

    @Dann("blockiert der Befund {string} die Freigabe des Importentwurfs")
    public void blockiert(String code) throws SQLException {
        assertThat(antwort.jsonPath().getList("befunde.code", String.class)).contains(code);
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(422);
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad = ?", pfad)).isZero();
    }

    @Dann("löst eine spätere Quellenaufnahme die Neuprüfung aus und entwertet alte ETags")
    public void spaetereQuelle() throws Exception {
        blockiert("QUELLE_UNBEKANNT");
        quelle("urinet", importSha);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .post(API + "/" + entwurf + "/neu-pruefen").then().statusCode(204);
        var detail = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).get(API + "/" + entwurf)
                .then().statusCode(200).extract().jsonPath();
        assertThat(detail.getList("aenderungen[0].befunde")).isEmpty();
        assertThat(detail.getString("aenderungen[0].daten.quellenPruefung[0].zuordnung")).isEqualTo("ZUGEORDNET");
        assertThat(detail.getString("aenderungen[0].daten.quellenPruefung[0].auszug")).isEqualTo("GEFUNDEN");
        assertThat(detail.getInt("aenderungen[0].etag")).isEqualTo(1);
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(412);
        entscheiden(entwurf, 1, 1, "annehmen", "{}").then().statusCode(204);
    }

    @Dann("werden gleiche Pakete auch gleichzeitig nur einmal angelegt und die Importquote bleibt getrennt")
    public void wiederholungUndQuote() throws Exception {
        byte[] zip = paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage."));
        List<Response> ergebnisse;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> anfrage(zip));
            var b = pool.submit(() -> anfrage(zip));
            ergebnisse = List.of(a.get(), b.get());
        }
        assertThat(ergebnisse.stream().map(Response::statusCode)).containsExactlyInAnyOrder(201, 409);
        Response erfolgreich = ergebnisse.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
        entwurf = UUID.fromString(erfolgreich.jsonPath().getString("entwurfId"));
        entwuerfe.add(entwurf);
        Response doppelt = ergebnisse.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertThat(doppelt.jsonPath().getString("entwurfId")).isEqualTo(entwurf.toString());
        UUID ingest = UUID.randomUUID();
        entwuerfe.add(ingest);
        ausfuehren("""
                insert into m_urinet.wissens_entwurf (id,anlass,status,erstellt_von,dokument_id,erstellt)
                values (?,'INGEST','OFFEN','probe',?,now())
                """, ingest, dokumente.get("urinet").getFirst());
        for (int i = 1; i < 5; i++) {
            post(paket("lokal", "NORMAL", seite(pfad + "-" + i, sha, "Seite 1", null, "Aussage.")))
                    .then().statusCode(201);
        }
        byte[] sechstes = paket("lokal", "NORMAL", seite(pfad + "-6", sha, "Seite 1", null, "Aussage."));
        post(sechstes).then().statusCode(409);
        entscheiden(entwurf, 1, 0, "verwerfen", "{\"grund\":\"Synthetischer Test\"}").then().statusCode(204);
        assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where id = ? and status = 'ABGELEHNT'",
                entwurf)).isEqualTo(1);
        post(sechstes).then().statusCode(201);
    }

    @Dann("erzwingt der Import die Quellenstufe und verwirft unzulässige Cloud-Kontexte")
    public void stufen() throws Exception {
        ausfuehren("update m_urinet.dokument set vertraulichkeit = 'INTERN' where id = ?",
                dokumente.get("urinet").getFirst());
        antwort = post(paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage.")));
        antwort.then().statusCode(201);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        assertThat(antwort.jsonPath().getList("hinweise.code", String.class)).contains("STUFE_IGNORIERT");
        entscheiden(entwurf, 1, 0, "annehmen", "{\"vertraulichkeit\":\"NORMAL\"}").then().statusCode(422);
        ausfuehren("update m_urinet.dokument set vertraulichkeit = 'NORMAL' where id = ?",
                dokumente.get("urinet").getFirst());
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).post(API + "/" + entwurf + "/neu-pruefen")
                .then().statusCode(204);
        assertThat(anzahl("select count(*) from m_urinet.wissens_aenderung where entwurf_id = ? "
                + "and vertraulichkeit_kontext = 'INTERN'", entwurf)).isEqualTo(1);
        entscheiden(entwurf, 1, 1, "annehmen", "{\"vertraulichkeit\":\"NORMAL\"}").then().statusCode(422);
        entscheiden(entwurf, 1, 1, "annehmen", "{}").then().statusCode(204);
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad = ? "
                + "and vertraulichkeit_mindest = 'INTERN'", pfad)).isEqualTo(1);
        ausfuehren("update m_urinet.dokument set vertraulichkeit = 'INTERN' where id = ?",
                dokumente.get("urinet").getFirst());
        pfad += "-cloud";
        antwort = post(paket("cloud", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage.")));
        antwort.then().statusCode(201);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        blockiert("CLOUD_STUFE_UNZULAESSIG");
        post(paket("cloud", "INTERN", seite(pfad + "-kopf", sha, "Seite 1", null, "Aussage.")))
                .then().statusCode(422);
    }

    @Dann("unterscheidet der Import neue geänderte und unveränderte Seiten ohne Seitenbereichsfehler")
    public void abgleich() throws Exception {
        String zweite = pfad + "-gleich";
        antwort = post(paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage."),
                seite(zweite, sha, "Seite 1", null, "Aussage.")));
        antwort.then().statusCode(201);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(204);
        entscheiden(entwurf, 2, 0, "annehmen", "{}").then().statusCode(204);
        antwort = post(paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", 1L, "Geänderte Aussage."),
                seite(zweite, sha, "Seite 1", 1L, "Aussage."),
                seite(pfad + "-neu", sha, "Seite 1", null, "Aussage.")));
        antwort.then().statusCode(201);
        assertThat(antwort.jsonPath().getInt("anzahl.neu")).isEqualTo(1);
        assertThat(antwort.jsonPath().getInt("anzahl.aendern")).isEqualTo(1);
        assertThat(antwort.jsonPath().getInt("anzahl.unveraendert")).isEqualTo(1);
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad in (?,?) and version = 1",
                pfad, zweite)).isEqualTo(2);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(204);
        antwort = post(paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", 1L, "Weitere Aussage.")));
        antwort.then().statusCode(201);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        assertThat(anzahl("select count(*) from m_urinet.wissens_aenderung where entwurf_id = ? "
                + "and nr = 1 and status = 'VERALTET' and basis_version = 1", entwurf)).isEqualTo(1);
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(409);
    }

    @Dann("wird eine inzwischen geänderte Quelle beim Annehmen erneut geprüft")
    public void quelleVorAnnahme() throws Exception {
        importieren();
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        ausfuehren("update m_urinet.dokument set vertraulichkeit = 'PERSONAL' where id = ?",
                dokumente.get("urinet").getFirst());
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(422);
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad = ?", pfad)).isZero();
    }

    @Dann("rollenfremde Benutzer und API-Tokens können kein Wissenspaket importieren")
    public void rechte() throws Exception {
        byte[] zip = paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage."));
        for (String rolle : List.of("mitarbeiter", "treuhaender")) {
            given().auth().oauth2(Testanmeldung.token(rolle + "-urinet"))
                    .multiPart("paket", "wissen.zip", zip, "application/zip").post(API + "/import")
                    .then().statusCode(403);
        }
        for (String scope : List.of("generator", "import")) {
            var erstellt = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .contentType("application/json").body(Map.of("name", "Import-Probe", "scope", scope))
                    .post("/api/v1/api-tokens").then().statusCode(201).extract().jsonPath();
            tokenIds.add(erstellt.getString("id"));
            given().auth().oauth2(erstellt.getString("token"))
                    .multiPart("paket", "wissen.zip", zip, "application/zip").post(API + "/import")
                    .then().statusCode(403);
        }
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk"))
                .multiPart("paket", "wissen.zip", zip, "application/zip").post(API + "/import")
                .then().statusCode(422).body("befunde[0].code", org.hamcrest.Matchers.equalTo("MANDANT"));
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad = ?", pfad)).isZero();
    }

    @Dann("ein Fehler nach dem ersten Speicherschritt rollt den gesamten Wissensimport zurück")
    public void rollback() throws Exception {
        byte[] zip = paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage."),
                seite(pfad + "-zweite", sha, "Seite 1", null, "Aussage."));
        String hash = Pruefsumme.von(zip);
        String name = "probe_import_" + UUID.randomUUID().toString().replace("-", "");
        try {
            ausfuehren("""
                    create function m_urinet.%s() returns trigger language plpgsql as $$
                    begin
                      if new.nr = 2 and exists (select 1 from m_urinet.wissens_entwurf
                        where id = new.entwurf_id and paket_sha256 = '%s') then
                        raise exception 'Synthetischer Importabbruch';
                      end if;
                      return new;
                    end $$
                    """.formatted(name, hash));
            ausfuehren("create trigger " + name + " before insert on m_urinet.wissens_aenderung "
                    + "for each row execute function m_urinet." + name + "()");
            post(zip).then().statusCode(500);
            assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where paket_sha256 = ?", hash)).isZero();
            assertThat(anzahl("select count(*) from m_urinet.wissens_aenderung where pfad in (?,?)",
                    pfad, pfad + "-zweite")).isZero();
            assertThat(anzahl("select count(*) from m_urinet.zugriff_protokoll "
                    + "where aktion = 'WISSEN_IMPORT' and details->>'sha256' = ?", hash)).isZero();
        } finally {
            ausfuehren("drop trigger if exists " + name + " on m_urinet.wissens_aenderung");
            ausfuehren("drop function if exists m_urinet." + name + "()");
        }
    }

    @Dann("ein überschrittener Zeitrahmen beendet den Wissensimport ohne Entwurf")
    public void zeitgrenze() throws Exception {
        byte[] zip = paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage."));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var abbruch = pool.submit(() -> kontext.mit(new MandantCode("urinet"),
                    () -> paketImport.importieren(new Benutzer("probe", Rolle.INHABER), "urinet", zip,
                            System.nanoTime() - 1, null)));
            assertThatExceptionOfType(ExecutionException.class).isThrownBy(() -> abbruch.get(10, TimeUnit.SECONDS))
                    .satisfies(e -> assertThat(e.getCause()).isInstanceOfSatisfying(PaketUngueltig.class,
                            p -> assertThat(p.befunde()).containsExactly(Map.of("code", "ZEIT", "pfad", ""))));
        }
        assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where paket_sha256 = ?",
                Pruefsumme.von(zip))).isZero();
    }

    @Dann("eine blockierte Speicherung überschreitet auch nach SQL-Zeitlimitwechseln nicht den Importrahmen")
    public void zeitgrenzeBeimSchreiben() throws Exception {
        byte[] zip = paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, "Aussage."));
        String hash = Pruefsumme.von(zip);
        String name = "probe_import_zeit_" + UUID.randomUUID().toString().replace("-", "");
        try {
            ausfuehren("""
                    create function m_urinet.%s() returns trigger language plpgsql as $$
                    begin
                      if exists (select 1 from m_urinet.wissens_entwurf
                        where id = new.entwurf_id and paket_sha256 = '%s') then
                        perform pg_sleep(2);
                      end if;
                      return new;
                    end $$
                    """.formatted(name, hash));
            ausfuehren("create trigger " + name + " before insert on m_urinet.wissens_aenderung "
                    + "for each row execute function m_urinet." + name + "()");
            try (var pool = Executors.newSingleThreadExecutor()) {
                long beginn = System.nanoTime();
                var abbruch = pool.submit(() -> kontext.mit(new MandantCode("urinet"),
                        () -> paketImport.importieren(new Benutzer("probe", Rolle.INHABER), "urinet", zip,
                                beginn + TimeUnit.MILLISECONDS.toNanos(250), null)));
                assertThatExceptionOfType(ExecutionException.class)
                        .isThrownBy(() -> abbruch.get(5, TimeUnit.SECONDS))
                        .satisfies(e -> assertThat(e.getCause()).isInstanceOfSatisfying(PaketUngueltig.class,
                                p -> assertThat(p.befunde()).containsExactly(
                                        Map.of("code", "ZEIT", "pfad", "paket.yaml"))));
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginn)).isLessThan(1000);
            }
            assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where paket_sha256 = ?", hash)).isZero();
            assertThat(anzahl("select count(*) from m_urinet.wissens_aenderung where pfad = ?", pfad)).isZero();
            assertThat(anzahl("select count(*) from m_urinet.zugriff_protokoll "
                    + "where aktion = 'WISSEN_IMPORT' and details->>'sha256' = ?", hash)).isZero();
        } finally {
            ausfuehren("drop trigger if exists " + name + " on m_urinet.wissens_aenderung");
            ausfuehren("drop function if exists m_urinet." + name + "()");
        }
    }

    @Dann("Multipart und ZIP-Grenze werden ohne Paketinhalt in Antwort oder Log geprüft")
    public void eingangsformatUndLogs() throws Exception {
        String marker = "SYNTHETISCHER_PAKETINHALT_" + UUID.randomUUID();
        byte[] zip = paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", null, marker));
        try (var log = LogMitschnitt.starten()) {
            var gueltig = post(zip);
            assertThat(gueltig.statusCode()).isEqualTo(201);
            assertFalse(gueltig.asString().contains(marker), "Importantwort enthält Paketinhalt");
            assertThat(given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .multiPart("andererTeil", "wissen.zip", zip, "application/zip")
                    .post(API + "/import").statusCode()).isEqualTo(400);
            assertThat(given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .multiPart("paket", "eins.zip", zip, "application/zip")
                    .multiPart("paket", "zwei.zip", zip, "application/zip")
                    .post(API + "/import").statusCode()).isEqualTo(400);
            var keinZip = post(marker.getBytes(StandardCharsets.UTF_8));
            assertThat(keinZip.statusCode()).isEqualTo(415);
            assertFalse(keinZip.asString().contains(marker), "Problemantwort enthält Paketinhalt");
            assertThat(post(new byte[10 * 1024 * 1024]).statusCode()).isEqualTo(415);
            assertThat(post(new byte[10 * 1024 * 1024 + 1]).statusCode()).isEqualTo(413);
            assertFalse(log.volltext().contains(marker), "Importlog enthält Paketinhalt");
        }
    }

    @Dann("ein OKF-Export mit lokaler Herkunft ist ohne ID-Zuordnung als Wissenspaket importierbar")
    public void rundlaufLokalerExport() throws Exception {
        UUID dokument = dokumente.get("urinet").getFirst();
        UUID fremdesDokument = UUID.randomUUID();
        Instant jetzt = Instant.now();
        var seite = new Wissensseite(UUID.randomUUID(), pfad, Seitentyp.KONZEPT, "Probe", "Beschreibung",
                List.of(), Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag("q-1", fremdesDokument, 1, null, "Seite 1", null)),
                Wissensseite.Vertraulichkeit.NORMAL, "Aussage.[^q-1]", 1, "human:probe", jetzt,
                "probe", jetzt, false, false, null);
        var paket = new OkfSchreiber.Paket(new MandantCode("urinet"), "urinet-archiv", "lokal", jetzt,
                "lokale-quelle-" + UUID.randomUUID(), Set.of(pfad));
        var bytes = new ByteArrayOutputStream();
        OkfSchreiber.schreiben(paket, List.of(new OkfSchreiber.Seite(seite,
                Map.of(fremdesDokument, new OkfSchreiber.Dokument(fremdesDokument, sha, "Probe", jetzt)), Map.of())),
                List.of(), bytes);
        var gelesen = new WissensPaketLeser().lesen(bytes.toByteArray());
        assertThat(gelesen.kopf().erzeugtMit()).isEqualTo("lokal");
        assertThat(gelesen.seiten().getFirst().sources().getFirst().sha256()).isEqualTo(sha);
        antwort = post(bytes.toByteArray());
        antwort.then().statusCode(201);
        assertThat(antwort.jsonPath().getList("befunde")).isEmpty();
        assertThat(antwort.jsonPath().getInt("anzahl.aendern")).isZero();
        assertThat(antwort.jsonPath().getInt("anzahl.neu")).isEqualTo(1);
        assertThat(antwort.jsonPath().getList("hinweise.code", String.class)).contains("BASIS_UNBEKANNT");
        assertThat(antwort.jsonPath().getInt("anzahl.ignoriert")).isEqualTo(2);
        entwurf = UUID.fromString(antwort.jsonPath().getString("entwurfId"));
        assertThat(anzahl("select count(*) from m_urinet.wissens_aenderung where entwurf_id = ? "
                + "and aktion = 'NEU' and basis_version is null", entwurf)).isEqualTo(1);
        var detail = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .get(API + "/" + entwurf).then().statusCode(200).extract().jsonPath();
        assertThat(detail.getString("aenderungen[0].daten.quellen[0].dokumentId")).isEqualTo(dokument.toString());
        entscheiden(entwurf, 1, 0, "annehmen", "{}").then().statusCode(204);
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad = ? "
                + "and version = 1 and erzeugt_von = 'extern:urinet-archiv'", pfad)).isEqualTo(1);
    }

    @Dann("eine neue Seite mit Basisversion {long} verwirft das gesamte Wissenspaket")
    public void ungueltigeNeueBasis(long version) throws Exception {
        ungueltigeNeueBasis(version, ConfigProvider.getConfig().getValue("archiv.wissen.instanz-id", String.class));
    }

    public void ungueltigeNeueBasis(long version, String instanz) throws Exception {
        byte[] zip = paket("lokal", "NORMAL", instanz,
                seite(pfad, sha, "Seite 1", null, "Aussage."),
                seite(pfad + "-ungueltig", sha, "Seite 1", version, "Aussage."));
        var abbruch = post(zip);
        abbruch.then().statusCode(422).body("befunde[0].code", org.hamcrest.Matchers.equalTo("FORMAT"));
        String hash = Pruefsumme.von(zip);
        assertThat(anzahl("select count(*) from m_urinet.wissens_entwurf where paket_sha256 = ?", hash)).isZero();
        assertThat(anzahl("select count(*) from m_urinet.wissens_aenderung where pfad in (?,?)",
                pfad, pfad + "-ungueltig")).isZero();
        assertThat(anzahl("select count(*) from m_urinet.wissensseite where pfad in (?,?)",
                pfad, pfad + "-ungueltig")).isZero();
        assertThat(anzahl("select count(*) from m_urinet.zugriff_protokoll "
                + "where aktion = 'WISSEN_IMPORT' and details->>'sha256' = ?", hash)).isZero();
    }

    @Wenn("der Inhaber eine neue Wissensseite mit Basisversion 0 importiert")
    public void neueBasisNull() throws Exception {
        antwort = post(paket("lokal", "NORMAL", seite(pfad, sha, "Seite 1", 0L, "Aussage.")));
    }

    public void regelgleichheit(WissensPaketBeispiele.Beispiel beispiel) {
        String code = null;
        List<Map<String, String>> erwarteteHinweise = List.of();
        try {
            var gelesen = new WissensPaketLeser().lesen(beispiel.zip());
            erwarteteHinweise = gelesen.hinweise().stream()
                    .map(h -> Map.of("code", h.code().name(), "pfad", h.pfad())).toList();
        } catch (WissensPaketException e) {
            code = e.code().name();
        }
        assertThat(code).as(beispiel.name()).isEqualTo(beispiel.readerCode());
        var ergebnis = post(beispiel.zip());
        if (code == null) {
            ergebnis.then().statusCode(201);
            assertThat(ergebnis.jsonPath().getList("befunde.code", String.class)).containsAll(beispiel.befunde());
            assertThat(ergebnis.jsonPath().getList("hinweise")).containsAll(erwarteteHinweise);
        } else {
            int status = switch (code) {
                case "DATEI_ZU_GROSS" -> 413;
                case "DATEITYP_UNBEKANNT" -> 415;
                default -> 422;
            };
            ergebnis.then().statusCode(status);
            if (status == 422) {
                assertThat(ergebnis.jsonPath().getList("befunde.code", String.class)).contains(code);
            }
        }
    }

    @Dann("der Paketleser und der REST-Import beurteilen die gemeinsamen Beispielpakete gleich")
    public void gemeinsameRegeln() throws Exception {
        for (var beispiel : WissensPaketBeispiele.alle()) {
            regelgleichheit(beispiel);
        }
    }

    private byte[] paket(String erzeugtMit, String stufe, Seite... seiten) throws Exception {
        return paket(erzeugtMit, stufe,
                ConfigProvider.getConfig().getValue("archiv.wissen.instanz-id", String.class), seiten);
    }

    private byte[] paket(String erzeugtMit, String stufe, String instanz, Seite... seiten) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            eintrag(zip, "paket.yaml", """
                    paket_format: 1
                    mandant: urinet
                    erzeugt_von: probe
                    erzeugt_mit: %s
                    kontext_stufe: %s
                    erzeugt_am: '2026-11-20T12:00:00Z'
                    %s""".formatted(erzeugtMit, stufe,
                    instanz == null ? "" : "herkunft:\n  instanz: " + instanz + "\n"));
            for (Seite seite : seiten) {
                eintrag(zip, seite.pfad() + ".md", """
                        ---
                        type: Konzept
                        title: Probe
                        description: Beschreibung.
                        tags: []
                        stale_after: null
                        %ssources:
                          - id: q-1
                            sha256: %s
                            seite: '1'
                        %s---
                        %s[^q-1]
                        """.formatted(seite.basis() == null ? "" : "basis_version: " + seite.basis() + "\n",
                        seite.sha(), seite.auszug() == null ? "" : "    auszug: '" + seite.auszug() + "'\n",
                        seite.inhalt()));
            }
        }
        return bytes.toByteArray();
    }

    private static void eintrag(ZipOutputStream zip, String pfad, String text) throws Exception {
        ZipEntry eintrag = new ZipEntry(pfad);
        eintrag.setTime(0);
        zip.putNextEntry(eintrag);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Seite seite(String pfad, String sha, String auszug, Long basis, String inhalt) {
        return new Seite(pfad, sha, auszug, basis, inhalt);
    }

    private record Seite(String pfad, String sha, String auszug, Long basis, String inhalt) { }

    private Response post(byte[] zip) {
        Response ergebnis = anfrage(zip);
        if (ergebnis.statusCode() == 201) {
            entwuerfe.add(UUID.fromString(ergebnis.jsonPath().getString("entwurfId")));
        }
        return ergebnis;
    }

    private static Response anfrage(byte[] zip) {
        return given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .multiPart("paket", "wissen.zip", zip, "application/zip").post(API + "/import");
    }

    private static Response entscheiden(UUID id, int nr, int etag, String aktion, String body) {
        return given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                .header("If-Match", "\"" + etag + "\"").body(body)
                .post(API + "/" + id + "/aenderungen/" + nr + "/" + aktion);
    }

    private void quelle(String code, String hash) throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(code, "WISSENSQUELLE",
                "NORMAL", true, "probe", null, "ARCHIVIERT"));
        dokumente.get(code).add(id);
        String schema = new MandantCode(code).schema();
        ausfuehren("update " + schema + ".dokument set volltext = 'Seite 1\f', seiten = 1, "
                + "s3_pdfa = 'archiv/probe.pdf', s3_version_pdfa = 'v1', pdfa_fehlt = false where id = ?", id);
        ausfuehren("insert into " + schema + ".dokument_datei (dokument_id,nr,rolle,sha256) "
                + "values (?,1,'ORIGINAL',?)", id, hash);
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (var c = datenbank.get().getConnection(); var p = c.prepareStatement(sql)) {
            binden(p, werte);
            p.executeUpdate();
        }
    }

    private long anzahl(String sql, Object... werte) throws SQLException {
        try (var c = datenbank.get().getConnection(); var p = c.prepareStatement(sql)) {
            binden(p, werte);
            try (var r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getLong(1);
            }
        }
    }

    private static void binden(PreparedStatement p, Object... werte) throws SQLException {
        for (int i = 0; i < werte.length; i++) {
            p.setObject(i + 1, werte[i]);
        }
    }

    @After("@wissen-import")
    public void aufraeumen() throws SQLException {
        if (entwuerfe.isEmpty() && dokumente.isEmpty()) {
            return;
        }
        try (Connection c = datenbank.get().getConnection()) {
            c.setAutoCommit(false);
            try (var p = c.createStatement()) {
                p.execute("set local session_replication_role = replica");
            }
            String neueSeiten = "(select seite_id from m_urinet.wissens_aenderung "
                    + "where entwurf_id = any (?) and aktion = 'NEU' and seite_id is not null)";
            for (String sql : List.of(
                    "delete from public.auftrag where typ = 'WISSEN_EMBEDDING' and nutzlast->>'seiteId' in "
                            + "(select seite_id::text from m_urinet.wissens_aenderung where entwurf_id = any (?))",
                    "delete from m_urinet.wissensseite_version_dokument where seite_id in " + neueSeiten,
                    "delete from m_urinet.wissensseite_version where entwurf_id = any (?)",
                    "delete from m_urinet.wissens_log where entwurf_id = any (?)",
                    "delete from m_urinet.zugriff_protokoll where dokument_id = any (?)",
                    "delete from m_urinet.wissensseite_quelle where seite_id in " + neueSeiten,
                    "delete from m_urinet.wissensseite_dokument where seite_id in " + neueSeiten,
                    "delete from m_urinet.wissensseite_link where von_seite_id in " + neueSeiten,
                    "delete from m_urinet.wissensseite where id in " + neueSeiten,
                    "delete from m_urinet.wissens_aenderung where entwurf_id = any (?)",
                    "delete from m_urinet.wissens_entwurf where id = any (?)")) {
                try (var p = c.prepareStatement(sql)) {
                    p.setArray(1, c.createArrayOf("uuid", entwuerfe.toArray()));
                    p.executeUpdate();
                }
            }
            c.commit();
        }
        for (var eintrag : dokumente.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
        tokenIds.clear();
        entwuerfe.clear();
        dokumente.clear();
    }
}
