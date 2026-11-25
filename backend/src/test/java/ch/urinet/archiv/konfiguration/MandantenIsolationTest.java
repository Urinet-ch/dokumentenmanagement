package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.application.MetadatenExtraktion;
import ch.urinet.archiv.ki.application.chat.Lueckenbericht;
import ch.urinet.archiv.konfiguration.adapter.in.kommando.KonfigurationKommando;
import ch.urinet.archiv.konfiguration.application.KonfigurationCache;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

abstract class MandantenIsolationIT {

    private record DokumentFall(String benutzer, UUID eigeneId, UUID fremdeId) { }
    private record ApiFehler(String type, String title, String detail, String fehlercode) { }

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantKontext kontext;

    @Inject
    KonfigurationVerwaltung konfiguration;

    @Inject
    KonfigurationKommando kommando;

    @Inject
    Lueckenbericht lueckenbericht;

    @Inject
    KonfigurationCache cache;

    @Inject
    ApiTokenVerwaltung apiTokenVerwaltung;

    @Inject
    MetadatenExtraktion extraktion;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @TempDir
    Path ordner;

    @BeforeEach
    void mandantenAnlegen() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk", "probe-verband")) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code);
                try (ResultSet ergebnis = abfrage.executeQuery()) {
                    if (!ergebnis.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
    }

    @Test
    void konfigurationVersionenUndKommandoBleibenBeimGewaehltenMandanten() throws Exception {
        Konfiguration urinet = in("urinet", konfiguration::aktuell);
        Konfiguration verband = in("probe-verband", konfiguration::aktuell);
        assertThat(verband.arten()).extracting(Konfiguration.Art::code)
                .contains("VERBANDSSITZUNG").doesNotContain("AUSGANGSRECHNUNG");
        assertThat(urinet.arten()).extracting(Konfiguration.Art::code)
                .contains("AUSGANGSRECHNUNG").doesNotContain("VERBANDSSITZUNG");
        assertThat(verband.arten()).extracting(Konfiguration.Art::code).doesNotContain("WISSENSQUELLE");
        assertThat(urinet.arten()).extracting(Konfiguration.Art::code).doesNotContain("WISSENSQUELLE");
        assertThat(verband.klassifikationen()).extracting(Klassifikation::code).contains("sitzungsthema");
        assertThat(urinet.klassifikationen()).extracting(Klassifikation::code).doesNotContain("sitzungsthema");
        assertThat(verband.regeln()).extracting(Regel::code)
                .contains("sitzung_nummer_eindeutig", "sitzung_nummer_folge");
        assertThat(urinet.regeln()).extracting(Regel::code).doesNotContain("sitzung_nummer_folge");
        assertThat(verband.kiTexte().rolle()).contains("Probeverband");
        assertThat(urinet.kiTexte().rolle()).doesNotContain("Probeverband");

        assertThat(in("probe-verband", () -> konfiguration.version(verband.version()).inhalt()).arten())
                .extracting(Konfiguration.Art::code).contains("VERBANDSSITZUNG")
                .doesNotContain("AUSGANGSRECHNUNG");
        assertThat(in("urinet", () -> konfiguration.version(urinet.version()).inhalt()).arten())
                .extracting(Konfiguration.Art::code).contains("AUSGANGSRECHNUNG")
                .doesNotContain("VERBANDSSITZUNG");
        Konfiguration verbandsVersion = in("probe-verband",
                () -> konfiguration.version(verband.version()).inhalt());
        Konfiguration urinetVersion = in("urinet", () -> konfiguration.version(urinet.version()).inhalt());
        assertThat(verbandsVersion.klassifikationen()).extracting(Klassifikation::code).contains("sitzungsthema");
        assertThat(urinetVersion.klassifikationen()).extracting(Klassifikation::code).doesNotContain("sitzungsthema");
        assertThat(verbandsVersion.regeln()).extracting(Regel::code).contains("sitzung_nummer_folge");
        assertThat(urinetVersion.regeln()).extracting(Regel::code).doesNotContain("sitzung_nummer_folge");
        assertThat(verbandsVersion.kiTexte().rolle()).contains("Probeverband");
        assertThat(urinetVersion.kiTexte().rolle()).doesNotContain("Probeverband");

        String exportVerband = exportieren("probe-verband");
        String exportUrinet = exportieren("urinet");
        assertThat(exportVerband).contains("VERBANDSSITZUNG", "sitzungsthema", "sitzung_nummer_folge", "Probeverband")
                .doesNotContain("AUSGANGSRECHNUNG");
        assertThat(exportUrinet).contains("AUSGANGSRECHNUNG")
                .doesNotContain("VERBANDSSITZUNG", "sitzungsthema", "sitzung_nummer_folge", "Probeverband");
    }

    @Test
    void lueckenberichtVerwendetAusschliesslichDieRegelnDesMandanten() throws Exception {
        Sichtregel sicht = Sichtregel.fuer(new Benutzer("test-isolation", Rolle.INHABER));
        Lueckenbericht.Bericht verband = in("probe-verband", () -> QuarkusTransaction.requiringNew()
                .call(() -> lueckenbericht.erstellen(new MandantCode("probe-verband"), 2026, sicht)));
        Lueckenbericht.Bericht urinet = in("urinet", () -> QuarkusTransaction.requiringNew()
                .call(() -> lueckenbericht.erstellen(new MandantCode("urinet"), 2026, sicht)));
        assertThat(verband.kategorien()).extracting(Lueckenbericht.Kategorie::art)
                .contains("VERBANDSSITZUNG").doesNotContain("AUSGANGSRECHNUNG");
        assertThat(urinet.kategorien()).extracting(Lueckenbericht.Kategorie::art)
                .doesNotContain("VERBANDSSITZUNG");
    }

    @Test
    void kiVerwendetAusschliesslichDieKonfigurationDesMandanten() throws Exception {
        MandantCode verbandCode = new MandantCode("probe-verband");
        Konfiguration verband = in("probe-verband", konfiguration::aktuell);
        assertThat(verband.kiTexte().kiFreigegeben()).isFalse();
        cache.speichern(verbandCode, new Konfiguration(verband.version(), verband.einstellungen(), verband.arten(),
                verband.klassifikationen(), verband.regeln(),
                new Konfiguration.KiTexte(verband.kiTexte().rolle(), verband.kiTexte().feldregeln(), true)));
        try {
            KiWireMock.zuruecksetzen();
            kiAnfrage("probe-verband", "synthetischer Verbandstext");
            kiAnfrage("urinet", "synthetischer Urinettext");
            List<String> anfragen = KiWireMock.client()
                    .find(postRequestedFor(urlPathEqualTo(KiWireMock.CHAT_PFAD))).stream()
                    .map(anfrage -> anfrage.getBodyAsString()).toList();
            List<String> verbandsAnfragen = anfragen.stream()
                    .filter(anfrage -> anfrage.contains("synthetischer Verbandstext")).toList();
            List<String> urinetAnfragen = anfragen.stream()
                    .filter(anfrage -> anfrage.contains("synthetischer Urinettext")).toList();
            assertThat(verbandsAnfragen).isNotEmpty().allSatisfy(anfrage -> assertThat(anfrage)
                    .contains("Probeverband", "VERBANDSSITZUNG", "sitzungsthema")
                    .doesNotContain("AUSGANGSRECHNUNG"));
            assertThat(urinetAnfragen).isNotEmpty().allSatisfy(anfrage -> assertThat(anfrage)
                    .contains("AUSGANGSRECHNUNG")
                    .doesNotContain("Probeverband", "VERBANDSSITZUNG", "sitzungsthema"));
        } finally {
            cache.invalidieren(verbandCode);
            KiWireMock.zuruecksetzen();
        }
    }

    private void kiAnfrage(String mandant, String text) throws Exception {
        MandantCode code = new MandantCode(mandant);
        in(mandant, () -> QuarkusTransaction.requiringNew().call(() -> extraktion.vorschlagen(
                new MetadatenExtraktion.Eingabe(UUID.randomUUID(), code, text, Optional.empty(), Optional.empty(),
                        LocalDate.of(2026, 11, 20)))));
    }

    @Test
    void apiUndSuchfilterVerwendenDenMandantenAusDerAnmeldung() {
        String urinet = Testanmeldung.token("inhaber-urinet");
        String vhf = Testanmeldung.token("inhaber-vhf-gsk");
        String eigeneDatei = given().auth().oauth2(urinet).when().get("/api/v1/konfiguration")
                .then().statusCode(200).extract().asString();
        String andereDatei = given().auth().oauth2(vhf).when().get("/api/v1/konfiguration")
                .then().statusCode(200).extract().asString();
        assertThat(eigeneDatei).contains("AUSGANGSRECHNUNG").doesNotContain("BULLETIN");
        assertThat(andereDatei).contains("BULLETIN").doesNotContain("AUSGANGSRECHNUNG");
        given().auth().oauth2(urinet).queryParam("klassifikation.sitzungsthema", "projekte")
                .when().get("/api/v1/dokumente").then().statusCode(400);
        given().auth().oauth2(vhf).queryParam("klassifikation.sitzungsthema", "projekte")
                .when().get("/api/v1/dokumente").then().statusCode(400);
    }

    @Test
    void fremdeDokumenteSindWieUnbekannteDokumenteNichtSichtbar() throws Exception {
        UUID urinetDokument = null;
        UUID vhfDokument = null;
        Throwable testFehler = null;
        try {
            urinetDokument = Testdokumente.anlegen(datenbank.get(),
                    new Testdokumente.Angaben("urinet", "PROJEKTDOKUMENT", "NORMAL", true, "probe", null, "ARCHIVIERT"));
            vhfDokument = Testdokumente.anlegen(datenbank.get(),
                    new Testdokumente.Angaben("vhf-gsk", "BULLETIN", "NORMAL", true, "probe", null, "ARCHIVIERT"));
            for (DokumentFall fall : List.of(
                    new DokumentFall("inhaber-urinet", urinetDokument, vhfDokument),
                    new DokumentFall("inhaber-vhf-gsk", vhfDokument, urinetDokument))) {
                String token = Testanmeldung.token(fall.benutzer());
                String fremderInhaber = fall.benutzer().equals("inhaber-urinet")
                        ? "inhaber-vhf-gsk" : "inhaber-urinet";
                String fremdeEtag = given().auth().oauth2(Testanmeldung.token(fremderInhaber))
                        .get("/api/v1/dokumente/" + fall.fremdeId())
                        .then().statusCode(200).extract().header("ETag");
                given().auth().oauth2(token).get("/api/v1/dokumente/" + fall.eigeneId())
                        .then().statusCode(200);
                for (String suffix : List.of("", "/datei", "/vorschau")) {
                    dokumentIsolationPruefen(token, fall.fremdeId(), suffix, "GET", null, null);
                }
                dokumentIsolationPruefen(token, fall.fremdeId(), "", "PATCH",
                        "{\"titel\":\"Fremder Schreibversuch\",\"grund\":\"Isolationsprobe\"}", fremdeEtag);
                dokumentIsolationPruefen(token, fall.fremdeId(), "/bestaetigen", "POST",
                        "{\"bestaetigung\":true}", fremdeEtag);
                dokumentIsolationPruefen(token, fall.fremdeId(), "/verwerfen", "POST",
                        "{\"grund\":\"Isolationsprobe\"}", fremdeEtag);
                dokumentIsolationPruefen(token, fall.fremdeId(), "/wiederholen", "POST", null, fremdeEtag);
                dokumentIsolationPruefen(token, fall.fremdeId(), "/wissensquelle", "POST", null, null);
                dokumentIsolationPruefen(token, fall.fremdeId(), "/wissensquelle", "DELETE", null, null);
                dokumentIsolationPruefen(token, fall.fremdeId(), "/wissen-ingest", "POST", "{}", null);
            }
        } catch (Throwable t) {
            testFehler = t;
            throw t;
        } finally {
            Throwable fehler = null;
            for (var eintrag : java.util.Map.of("urinet", java.util.Optional.ofNullable(urinetDokument),
                    "vhf-gsk", java.util.Optional.ofNullable(vhfDokument)).entrySet()) {
                if (eintrag.getValue().isEmpty()) continue;
                try {
                    Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), List.of(eintrag.getValue().get()));
                } catch (Exception e) {
                    fehler = merken(fehler, e);
                }
            }
            if (fehler != null) {
                if (testFehler != null) testFehler.addSuppressed(fehler);
                else throw new IllegalStateException("Aufräumen fehlgeschlagen", fehler);
            }
        }
    }

    @Test
    void fremdeKontakteSindBeiAllenRessourcenaktionenWieUnbekannteKontakte() throws Exception {
        UUID kontakt = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        String marker = "Isolationskontakt-" + kontakt;
        try {
            kontaktAnlegen(kontakt, marker);
            kontaktAnlegen(ziel, "Isolationsziel-" + ziel);
            String tokenA = Testanmeldung.token("inhaber-urinet");
            String tokenB = Testanmeldung.token("inhaber-vhf-gsk");
            String etag = given().auth().oauth2(tokenB).get("/api/v1/kontakte/" + kontakt)
                    .then().statusCode(200).extract().header("ETag");
            given().auth().oauth2(tokenB).get("/api/v1/kontakte/" + kontakt + "/verweise")
                    .then().statusCode(200);

            List<KontaktProbe> proben = List.of(
                    new KontaktProbe("GET", "", null, false),
                    new KontaktProbe("GET", "/verweise", null, false),
                    new KontaktProbe("PATCH", "", "{\"name\":\"Fremder Schreibversuch\","
                            + "\"typ\":\"ORGANISATION\"}", true),
                    new KontaktProbe("POST", "/inaktiv", "{\"grund\":\"Isolationsprobe\"}", true),
                    new KontaktProbe("POST", "/aktiv", null, true),
                    new KontaktProbe("POST", "/zusammenfuehren",
                            "{\"ziel\":\"" + ziel + "\",\"grund\":\"Isolationsprobe\"}", true));
            for (KontaktProbe probe : proben) {
                String fremd = "/api/v1/kontakte/" + kontakt + probe.suffix();
                String unbekannt = "/api/v1/kontakte/" + UUID.randomUUID() + probe.suffix();
                kontaktIsolationPruefen(tokenA, probe, fremd, unbekannt, etag, marker);
            }
            String unveraendert = given().auth().oauth2(tokenB).get("/api/v1/kontakte/" + kontakt)
                    .then().statusCode(200).extract().asString();
            assertThat(unveraendert).contains(marker, "\"aktiv\":true");
            given().auth().oauth2(tokenB).get("/api/v1/kontakte/" + ziel).then().statusCode(200)
                    .body("id", org.hamcrest.Matchers.equalTo(ziel.toString()));
        } finally {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement loeschen = verbindung.prepareStatement(
                         "delete from m_vhf_gsk.kontakt where id = any (?)")) {
                loeschen.setArray(1, verbindung.createArrayOf("uuid", new UUID[]{kontakt, ziel}));
                loeschen.executeUpdate();
            }
        }
    }

    @Test
    void austrittEinerFremdenBenutzerkennungIstWieEineUnbekannteKennung() throws Exception {
        String sub = Testanmeldung.sub("inhaber-vhf-gsk");
        long protokollId;
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into m_vhf_gsk.zugriff_protokoll (benutzer, rolle, aktion) "
                             + "values (?, 'INHABER', 'BENUTZER_AENDERN') returning id")) {
            einfuegen.setString(1, sub);
            try (ResultSet zeile = einfuegen.executeQuery()) {
                zeile.next();
                protokollId = zeile.getLong(1);
            }
        }
        try {
            String tokenB = Testanmeldung.token("inhaber-vhf-gsk");
            assertThat(given().auth().oauth2(tokenB).get("/api/v1/protokoll/benutzer")
                    .then().statusCode(200).extract().asString()).contains(sub);
            String tokenA = Testanmeldung.token("inhaber-urinet");
            var fremd = given().auth().oauth2(tokenA)
                    .post("/api/v1/protokoll/benutzer/" + sub + "/austritt");
            var unbekannt = given().auth().oauth2(tokenA)
                    .post("/api/v1/protokoll/benutzer/unbekannt-" + UUID.randomUUID() + "/austritt");
            assertThat(fremd.statusCode()).isIn(403, 404);
            assertThat(fremd.statusCode()).isEqualTo(unbekannt.statusCode());
            assertThat(apiFehler(fremd)).isEqualTo(apiFehler(unbekannt));
            assertThat(fremd.asString()).doesNotContain(sub);
        } finally {
            try (Connection verbindung = datenbank.get().getConnection()) {
                verbindung.setAutoCommit(false);
                try (var befehl = verbindung.createStatement()) {
                    befehl.execute("set local session_replication_role = replica");
                }
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from m_vhf_gsk.zugriff_protokoll where id = ?")) {
                    loeschen.setLong(1, protokollId);
                    loeschen.executeUpdate();
                }
                verbindung.commit();
            }
        }
    }

    @Test
    void importUndWissensRessourcenSindMandantengetrennt() throws Exception {
        UUID importId = UUID.randomUUID();
        UUID wissenExportId = UUID.randomUUID();
        UUID entwurfId = UUID.randomUUID();
        String sub = Testanmeldung.sub("inhaber-vhf-gsk");
        try {
            laufRessourcenAnlegen(importId, wissenExportId, entwurfId, sub);
            String tokenB = Testanmeldung.token("inhaber-vhf-gsk");
            String tokenA = Testanmeldung.token("inhaber-urinet");
            given().auth().oauth2(tokenB).get("/api/v1/importe/" + importId).then().statusCode(200);
            given().auth().oauth2(tokenB).get("/api/v1/wissensbasis/exporte/" + wissenExportId)
                    .then().statusCode(200);
            given().auth().oauth2(tokenB).get("/api/v1/wissensentwuerfe/" + entwurfId).then().statusCode(200);

            UUID unbekannterImport = UUID.randomUUID();
            UUID unbekannterWissenExport = UUID.randomUUID();
            UUID unbekannterEntwurf = UUID.randomUUID();
            pruefeRessourcenIsolation(tokenA, "GET", "/api/v1/importe/" + importId,
                    "/api/v1/importe/" + unbekannterImport, null, null, importId.toString());
            pruefeRessourcenIsolation(tokenA, "POST", "/api/v1/importe/" + importId + "/uebernehmen",
                    "/api/v1/importe/" + unbekannterImport + "/uebernehmen",
                    "{\"dublettenEntscheide\":{}}", null, importId.toString());
            pruefeRessourcenIsolation(tokenA, "GET", "/api/v1/wissensbasis/exporte/" + wissenExportId,
                    "/api/v1/wissensbasis/exporte/" + unbekannterWissenExport, null, null, wissenExportId.toString());
            pruefeRessourcenIsolation(tokenA, "GET", "/api/v1/wissensentwuerfe/" + entwurfId,
                    "/api/v1/wissensentwuerfe/" + unbekannterEntwurf, null, null, entwurfId.toString());
            pruefeRessourcenIsolation(tokenA, "POST", "/api/v1/wissensentwuerfe/" + entwurfId + "/neu-pruefen",
                    "/api/v1/wissensentwuerfe/" + unbekannterEntwurf + "/neu-pruefen",
                    null, null, entwurfId.toString());
            for (String aktion : List.of("annehmen", "verwerfen", "neu-erzeugen")) {
                String pfad = "/api/v1/wissensentwuerfe/" + entwurfId + "/aenderungen/1/" + aktion;
                String unbekannt = "/api/v1/wissensentwuerfe/" + unbekannterEntwurf
                        + "/aenderungen/1/" + aktion;
                String body = switch (aktion) {
                    case "annehmen" -> "{\"vertraulichkeit\":\"NORMAL\",\"grund\":\"Isolationsprobe\"}";
                    case "verwerfen" -> "{\"grund\":\"Isolationsprobe\"}";
                    case "neu-erzeugen" -> null;
                    default -> throw new IllegalStateException("Unbekannte Wissensaktion");
                };
                pruefeRessourcenIsolation(tokenA, "POST", pfad, unbekannt, body, "\"0\"", entwurfId.toString());
            }
            assertThat(anzahl("select count(*) from m_vhf_gsk.import_lauf where id = '" + importId + "'")).isEqualTo(1);
            assertThat(anzahl("select count(*) from m_vhf_gsk.wissens_export where id = '" + wissenExportId + "'"))
                    .isEqualTo(1);
            assertThat(anzahl("select count(*) from m_vhf_gsk.wissens_entwurf where id = '" + entwurfId + "'"))
                    .isEqualTo(1);
        } finally {
            laufRessourcenEntfernen(importId, wissenExportId, entwurfId);
        }
    }

    @Test
    void jahresexportIdsSindMandantengetrennt() throws Exception {
        UUID eigenesId = UUID.randomUUID();
        UUID fremdesId = UUID.randomUUID();
        String subA = Testanmeldung.sub("inhaber-urinet");
        String subB = Testanmeldung.sub("inhaber-vhf-gsk");
        try {
            jahresexportAnlegen("m_urinet", eigenesId, subA);
            jahresexportAnlegen("m_vhf_gsk", fremdesId, subB);
            String tokenA = Testanmeldung.token("inhaber-urinet");
            given().auth().oauth2(tokenA).get("/api/v1/exporte/" + eigenesId).then().statusCode(200);
            String listeA = given().auth().oauth2(tokenA).get("/api/v1/exporte")
                    .then().statusCode(200).extract().asString();
            assertThat(listeA).contains(eigenesId.toString()).doesNotContain(fremdesId.toString());

            UUID unbekannteId = UUID.randomUUID();
            pruefeRessourcenIsolation(tokenA, "GET", "/api/v1/exporte/" + fremdesId,
                    "/api/v1/exporte/" + unbekannteId, null, null, fremdesId.toString());
        } finally {
            jahresexportEntfernen("m_urinet", eigenesId);
            jahresexportEntfernen("m_vhf_gsk", fremdesId);
        }
    }

    @Test
    void apiTokenAblaufUndWiderrufSindMandantengetrennt() throws Exception {
        var tokenB = apiTokenVerwaltung.erstellen("vhf-gsk", "Isolationsprobe", "generator");
        String fremdeId = tokenB.client().id();
        String unbekannteId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tokenA = Testanmeldung.token("inhaber-urinet");
        try {
            String listeB = given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk"))
                    .get("/api/v1/api-tokens").then().statusCode(200).extract().asString();
            assertThat(listeB).contains(fremdeId);
            String listeA = given().auth().oauth2(tokenA).get("/api/v1/api-tokens")
                    .then().statusCode(200).extract().asString();
            assertThat(listeA).doesNotContain(fremdeId);
            assertThat(listeA).doesNotContain(tokenB.token().text());

            String fremderAblauf = "/api/v1/api-tokens/" + fremdeId + "/gueltig-bis";
            String unbekannterAblauf = "/api/v1/api-tokens/" + unbekannteId + "/gueltig-bis";
            String body = "{\"gueltigBis\":\"" + LocalDate.now().plusDays(30) + "\"}";
            pruefeRessourcenIsolation(tokenA, "PUT", fremderAblauf, unbekannterAblauf, body, null, fremdeId);
            pruefeRessourcenIsolation(tokenA, "DELETE", fremderAblauf, unbekannterAblauf,
                    null, null, fremdeId);
            pruefeRessourcenIsolation(tokenA, "DELETE", "/api/v1/api-tokens/" + fremdeId,
                    "/api/v1/api-tokens/" + unbekannteId, null, null, fremdeId);
            assertThat(apiTokenStatus(fremdeId)).isEqualTo("vhf-gsk|true|null");
        } finally {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement loeschen = verbindung.prepareStatement(
                         "delete from public.api_client where id = ?")) {
                loeschen.setString(1, fremdeId);
                loeschen.executeUpdate();
            }
        }
    }

    private String apiTokenStatus(String id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select mandant || '|' || aktiv || '|' || coalesce(gueltig_bis::text, 'null') "
                             + "from public.api_client where id = ?")) {
            abfrage.setString(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getString(1);
            }
        }
    }

    private void pruefeRessourcenIsolation(String token, String methode, String fremderPfad, String unbekannterPfad,
                                            String body, String ifMatch, String marker) {
        var fremdAnfrage = given().auth().oauth2(token);
        var unbekanntAnfrage = given().auth().oauth2(token);
        if (ifMatch != null) {
            fremdAnfrage.header("If-Match", ifMatch);
            unbekanntAnfrage.header("If-Match", ifMatch);
        }
        if (body != null) {
            fremdAnfrage.contentType("application/json").body(body);
            unbekanntAnfrage.contentType("application/json").body(body);
        }
        var fremd = fremdAnfrage.request(methode, fremderPfad);
        var unbekannt = unbekanntAnfrage.request(methode, unbekannterPfad);
        assertThat(fremd.statusCode()).as("%s %s", methode, fremderPfad).isIn(403, 404);
        assertThat(fremd.statusCode()).isEqualTo(unbekannt.statusCode());
        assertThat(apiFehler(fremd)).isEqualTo(apiFehler(unbekannt));
        assertThat(fremd.asString()).doesNotContain(marker);
    }

    private void laufRessourcenAnlegen(UUID importId, UUID wissenExportId, UUID entwurfId, String benutzer)
            throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement importLauf = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.import_lauf
                         (id, art, sha256, zeichensatz, trennzeichen, kopfzeile, zeilen, fehler, status, benutzer)
                     values (?, 'kontakt', repeat('b', 64), 'UTF-8', ',', '[]'::jsonb, 0, 0, 'VORSCHAU', ?)
                     """);
             PreparedStatement wissenExport = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.wissens_export (id, status, benutzer, rolle)
                     values (?, 'OFFEN', ?, 'INHABER')
                     """);
             PreparedStatement entwurf = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.wissens_entwurf
                         (id, anlass, status, erstellt_von, erstellt, paket_sha256, paket_angaben)
                     values (?, 'IMPORT', 'OFFEN', ?, now(), repeat('c', 64),
                             '{"kopf":{"paketFormat":1,"mandant":"vhf-gsk","erzeugtVon":"test",
                               "erzeugtMit":"test","kontextStufe":"oeffentlich",
                               "erzeugtAm":"2026-01-01T00:00:00Z","instanz":"isolation"},
                              "unveraendert":0,"ignoriert":0}'::jsonb)
                     """);
             PreparedStatement aenderung = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.wissens_aenderung
                         (entwurf_id, nr, aktion, pfad, daten, inhalt, diff, status)
                     values (?, 1, 'NEU', ?, '{"typ":"Konzept","titel":"Isolationsprobe"}'::jsonb,
                             '# Isolationsprobe', '{}'::jsonb, 'OFFEN')
                     """)) {
            importLauf.setObject(1, importId);
            importLauf.setString(2, benutzer);
            importLauf.executeUpdate();
            wissenExport.setObject(1, wissenExportId);
            wissenExport.setString(2, benutzer);
            wissenExport.executeUpdate();
            entwurf.setObject(1, entwurfId);
            entwurf.setString(2, benutzer);
            entwurf.executeUpdate();
            aenderung.setObject(1, entwurfId);
            aenderung.setString(2, "isolation/probe-" + entwurfId);
            aenderung.executeUpdate();
        }
    }

    private void jahresexportAnlegen(String schema, UUID id, String benutzer) throws SQLException {
        if (!List.of("m_urinet", "m_vhf_gsk").contains(schema)) {
            throw new IllegalArgumentException("Unbekanntes Testschema");
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into " + schema + ".export (id, geschaeftsjahr, status, benutzer, rolle) "
                             + "values (?, 2026, 'OFFEN', ?, 'INHABER')")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, benutzer);
            einfuegen.executeUpdate();
        }
    }

    private void jahresexportEntfernen(String schema, UUID id) throws SQLException {
        if (!List.of("m_urinet", "m_vhf_gsk").contains(schema)) {
            throw new IllegalArgumentException("Unbekanntes Testschema");
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "delete from " + schema + ".export where id = ?")) {
            loeschen.setObject(1, id);
            loeschen.executeUpdate();
        }
    }

    private void laufRessourcenEntfernen(UUID importId, UUID wissenExportId, UUID entwurfId) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement importLauf = verbindung.prepareStatement(
                     "delete from m_vhf_gsk.import_lauf where id = ?");
             PreparedStatement wissenExport = verbindung.prepareStatement(
                     "delete from m_vhf_gsk.wissens_export where id = ?");
             PreparedStatement aenderung = verbindung.prepareStatement(
                     "delete from m_vhf_gsk.wissens_aenderung where entwurf_id = ?");
             PreparedStatement entwurf = verbindung.prepareStatement(
                     "delete from m_vhf_gsk.wissens_entwurf where id = ?")) {
            importLauf.setObject(1, importId);
            importLauf.executeUpdate();
            wissenExport.setObject(1, wissenExportId);
            wissenExport.executeUpdate();
            aenderung.setObject(1, entwurfId);
            aenderung.executeUpdate();
            entwurf.setObject(1, entwurfId);
            entwurf.executeUpdate();
        }
    }

    private long anzahl(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             var abfrage = verbindung.createStatement();
             ResultSet zeile = abfrage.executeQuery(sql)) {
            zeile.next();
            return zeile.getLong(1);
        }
    }

    private record KontaktProbe(String methode, String suffix, String body, boolean etag) {
    }

    private void kontaktIsolationPruefen(String token, KontaktProbe probe, String fremderPfad,
                                          String unbekannterPfad, String etag, String marker) {
        var fremdAnfrage = given().auth().oauth2(token);
        var unbekanntAnfrage = given().auth().oauth2(token);
        if (probe.etag()) {
            fremdAnfrage.header("If-Match", etag);
            unbekanntAnfrage.header("If-Match", etag);
        }
        if (probe.body() != null) {
            fremdAnfrage.contentType("application/json").body(probe.body());
            unbekanntAnfrage.contentType("application/json").body(probe.body());
        }
        var fremd = fremdAnfrage.request(probe.methode(), fremderPfad);
        var unbekannt = unbekanntAnfrage.request(probe.methode(), unbekannterPfad);
        assertThat(fremd.statusCode()).as("%s %s", probe.methode(), probe.suffix()).isIn(403, 404);
        assertThat(fremd.statusCode()).isEqualTo(unbekannt.statusCode());
        assertThat(apiFehler(fremd)).isEqualTo(apiFehler(unbekannt));
        assertThat(fremd.asString()).doesNotContain(marker);
    }

    private void kontaktAnlegen(UUID id, String name) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.kontakt
                         (id, typ, name, name_normalisiert, quelle, nicht_im_export, aktiv)
                     values (?, 'ORGANISATION', ?, ?, 'MANUELL', false, true)
                     """)) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, name);
            einfuegen.setString(3, name.toLowerCase(java.util.Locale.ROOT));
            einfuegen.executeUpdate();
        }
    }

    @Test
    void wissensseitenBleibenBeimMandantenAusDerAnmeldung() throws Exception {
        UUID urinetSeite = UUID.randomUUID();
        UUID vhfSeite = UUID.randomUUID();
        UUID urinetDokument = null;
        UUID vhfDokument = null;
        Throwable testFehler = null;
        try {
            urinetDokument = Testdokumente.anlegen(datenbank.get(),
                    new Testdokumente.Angaben("urinet", "PROJEKTDOKUMENT", "NORMAL", true, "probe", null, "ARCHIVIERT"));
            vhfDokument = Testdokumente.anlegen(datenbank.get(),
                    new Testdokumente.Angaben("vhf-gsk", "BULLETIN", "NORMAL", true, "probe", null, "ARCHIVIERT"));
            Testdokumente.alsWissensquelleMarkieren(datenbank.get(), "urinet", urinetDokument);
            Testdokumente.alsWissensquelleMarkieren(datenbank.get(), "vhf-gsk", vhfDokument);
            wissensseiteAnlegen("m_urinet", urinetSeite, urinetDokument);
            wissensseiteAnlegen("m_vhf_gsk", vhfSeite, vhfDokument);
            ObjectMapper mapper = new ObjectMapper();
            String urinetToken = Testanmeldung.token("inhaber-urinet");
            String vhfToken = Testanmeldung.token("inhaber-vhf-gsk");
            String urinetEtag = seitenEtag(urinetToken, urinetSeite);
            String vhfEtag = seitenEtag(vhfToken, vhfSeite);
            wissensseitenLesewegePruefen(urinetToken, urinetSeite);
            wissensseitenLesewegePruefen(vhfToken, vhfSeite);
            for (String benutzer : List.of("inhaber-urinet", "mitarbeiter-urinet", "treuhaender-urinet",
                    "inhaber-vhf-gsk", "geschaeftsstelle-vhf-gsk")) {
                boolean verband = benutzer.endsWith("vhf-gsk");
                String token = Testanmeldung.token(benutzer);
                UUID eigeneSeite = verband ? vhfSeite : urinetSeite;
                UUID fremdeSeite = verband ? urinetSeite : vhfSeite;
                String fremdeEtag = verband ? urinetEtag : vhfEtag;
                String eigeneAntwort = given().auth().oauth2(token).get("/api/v1/wissensseiten/" + eigeneSeite)
                        .then().statusCode(200).extract().asString();
                String fremdeAntwort = given().auth().oauth2(token).get("/api/v1/wissensseiten/" + fremdeSeite)
                        .then().statusCode(404).extract().asString();
                String unbekannteAntwort = given().auth().oauth2(token)
                        .get("/api/v1/wissensseiten/" + UUID.randomUUID())
                        .then().statusCode(404).extract().asString();
                var fremderFehler = mapper.readTree(fremdeAntwort);
                var unbekannterFehler = mapper.readTree(unbekannteAntwort);
                assertThat(fremderFehler.path("type").asText()).isEqualTo(unbekannterFehler.path("type").asText());
                assertThat(fremderFehler.path("title").asText()).isEqualTo(unbekannterFehler.path("title").asText());
                assertThat(fremderFehler.path("detail").asText()).isEqualTo(unbekannterFehler.path("detail").asText());
                assertThat(fremderFehler.path("fehlercode").asText())
                        .isEqualTo(unbekannterFehler.path("fehlercode").asText());
                assertThat(fremdeAntwort).doesNotContain(fremdeSeite.toString(), "Isolationsprobe-", "Inhalt-");
                assertThat(eigeneAntwort).contains("Isolationsprobe-" + eigeneSeite, "Inhalt-" + eigeneSeite);
                for (String suffix : List.of("/webseitenfreigabe", "/versionen", "/versionen/1",
                        "/diff?von=1&bis=1", "/eingehend")) {
                    wissensseitenIsolationPruefen(token, fremdeSeite, suffix, "GET", null, null);
                }
                wissensseitenIsolationPruefen(token, fremdeSeite, "/webseitenfreigabe", "POST",
                        "{\"freigeben\":false}", fremdeEtag);
                wissensseitenIsolationPruefen(token, fremdeSeite, "/quelle-geprueft", "POST", null, fremdeEtag);
            }
        } catch (Throwable t) {
            testFehler = t;
            throw t;
        } finally {
            Throwable fehler = null;
            try (Connection verbindung = datenbank.get().getConnection()) {
                verbindung.setAutoCommit(false);
                try (var befehl = verbindung.createStatement()) {
                    befehl.execute("set local session_replication_role = replica");
                }
                for (String schema : List.of("m_urinet", "m_vhf_gsk")) {
                    try (PreparedStatement versionLoeschen = verbindung.prepareStatement(
                            "delete from " + schema + ".wissensseite_version where seite_id = ?");
                         PreparedStatement seiteLoeschen = verbindung.prepareStatement(
                                 "delete from " + schema + ".wissensseite where id = ?")) {
                        UUID seite = schema.equals("m_urinet") ? urinetSeite : vhfSeite;
                        versionLoeschen.setObject(1, seite);
                        versionLoeschen.executeUpdate();
                        seiteLoeschen.setObject(1, seite);
                        seiteLoeschen.executeUpdate();
                    }
                }
                verbindung.commit();
            } catch (Exception e) {
                fehler = merken(fehler, e);
            }
            for (var eintrag : java.util.Map.of("urinet", java.util.Optional.ofNullable(urinetDokument),
                    "vhf-gsk", java.util.Optional.ofNullable(vhfDokument)).entrySet()) {
                if (eintrag.getValue().isEmpty()) continue;
                try {
                    Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), List.of(eintrag.getValue().get()));
                } catch (Exception e) {
                    fehler = merken(fehler, e);
                }
            }
            if (fehler != null) {
                if (testFehler != null) testFehler.addSuppressed(fehler);
                else throw new IllegalStateException("Aufräumen fehlgeschlagen", fehler);
            }
        }
    }

    private static Throwable merken(Throwable erster, Throwable weiterer) {
        if (erster == null) return weiterer;
        erster.addSuppressed(weiterer);
        return erster;
    }

    private static ApiFehler apiFehler(io.restassured.response.Response antwort) {
        return new ApiFehler(antwort.path("type"), antwort.path("title"), antwort.path("detail"),
                antwort.path("fehlercode"));
    }

    private void dokumentIsolationPruefen(String token, UUID fremdeId, String suffix, String methode, String body,
                                          String etag) {
        String fremderPfad = "/api/v1/dokumente/" + fremdeId + suffix;
        String unbekannterPfad = "/api/v1/dokumente/" + UUID.randomUUID() + suffix;
        var fremdAnfrage = given().auth().oauth2(token);
        var unbekanntAnfrage = given().auth().oauth2(token);
        if (etag != null) {
            fremdAnfrage.header("If-Match", etag);
            unbekanntAnfrage.header("If-Match", etag);
        }
        if (body != null) {
            fremdAnfrage.contentType("application/json").body(body);
            unbekanntAnfrage.contentType("application/json").body(body);
        }
        var fremd = switch (methode) {
            case "GET" -> fremdAnfrage.get(fremderPfad);
            case "PATCH" -> fremdAnfrage.patch(fremderPfad);
            case "POST" -> fremdAnfrage.post(fremderPfad);
            case "DELETE" -> fremdAnfrage.delete(fremderPfad);
            default -> throw new IllegalArgumentException("Unbekannte Dokument-Operation");
        };
        var unbekannt = switch (methode) {
            case "GET" -> unbekanntAnfrage.get(unbekannterPfad);
            case "PATCH" -> unbekanntAnfrage.patch(unbekannterPfad);
            case "POST" -> unbekanntAnfrage.post(unbekannterPfad);
            case "DELETE" -> unbekanntAnfrage.delete(unbekannterPfad);
            default -> throw new IllegalArgumentException("Unbekannte Dokument-Operation");
        };
        assertThat(fremd.statusCode()).isIn(403, 404);
        assertThat(fremd.statusCode()).isEqualTo(unbekannt.statusCode());
        assertThat(apiFehler(fremd)).isEqualTo(apiFehler(unbekannt));
        assertThat(fremd.asString()).doesNotContain(fremdeId.toString(), "Fremder Schreibversuch");
    }

    private String seitenEtag(String token, UUID id) {
        return given().auth().oauth2(token).get("/api/v1/wissensseiten/" + id)
                .then().statusCode(200).extract().header("ETag");
    }

    private void wissensseitenLesewegePruefen(String token, UUID id) {
        for (String suffix : List.of("/webseitenfreigabe", "/versionen", "/versionen/1",
                "/diff?von=1&bis=1", "/eingehend")) {
            var antwort = given().auth().oauth2(token).get("/api/v1/wissensseiten/" + id + suffix);
            assertThat(antwort.statusCode()).as("Wissensseiten-Endpunkt %s", suffix).isEqualTo(200);
        }
    }

    private void wissensseitenIsolationPruefen(String token, UUID fremdeId, String suffix, String methode,
                                                String body, String etag) {
        String fremderPfad = "/api/v1/wissensseiten/" + fremdeId + suffix;
        String unbekannterPfad = "/api/v1/wissensseiten/" + UUID.randomUUID() + suffix;
        var fremdAnfrage = given().auth().oauth2(token);
        var unbekanntAnfrage = given().auth().oauth2(token);
        if (etag != null) {
            fremdAnfrage.header("If-Match", etag);
            unbekanntAnfrage.header("If-Match", etag);
        }
        if (body != null) {
            fremdAnfrage.contentType("application/json").body(body);
            unbekanntAnfrage.contentType("application/json").body(body);
        }
        var fremd = switch (methode) {
            case "GET" -> fremdAnfrage.get(fremderPfad);
            case "POST" -> fremdAnfrage.post(fremderPfad);
            default -> throw new IllegalArgumentException("Unbekannte Wissensseiten-Operation");
        };
        var unbekannt = switch (methode) {
            case "GET" -> unbekanntAnfrage.get(unbekannterPfad);
            case "POST" -> unbekanntAnfrage.post(unbekannterPfad);
            default -> throw new IllegalArgumentException("Unbekannte Wissensseiten-Operation");
        };
        assertThat(fremd.statusCode()).isIn(403, 404);
        assertThat(fremd.statusCode()).isEqualTo(unbekannt.statusCode());
        assertThat(apiFehler(fremd)).isEqualTo(apiFehler(unbekannt));
        assertThat(fremd.asString()).doesNotContain(fremdeId.toString(), "Isolationsprobe-", "Inhalt-");
    }

    private void wissensseiteAnlegen(String schema, UUID seite, UUID dokument) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("""
                     insert into %s.wissensseite
                         (id, pfad, typ, titel, beschreibung, tags, vertraulichkeit, vertraulichkeit_mindest, inhalt,
                          version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                     values (?, ?, 'Konzept', ?, 'Synthetisch', array['isolation'],
                             'NORMAL', 'NORMAL', ?, 1, 'probe', now(), 'probe', now())
                     """.formatted(schema));
             PreparedStatement huelle = verbindung.prepareStatement(
                     "insert into " + schema + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)");
             PreparedStatement version = verbindung.prepareStatement("""
                     insert into %s.wissensseite_version
                         (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit)
                     values (?, 1, jsonb_build_object('pfad', ?, 'typ', 'Konzept', 'titel', ?,
                         'beschreibung', 'Synthetisch', 'tags', jsonb_build_array('isolation'),
                         'status', 'AKTIV', 'vertraulichkeit', 'NORMAL',
                         'vertraulichkeit_mindest', 'NORMAL'), ?, 'probe', 'probe', now())
                     """.formatted(schema))) {
            einfuegen.setObject(1, seite);
            einfuegen.setString(2, "konzepte/isolation-" + seite);
            einfuegen.setString(3, "Isolationsprobe-" + seite);
            einfuegen.setString(4, "Inhalt-" + seite);
            einfuegen.executeUpdate();
            huelle.setObject(1, seite);
            huelle.setObject(2, dokument);
            huelle.executeUpdate();
            version.setObject(1, seite);
            version.setString(2, "konzepte/isolation-" + seite);
            version.setString(3, "Isolationsprobe-" + seite);
            version.setString(4, "Inhalt-" + seite);
            version.executeUpdate();
        }
    }

    @Test
    void fremderMandantInHeaderQueryUndKonfigurationsdateiAendertNichtDenZielmandanten() throws Exception {
        String urinet = Testanmeldung.token("inhaber-urinet");
        String vhf = Testanmeldung.token("inhaber-vhf-gsk");
        String eigeneDatei = given().auth().oauth2(urinet).when().get("/api/v1/konfiguration")
                .then().statusCode(200).extract().asString();
        String andereDatei = given().auth().oauth2(vhf).when().get("/api/v1/konfiguration")
                .then().statusCode(200).extract().asString();

        String mitFremdemMandanten = given().auth().oauth2(urinet)
                .header("X-Mandant", "vhf-gsk").queryParam("mandant", "vhf-gsk")
                .when().get("/api/v1/konfiguration").then().statusCode(200).extract().asString();
        assertThat(mitFremdemMandanten).isEqualTo(eigeneDatei);

        ObjectNode datei = (ObjectNode) new ObjectMapper().readTree(eigeneDatei);
        datei.put("mandant", "vhf-gsk");
        String fehlercode = given().auth().oauth2(urinet).contentType("application/json").body(datei.toString())
                .when().put("/api/v1/konfiguration").then().statusCode(422).extract().path("fehlercode");
        assertThat(fehlercode).isEqualTo("KONFIGURATION_UNGUELTIG");
        assertThat(given().auth().oauth2(urinet).when().get("/api/v1/konfiguration")
                .then().statusCode(200).extract().asString()).isEqualTo(eigeneDatei);
        assertThat(given().auth().oauth2(vhf).when().get("/api/v1/konfiguration")
                .then().statusCode(200).extract().asString()).isEqualTo(andereDatei);
    }

    @Test
    void konfigurationsRessourcenSindWieUnbekannteIDsIsoliert() throws Exception {
        String klassifikation = "iso_" + UUID.randomUUID().toString().replace("-", "");
        String wert = "wert_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String regel = "iso_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        int version = naechsteKonfigurationsversion();
        try {
            konfigurationsRessourcenAnlegen(klassifikation, wert, regel, version);
            String tokenA = Testanmeldung.token("inhaber-urinet");
            String tokenB = Testanmeldung.token("inhaber-vhf-gsk");
            String etagA = given().auth().oauth2(tokenA).get("/api/v1/dokumentarten")
                    .then().statusCode(200).extract().header("ETag");

            assertThat(given().auth().oauth2(tokenB).get("/api/v1/dokumentarten/BULLETIN/klassifikationen")
                    .then().statusCode(200).extract().asString()).contains(klassifikation);
            assertThat(given().auth().oauth2(tokenB).get("/api/v1/klassifikationen")
                    .then().statusCode(200).extract().asString()).contains(klassifikation);
            assertThat(given().auth().oauth2(tokenB).get("/api/v1/regeln")
                    .then().statusCode(200).extract().asString()).contains(regel);
            given().auth().oauth2(tokenB).get("/api/v1/konfiguration/versionen/" + version)
                    .then().statusCode(200);

            List<KonfigurationsProbe> proben = List.of(
                    new KonfigurationsProbe("GET", "/api/v1/dokumentarten/BULLETIN/klassifikationen",
                            "/api/v1/dokumentarten/ISO_UNBEKANNT/klassifikationen", null, false),
                    new KonfigurationsProbe("POST", "/api/v1/dokumentarten/BULLETIN/klassifikationen",
                            "/api/v1/dokumentarten/ISO_UNBEKANNT/klassifikationen",
                            "{\"klassifikation\":\"" + klassifikation + "\",\"pflicht\":false,"
                                    + "\"standardWert\":\"" + wert + "\"}", true),
                    new KonfigurationsProbe("PATCH", "/api/v1/dokumentarten/BULLETIN/klassifikationen/"
                            + klassifikation, "/api/v1/dokumentarten/ISO_UNBEKANNT/klassifikationen/"
                            + klassifikation, "{\"pflicht\":false}", true),
                    new KonfigurationsProbe("DELETE", "/api/v1/dokumentarten/BULLETIN/klassifikationen/"
                            + klassifikation, "/api/v1/dokumentarten/ISO_UNBEKANNT/klassifikationen/"
                            + klassifikation, null, true),
                    new KonfigurationsProbe("PATCH", "/api/v1/dokumentarten/BULLETIN",
                            "/api/v1/dokumentarten/ISO_UNBEKANNT", "{\"name\":\"Probe Änderung\"}", true),
                    new KonfigurationsProbe("DELETE", "/api/v1/dokumentarten/BULLETIN",
                            "/api/v1/dokumentarten/ISO_UNBEKANNT", null, true),
                    new KonfigurationsProbe("PATCH", "/api/v1/klassifikationen/" + klassifikation,
                            "/api/v1/klassifikationen/iso_unbekannt", "{\"name\":\"Probe Änderung\"}", true),
                    new KonfigurationsProbe("DELETE", "/api/v1/klassifikationen/" + klassifikation,
                            "/api/v1/klassifikationen/iso_unbekannt", null, true),
                    new KonfigurationsProbe("POST", "/api/v1/klassifikationen/" + klassifikation + "/werte",
                            "/api/v1/klassifikationen/iso_unbekannt/werte",
                            "{\"code\":\"probe_neu\",\"name\":\"Probe neu\"}", true),
                    new KonfigurationsProbe("PATCH", "/api/v1/klassifikationen/" + klassifikation
                            + "/werte/" + wert, "/api/v1/klassifikationen/iso_unbekannt/werte/" + wert,
                            "{\"name\":\"Probe Änderung\"}", true),
                    new KonfigurationsProbe("DELETE", "/api/v1/klassifikationen/" + klassifikation
                            + "/werte/" + wert, "/api/v1/klassifikationen/iso_unbekannt/werte/" + wert,
                            null, true),
                    new KonfigurationsProbe("PATCH", "/api/v1/regeln/" + regel,
                            "/api/v1/regeln/iso_unbekannt", "{\"meldung\":\"Probe Änderung\"}", true),
                    new KonfigurationsProbe("DELETE", "/api/v1/regeln/" + regel,
                            "/api/v1/regeln/iso_unbekannt", null, true),
                    new KonfigurationsProbe("GET", "/api/v1/konfiguration/versionen/" + version,
                            "/api/v1/konfiguration/versionen/2147483647", null, false));
            for (KonfigurationsProbe probe : proben) {
                konfigurationsIsolationPruefen(tokenA, probe, etagA, klassifikation, wert, regel);
            }
        } finally {
            konfigurationsRessourcenEntfernen(klassifikation, regel, version);
        }
    }

    private record KonfigurationsProbe(String methode, String fremderPfad, String unbekannterPfad, String body,
                                       boolean konfigurationsEtag) {
    }

    private void konfigurationsIsolationPruefen(String token, KonfigurationsProbe probe, String etag,
                                                 String... marker) {
        var fremdAnfrage = given().auth().oauth2(token);
        var unbekanntAnfrage = given().auth().oauth2(token);
        if (probe.konfigurationsEtag()) {
            fremdAnfrage.header("If-Match", etag);
            unbekanntAnfrage.header("If-Match", etag);
        }
        if (probe.body() != null) {
            fremdAnfrage.contentType("application/json").body(probe.body());
            unbekanntAnfrage.contentType("application/json").body(probe.body());
        }
        var fremd = fremdAnfrage.request(probe.methode(), probe.fremderPfad());
        var unbekannt = unbekanntAnfrage.request(probe.methode(), probe.unbekannterPfad());
        assertThat(fremd.statusCode()).as("%s %s", probe.methode(), probe.fremderPfad()).isIn(403, 404);
        assertThat(fremd.statusCode()).isEqualTo(unbekannt.statusCode());
        assertThat(apiFehler(fremd)).isEqualTo(apiFehler(unbekannt));
        for (String wert : marker) {
            assertThat(fremd.asString()).doesNotContain(wert);
        }
    }

    private int naechsteKonfigurationsversion() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("""
                     select greatest(
                         (select coalesce(max(version), 0) + 1 from m_urinet.konfiguration_version),
                         (select coalesce(max(version), 0) + 1 from m_vhf_gsk.konfiguration_version))
                     """);
             ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            return zeile.getInt(1);
        }
    }

    private void konfigurationsRessourcenAnlegen(String klassifikation, String wert, String regel, int version)
            throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement klasse = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.klassifikation
                         (code, name, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter, aktiv)
                     values (?, 'Isolationsprobe', false, false, true, false, true)
                     """);
             PreparedStatement klassifikationswert = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.klassifikation_wert (klassifikation, code, name, reihenfolge, aktiv)
                     values (?, ?, 'Probe', 0, true)
                     """);
             PreparedStatement zuordnung = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.dokumentart_klassifikation (art, klassifikation, pflicht, standard_wert)
                     values ('BULLETIN', ?, false, ?)
                     """);
             PreparedStatement regelEinfuegen = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.regel (code, art, typ, parameter, schwere, meldung, aktiv)
                     values (?, 'BULLETIN', 'ERWARTET', '{}'::jsonb, 'WARNUNG', 'Isolationsprobe', true)
                     """);
             PreparedStatement versionEinfuegen = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.konfiguration_version (version, wer, quelle, inhalt, sha256)
                     values (?, 'test:isolation', 'APP', '{"probe":"Mandant B"}'::jsonb, repeat('a', 64))
                     """)) {
            klasse.setString(1, klassifikation);
            klasse.executeUpdate();
            klassifikationswert.setString(1, klassifikation);
            klassifikationswert.setString(2, wert);
            klassifikationswert.executeUpdate();
            zuordnung.setString(1, klassifikation);
            zuordnung.setString(2, wert);
            zuordnung.executeUpdate();
            regelEinfuegen.setString(1, regel);
            regelEinfuegen.executeUpdate();
            versionEinfuegen.setInt(1, version);
            versionEinfuegen.executeUpdate();
        }
    }

    private void konfigurationsRessourcenEntfernen(String klassifikation, String regel, int version)
            throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try (var befehl = verbindung.createStatement()) {
                befehl.execute("set local session_replication_role = replica");
            }
            try (PreparedStatement regelLoeschen = verbindung.prepareStatement(
                    "delete from m_vhf_gsk.regel where code = ?");
                 PreparedStatement zuordnungLoeschen = verbindung.prepareStatement(
                         "delete from m_vhf_gsk.dokumentart_klassifikation where klassifikation = ?");
                 PreparedStatement werteLoeschen = verbindung.prepareStatement(
                         "delete from m_vhf_gsk.klassifikation_wert where klassifikation = ?");
                 PreparedStatement klasseLoeschen = verbindung.prepareStatement(
                         "delete from m_vhf_gsk.klassifikation where code = ?");
                 PreparedStatement versionLoeschen = verbindung.prepareStatement(
                         "delete from m_vhf_gsk.konfiguration_version where version = ?")) {
                regelLoeschen.setString(1, regel);
                regelLoeschen.executeUpdate();
                zuordnungLoeschen.setString(1, klassifikation);
                zuordnungLoeschen.executeUpdate();
                werteLoeschen.setString(1, klassifikation);
                werteLoeschen.executeUpdate();
                klasseLoeschen.setString(1, klassifikation);
                klasseLoeschen.executeUpdate();
                versionLoeschen.setInt(1, version);
                versionLoeschen.executeUpdate();
            }
            verbindung.commit();
        }
    }

    private String exportieren(String mandant) throws Exception {
        Path datei = ordner.resolve(mandant + ".json");
        ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        int status = thread(() -> kommando.ausfuehren(List.of("exportieren", mandant, datei.toString()),
                new PrintStream(ausgabe, true, StandardCharsets.UTF_8),
                new PrintStream(fehler, true, StandardCharsets.UTF_8)));
        assertThat(status).as(fehler.toString(StandardCharsets.UTF_8)).isZero();
        return Files.readString(datei);
    }

    private <T> T in(String mandant, Supplier<T> aktion) throws Exception {
        return thread(() -> kontext.mit(new MandantCode(mandant), aktion));
    }

    private static <T> T thread(Callable<T> aktion) throws Exception {
        FutureTask<T> arbeit = new FutureTask<>(aktion);
        Thread.ofVirtual().start(arbeit);
        return arbeit.get();
    }
}

@QuarkusTest
class MandantenIsolationTest extends MandantenIsolationIT {
}
