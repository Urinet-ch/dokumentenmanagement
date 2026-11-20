package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
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
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
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
    MetadatenExtraktion extraktion;

    @Inject
    AgroalDataSource datenbank;

    @TempDir
    Path ordner;

    @BeforeEach
    void mandantenAnlegen() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk", "probe-verband")) {
            try (Connection verbindung = datenbank.getConnection();
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
        assertThat(verband.arten()).extracting(Konfiguration.Art::code).contains("WISSENSQUELLE");
        assertThat(urinet.arten()).extracting(Konfiguration.Art::code).contains("WISSENSQUELLE");
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
        assertThat(eigeneDatei).contains("AUSGANGSRECHNUNG").doesNotContain("PROBE_VHF_BULLETIN");
        assertThat(andereDatei).contains("PROBE_VHF_BULLETIN").doesNotContain("AUSGANGSRECHNUNG");
        given().auth().oauth2(urinet).queryParam("klassifikation.sitzungsthema", "projekte")
                .when().get("/api/v1/dokumente").then().statusCode(400);
        given().auth().oauth2(vhf).queryParam("klassifikation.sitzungsthema", "projekte")
                .when().get("/api/v1/dokumente").then().statusCode(400);
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
