package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.application.RegelDatenPort;
import ch.urinet.archiv.konfiguration.application.RegelPruefung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class RegelnSchritte {

    /** Neue Konfigurationsversion mit den Regeln aus der Tabelle {@code regel}, sonst wie die letzte (wie 046). */
    private static final String VERSION_MIT_REGELN = """
            insert into konfiguration_version (version, wer, grund, quelle, inhalt, sha256)
            select n.version, 'test:regeln', 'Testregeln', 'APP', i.inhalt,
                encode(sha256(convert_to(i.inhalt::text, 'UTF8')), 'hex')
            from (select coalesce(max(version), 0) + 1 as version from konfiguration_version) n
            cross join lateral (
                select a.inhalt || jsonb_build_object('version', n.version, 'regeln', coalesce((
                    select jsonb_agg(jsonb_build_object('code', r.code, 'art', r.art, 'typ', r.typ,
                        'parameter', r.parameter, 'schwere', r.schwere, 'meldung', r.meldung, 'aktiv', r.aktiv)
                        order by r.code collate "C") from regel r), '[]'::jsonb)) as inhalt
                from (select inhalt from konfiguration_version order by version desc limit 1) a) i
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Eingerichtet(String mandant, String code) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final List<Eingerichtet> eingerichtet = new ArrayList<>();

    private List<Konfiguration.Befund> befunde;
    private List<Regel.Parameter> typisierteRegeln = List.of();
    private List<RegelPruefung.Warnung> warnungen = List.of();
    private RegelVerletzt fehler;
    private Response antwort;
    private int gemerkteVersion;
    private long gemerktesProtokoll;

    @Wenn("die vier Regeltypen der Konfiguration aufgebaut werden")
    public void regeltypenAufbauen() {
        List<Regel> regeln = List.of(
                new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                        Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                        "FEHLER", "Belegdatum fehlt.", true),
                new Regel("nummer_eindeutig", "RECHNUNG", "EINDEUTIG", Map.of("felder", List.of("belegnummer")),
                        "FEHLER", "Nummer bereits erfasst.", true),
                new Regel("nummer_folge", "RECHNUNG", "FOLGE",
                        Map.of("feld", "zusatz.nummer", "zeitraum", "GESCHAEFTSJAHR"),
                        "WARNUNG", "Nummernfolge prüfen.", true),
                new Regel("beleg_erwartet", "RECHNUNG", "ERWARTET",
                        Map.of("je", "MONAT", "datum", "BELEGDATUM"),
                        "WARNUNG", "Monatsbeleg fehlt.", true));
        typisierteRegeln = regeln.stream().map(Regel::typisierteParameter).toList();
    }

    @Wenn("eine aktive Pflichtregel mit Schwere «{word}» und fehlendem Datum geprüft wird")
    public void pflichtregelPruefen(String schwere) {
        pruefenPflichtregel(schwere, null);
    }

    @Wenn("eine bereits verletzte Pflichtregel mit unveränderten Metadaten geprüft wird")
    public void bestehendePflichtverletzungPruefen() {
        pruefenPflichtregel("FEHLER", Map.of("belegnummer", "R1"));
    }

    private void pruefenPflichtregel(String schwere, Map<String, Object> vorher) {
        Regel regel = new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                schwere, "Belegdatum fehlt.", true);
        fehler = null;
        warnungen = List.of();
        try {
            warnungen = pruefung(regel, (art, alle, felder, werte, id, sperren) -> false)
                    .pruefen("RECHNUNG", Map.of("belegnummer", "R1"), vorher, UUID.randomUUID());
        } catch (RegelVerletzt e) {
            fehler = e;
        }
    }

    @Dann("wird der Vorgang mit «{word}» abgelehnt")
    public void abgelehnt(String code) {
        assertThat(fehler).isNotNull();
        assertThat(fehler.code()).isEqualTo(Fehlercode.valueOf(code));
        assertThat(warnungen).isEmpty();
    }

    @Dann("enthält die Antwort die Warnung «{word}»")
    public void warnung(String code) {
        assertThat(fehler).isNull();
        assertThat(warnungen).extracting(RegelPruefung.Warnung::code).contains(code);
    }

    @Dann("wird der Vorgang nicht abgelehnt")
    public void nichtAbgelehnt() {
        assertThat(fehler).isNull();
    }

    @Dann("sind alle vier Regeltypen typisiert")
    public void alleRegeltypenTypisiert() {
        assertThat(typisierteRegeln).hasSize(4);
        assertThat(typisierteRegeln.get(0)).isInstanceOf(Regel.PflichtWenn.class);
        assertThat(typisierteRegeln.get(1)).isInstanceOf(Regel.Eindeutig.class);
        assertThat(typisierteRegeln.get(2)).isInstanceOf(Regel.Folge.class);
        assertThat(typisierteRegeln.get(3)).isInstanceOf(Regel.Erwartet.class);
    }

    @Wenn("eine Regel ein nicht vorhandenes Zusatzfeld referenziert")
    public void unbekanntesZusatzfeld() {
        Regel regel = new Regel("feld_unbekannt", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "zusatz.unbekannt", "gleich", "x"), "dann", List.of("datum")),
                "FEHLER", "Unbekanntes Feld.", true);
        Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(), null,
                "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        befunde = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of(art), List.of(),
                List.of(regel)).pruefen();
    }

    @Dann("wird die Test-Konfiguration mit Befund «{word}» abgelehnt")
    public void konfigurationAbgelehnt(String code) {
        assertThat(befunde).extracting(Konfiguration.Befund::code).contains(code);
    }

    private static RegelPruefung pruefung(Regel regel, RegelDatenPort daten) {
        Konfiguration abbild = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of(),
                List.of(), List.of(regel));
        KonfigurationVerwaltung verwaltung = new KonfigurationVerwaltung(null, null, null, null, null, null,
                null, null, null, null, null) {
            @Override
            public Konfiguration aktuell() {
                return abbild;
            }
        };
        return new RegelPruefung(verwaltung, daten, null);
    }

    @Angenommen("im Mandanten «{word}» gilt die Regel «{word}» für die Art «{word}» vom Typ «{word}» mit Schwere «{word}» und den Parametern {string}")
    public void regelEinrichten(String mandant, String code, String art, String typ, String schwere, String parameter)
            throws SQLException {
        // legt bei Bedarf die erste Konfigurationsversion an (KonfigurationVerwaltung.basisSicherstellen)
        Response stand = given().auth().oauth2(Testanmeldung.token("inhaber-" + mandant))
                .get("/api/v1/dokumentarten");
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        eingerichtet.add(new Eingerichtet(mandant, code));
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into "
                    + new MandantCode(mandant).schema() + ".regel (code, art, typ, parameter, schwere, meldung, aktiv) "
                    + "values (?, ?, ?, cast(? as jsonb), ?, 'Testregel verletzt.', true)")) {
                einfuegen.setString(1, code);
                einfuegen.setString(2, art);
                einfuegen.setString(3, typ);
                einfuegen.setString(4, parameter);
                einfuegen.setString(5, schwere);
                einfuegen.executeUpdate();
            }
            neueVersion(verbindung, mandant);
        }
    }

    @Wenn("«{word}» eine Regel mit dem aktuellen Stand anlegt:")
    public void regelAnlegen(String benutzer, String body) {
        String code = io.restassured.path.json.JsonPath.from(body).getString("code");
        String mandant = mandant(benutzer);
        eingerichtet.add(new Eingerichtet(mandant, code));
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(body).post("/api/v1/regeln");
    }

    @Wenn("«{word}» aktiviert die Regel «{word}»")
    public void regelAktivieren(String benutzer, String code) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("aktiv", true))
                .patch("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» deaktiviert die Regel «{word}»")
    public void regelDeaktivieren(String benutzer, String code) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("aktiv", false))
                .patch("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» die Regeln liest")
    public void regelnLesen(String benutzer) {
        antwort = gegeben(benutzer).get("/api/v1/regeln");
    }

    @Wenn("«{word}» eine Regel ohne If-Match anlegt:")
    public void regelOhneVersionAnlegen(String benutzer, String body) {
        eingerichtet.add(new Eingerichtet(mandant(benutzer), JsonPath.from(body).getString("code")));
        antwort = gegeben(benutzer).contentType(ContentType.JSON).body(body).post("/api/v1/regeln");
    }

    @Wenn("«{word}» eine Regel mit der Version «{word}» anlegt:")
    public void regelMitVersionAnlegen(String benutzer, String version, String body) {
        eingerichtet.add(new Eingerichtet(mandant(benutzer), JsonPath.from(body).getString("code")));
        antwort = gegeben(benutzer).header("If-Match", "\"" + version + "\"").contentType(ContentType.JSON)
                .body(body).post("/api/v1/regeln");
    }

    @Wenn("«{word}» die Regel «{word}» ohne If-Match ändert:")
    public void regelOhneVersionAendern(String benutzer, String code, String body) {
        antwort = gegeben(benutzer).contentType(ContentType.JSON).body(body).patch("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» die Regel «{word}» mit der Version «{word}» ändert:")
    public void regelMitVersionAendern(String benutzer, String code, String version, String body) {
        antwort = gegeben(benutzer).header("If-Match", "\"" + version + "\"").contentType(ContentType.JSON)
                .body(body).patch("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» die Regel «{word}» mit dem aktuellen Stand ändert:")
    public void regelAendern(String benutzer, String code, String body) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(body).patch("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» die Regel «{word}» ohne If-Match löscht")
    public void regelOhneVersionLoeschen(String benutzer, String code) {
        antwort = gegeben(benutzer).delete("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» die Regel «{word}» mit der Version «{word}» löscht")
    public void regelMitVersionLoeschen(String benutzer, String code, String version) {
        antwort = gegeben(benutzer).header("If-Match", "\"" + version + "\"").delete("/api/v1/regeln/" + code);
    }

    @Wenn("«{word}» die Regel «{word}» mit dem aktuellen Stand löscht")
    public void regelLoeschen(String benutzer, String code) {
        antwort = mitVersion(benutzer).delete("/api/v1/regeln/" + code);
    }

    /** Req 7.1, 1.3: 204 mit neuem ETag, Regel weg aus Tabelle, Liste und neuer Version (APP), ein Protokolleintrag. */
    @Dann("ist die Regel «{word}» von «{word}» in genau einer neuen Konfigurationsversion gelöscht")
    public void regelGeloescht(String code, String benutzer) throws SQLException {
        int neueVersion = gemerkteVersion + 1;
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(204);
        assertThat(antwort.getHeader("ETag")).isEqualTo("\"" + neueVersion + "\"");
        Response liste = gegeben(benutzer).get("/api/v1/regeln");
        assertThat(liste.getHeader("ETag")).isEqualTo("\"" + neueVersion + "\"");
        assertThat(liste.jsonPath().<String>getList("code")).doesNotContain(code).isNotEmpty();
        String schema = new MandantCode(mandant(benutzer)).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement versionen = verbindung.prepareStatement("select v.version, v.quelle, v.wer, "
                     + "jsonb_path_exists(v.inhalt, '$.regeln[*] ? (@.code == $c)', jsonb_build_object('c', ?::text)), "
                     + "jsonb_array_length(v.inhalt -> 'regeln') from " + schema + ".konfiguration_version v "
                     + "where v.version > ?");
             PreparedStatement tabelle = verbindung.prepareStatement("select count(*) from " + schema
                     + ".regel where code = ?");
             PreparedStatement protokoll = verbindung.prepareStatement("select count(*), min(benutzer) from "
                     + schema + ".zugriff_protokoll where id > ? and aktion = 'KONFIGURATION_AENDERN'")) {
            String wer;
            versionen.setString(1, code);
            versionen.setInt(2, gemerkteVersion);
            try (var ergebnis = versionen.executeQuery()) {
                assertThat(ergebnis.next()).as("neue Konfigurationsversion").isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(neueVersion);
                assertThat(ergebnis.getString(2)).isEqualTo("APP");
                wer = ergebnis.getString(3);
                assertThat(wer).as("wer der Version").isNotBlank();
                assertThat(ergebnis.getBoolean(4)).as("Regel im Abbild der Version").isFalse();
                assertThat(ergebnis.getInt(5)).as("übrige Regeln bleiben").isPositive();
                assertThat(ergebnis.next()).as("genau eine neue Version").isFalse();
            }
            tabelle.setString(1, code);
            try (var ergebnis = tabelle.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getLong(1)).as("Regel in der Tabelle").isZero();
            }
            protokoll.setLong(1, gemerktesProtokoll);
            try (var ergebnis = protokoll.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getLong(1)).as("Konfigurationseinträge im Zugriffsprotokoll").isEqualTo(1);
                assertThat(ergebnis.getString(2)).as("Benutzer im Protokoll wie wer der Version").isEqualTo(wer);
            }
        }
    }

    @Angenommen("der Konfigurationsstand von «{word}» ist gemerkt")
    public void konfigurationsstandMerken(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             Statement abfrage = verbindung.createStatement();
             var ergebnis = abfrage.executeQuery("select (select coalesce(max(version), 0) from " + schema
                     + ".konfiguration_version), (select coalesce(max(id), 0) from " + schema
                     + ".zugriff_protokoll)")) {
            assertThat(ergebnis.next()).isTrue();
            gemerkteVersion = ergebnis.getInt(1);
            gemerktesProtokoll = ergebnis.getLong(2);
        }
    }

    /** Antwort, Tabelle, Konfigurationsversion und Protokoll führen dieselbe Änderung (Req 1.3, 5.6, 7.6). */
    @Dann("führt «{word}» die Regel «{word}» mit aktiv «{word}» in genau einer neuen Konfigurationsversion")
    public void regelInNeuerVersion(String benutzer, String code, String aktiv) throws Exception {
        boolean erwartet = Boolean.parseBoolean(aktiv);
        JsonNode abbild = neueVersionPruefen(benutzer, code);
        assertThat(antwort.jsonPath().getBoolean("aktiv")).as("Antwort").isEqualTo(erwartet);
        assertThat(abbild.path("aktiv").asText()).as("Abbild der Version").isEqualTo(aktiv);
        assertThat(gegeben(benutzer).get("/api/v1/regeln").jsonPath()
                .<Boolean>getList("findAll { it.code == '" + code + "' }.aktiv")).as("Liste").containsExactly(erwartet);
    }

    /**
     * Teiländerung (Req 5.6, 7.6): Antwort, Liste und Abbild der neuen Version führen die gesetzten und die
     * übernommenen Felder; Werte in der Tabelle als JSON.
     */
    @Dann("führt «{word}» die Regel «{word}» in genau einer neuen Konfigurationsversion mit:")
    public void regelMitFeldernInNeuerVersion(String benutzer, String code, DataTable tabelle) throws Exception {
        JsonNode abbild = neueVersionPruefen(benutzer, code);
        JsonNode inAntwort = JSON.readTree(antwort.asString());
        JsonNode inListe = null;
        for (JsonNode regel : JSON.readTree(gegeben(benutzer).get("/api/v1/regeln").asString())) {
            if (regel.path("code").asText().equals(code)) {
                inListe = regel;
            }
        }
        assertThat(inListe).as("Regel in der Liste").isNotNull();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String feld = zeile.get("feld");
            JsonNode erwartet = JSON.readTree(zeile.get("wert"));
            assertThat(inAntwort.get(feld)).as("Antwort: " + feld).isEqualTo(erwartet);
            assertThat(inListe.get(feld)).as("Liste: " + feld).isEqualTo(erwartet);
            assertThat(abbild.get(feld)).as("Abbild der Version: " + feld).isEqualTo(erwartet);
        }
    }

    /** Status 200, ETag und Liste auf der nächsten Version, genau eine neue Version (APP) und ein Protokolleintrag. */
    private JsonNode neueVersionPruefen(String benutzer, String code) throws Exception {
        int neueVersion = gemerkteVersion + 1;
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getString("code")).isEqualTo(code);
        assertThat(antwort.getHeader("ETag")).isEqualTo("\"" + neueVersion + "\"");
        Response liste = gegeben(benutzer).get("/api/v1/regeln");
        assertThat(liste.statusCode()).as(liste.asString()).isEqualTo(200);
        assertThat(liste.getHeader("ETag")).isEqualTo("\"" + neueVersion + "\"");
        String schema = new MandantCode(mandant(benutzer)).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement versionen = verbindung.prepareStatement("select v.version, v.quelle, r::text "
                     + "from " + schema + ".konfiguration_version v "
                     + "left join lateral jsonb_array_elements(v.inhalt -> 'regeln') r on r ->> 'code' = ? "
                     + "where v.version > ?");
             PreparedStatement protokoll = verbindung.prepareStatement("select count(*) from " + schema
                     + ".zugriff_protokoll where id > ? and aktion = 'KONFIGURATION_AENDERN'")) {
            versionen.setString(1, code);
            versionen.setInt(2, gemerkteVersion);
            JsonNode abbild;
            try (var ergebnis = versionen.executeQuery()) {
                assertThat(ergebnis.next()).as("neue Konfigurationsversion").isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(neueVersion);
                assertThat(ergebnis.getString(2)).isEqualTo("APP");
                assertThat(ergebnis.getString(3)).as("Regel im Abbild der Version").isNotNull();
                abbild = JSON.readTree(ergebnis.getString(3));
                assertThat(ergebnis.next()).as("genau eine neue Version").isFalse();
            }
            protokoll.setLong(1, gemerktesProtokoll);
            try (var ergebnis = protokoll.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getLong(1)).as("Konfigurationseinträge im Zugriffsprotokoll").isEqualTo(1);
            }
            return abbild;
        }
    }

    @Dann("antwortet die Regelverwaltung mit Status {int} und Fehlercode «{word}»")
    public void regelFehler(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
    }

    @Dann("enthält die Regelliste die Regel «{word}»")
    public void regelInListe(String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("code")).contains(code);
    }

    @Dann("enthält die Regelliste die Regel «{word}» nicht")
    public void regelNichtInListe(String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("code")).doesNotContain(code);
    }

    @Wenn("«{word}» den Lückenbericht für das Geschäftsjahr «{int}» abruft")
    public void lueckenberichtAbrufen(String benutzer, int geschaeftsjahr) {
        antwort = gegeben(benutzer).queryParam("geschaeftsjahr", geschaeftsjahr).get("/api/v1/berichte/luecken");
    }

    @Dann("antwortet der Lückenbericht mit Status {int}")
    public void lueckenberichtStatus(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet der Lückenbericht mit Status {int} und Fehlercode «{word}»")
    public void lueckenberichtFehler(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
    }

    @Dann("antwortet die Regelverwaltung mit Status {int}")
    public void regelStatus(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("meldet die Regelverwaltung {long} betroffene Dokumente")
    public void betroffeneDokumente(long anzahl) {
        assertThat(antwort.jsonPath().getLong("betroffeneDokumente")).isEqualTo(anzahl);
    }

    @Dann("sind im Mandanten «{word}» weiterhin {long} bestätigte Dokumente der Art «{word}» archiviert")
    public void dokumenteUnveraendert(String mandant, long anzahl, String art) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select count(*) from "
                     + new MandantCode(mandant).schema()
                     + ".dokument where art = ? and bestaetigt is not null and status = 'ARCHIVIERT'")) {
            abfrage.setString(1, art);
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getLong(1)).isEqualTo(anzahl);
            }
        }
    }

    @Dann("enthält die aktuelle Konfiguration von «{word}» die Regeln «{}»")
    public void regelnInKonfiguration(String mandant, String codes) throws SQLException {
        assertThat(regelnDerAktuellenVersion(mandant)).containsAll(List.of(codes.split(",\\s*")));
    }

    @Dann("enthält die aktuelle Konfiguration von «{word}» keine Regeln")
    public void keineRegelnInKonfiguration(String mandant) throws SQLException {
        assertThat(regelnDerAktuellenVersion(mandant)).isEmpty();
    }

    private List<String> regelnDerAktuellenVersion(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        List<String> codes = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection();
             Statement abfrage = verbindung.createStatement();
             var ergebnis = abfrage.executeQuery("select r ->> 'code' from " + schema + ".konfiguration_version v, "
                     + "jsonb_array_elements(v.inhalt -> 'regeln') r where v.version = (select max(version) from "
                     + schema + ".konfiguration_version) union all select 'tabelle:' || code from " + schema
                     + ".regel")) {
            while (ergebnis.next()) {
                codes.add(ergebnis.getString(1));
            }
        }
        // Tabelle und aktuelle Version führen dieselben Regeln
        List<String> tabelle = codes.stream().filter(c -> c.startsWith("tabelle:"))
                .map(c -> c.substring("tabelle:".length())).sorted().toList();
        List<String> version = codes.stream().filter(c -> !c.startsWith("tabelle:")).sorted().toList();
        assertThat(version).isEqualTo(tabelle);
        return version;
    }

    // vor den Hooks, die Probe-Arten löschen (fk_regel_art ist RESTRICT)
    @After(order = 20000)
    public void regelnEntfernen() throws SQLException {
        if (eingerichtet.isEmpty()) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String mandant : eingerichtet.stream().map(Eingerichtet::mandant).distinct().toList()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement("delete from "
                        + new MandantCode(mandant).schema() + ".regel where code = any(?)")) {
                    loeschen.setArray(1, verbindung.createArrayOf("text", eingerichtet.stream()
                            .filter(e -> e.mandant().equals(mandant)).map(Eingerichtet::code).toArray()));
                    loeschen.executeUpdate();
                }
                neueVersion(verbindung, mandant);
            }
        }
        eingerichtet.clear();
    }

    private static void neueVersion(Connection verbindung, String mandant) throws SQLException {
        try (Statement befehl = verbindung.createStatement()) {
            befehl.execute("set search_path to " + new MandantCode(mandant).schema());
            try {
                assertThat(befehl.executeUpdate(VERSION_MIT_REGELN)).isEqualTo(1);
            } finally {
                befehl.execute("reset search_path");
            }
        }
    }

    private static io.restassured.specification.RequestSpecification mitVersion(String benutzer) {
        Response stand = gegeben(benutzer).get("/api/v1/regeln");
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        return gegeben(benutzer).header("If-Match", stand.getHeader("ETag"));
    }

    private static io.restassured.specification.RequestSpecification gegeben(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer));
    }

    private static String mandant(String benutzer) {
        return benutzer.substring(benutzer.indexOf('-') + 1);
    }
}
