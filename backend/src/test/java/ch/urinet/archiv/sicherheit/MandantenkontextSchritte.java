package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.probe.ProbeSichtbarkeit;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 1.2, 1.3, 1.4 (Teilnachweis), 1.6, 2.2, 2.3, 2.4 und 2.6 über die Probe-Endpunkte unter src/test. */
public class MandantenkontextSchritte {

    private static final String DOKUMENTARTEN = "/api/v1/probe/dokumentarten/";
    private static final String DOKUMENTE = "/api/v1/probe/dokumente/";
    private static final String ANFRAGE = "/api/v1/probe/anfrage";

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantKontext kontext;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final List<String> eingetragen = new ArrayList<>();
    private Response antwort;
    private boolean gefunden;

    @Angenommen("die Mandanten «{word}» und «{word}» sind angelegt")
    public void mandantenAngelegt(String a, String b) throws SQLException {
        for (String code : List.of(a, b)) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
    }

    @Angenommen("im Register steht der inaktive Mandant «{word}»")
    public void inaktiv(String code) throws SQLException {
        eingetragen.add(code);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung.prepareStatement(
                "insert into public.mandant (code, name, schema_name, bucket, aktiv) values (?, ?, ?, ?, false) "
                        + "on conflict (code) do update set aktiv = false")) {
            einfuegen.setString(1, code);
            einfuegen.setString(2, code);
            einfuegen.setString(3, new MandantCode(code).schema());
            einfuegen.setString(4, "urinet-archiv-" + code);
            einfuegen.executeUpdate();
        }
    }

    @Wenn("«{word}» die Dokumentart «{word}» abfragt")
    public void abfragen(String benutzer, String code) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(DOKUMENTARTEN + code);
    }

    @Wenn("«{word}» die Dokumentart «{word}» abfragt und dabei «{word}» als Mandant in Header und Query angibt")
    public void abfragenMitFremdemMandanten(String benutzer, String code, String mandant) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("X-Mandant", mandant)
                .header("mandant", mandant).queryParam("mandant", mandant).when().get(DOKUMENTARTEN + code);
    }

    @Wenn("«{word}» ein Dokument erfasst")
    public void erfassen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().post("/api/v1/probe/erfassen");
    }

    @Wenn("«{word}» das Probe-Dokument «{word}» liest")
    public void dokumentLesen(String benutzer, String dokument) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(DOKUMENTE + dokumentId(dokument));
    }

    @Wenn("«{word}» das Probe-Dokument «{word}» über die IP «{}» liest")
    public void dokumentLesenMitIp(String benutzer, String dokument, String ip) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("X-Real-Ip", ip).when()
                .get(DOKUMENTE + dokumentId(dokument));
    }

    @Wenn("«{word}» das Probe-Dokument «{word}» ändert und dabei die Dokumentart «{word}» anlegt")
    public void dokumentAendern(String benutzer, String dokument, String code) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("art", code).when()
                .patch(DOKUMENTE + dokumentId(dokument));
    }

    @Wenn("«{word}» das Probe-Dokument «{word}» bestätigt")
    public void dokumentBestaetigen(String benutzer, String dokument) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                .post(DOKUMENTE + dokumentId(dokument) + "/bestaetigen");
    }

    @Wenn("«{word}» an die Probe-Anfrage JSON mit dem Content-Type «{}» sendet")
    public void falscherMedientyp(String benutzer, String medientyp) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(medientyp)
                .body("{\"anzahl\": 1}").when().post(ANFRAGE);
    }

    @Dann("antwortet das Archiv mit Status {int} und liefert das Probe-Dokument «{word}»")
    public void mitDokument(int status, String dokument) {
        assertThat(antwort.statusCode()).isEqualTo(status);
        assertThat(antwort.<String>path("id")).isEqualTo(dokumentId(dokument));
    }

    private static String dokumentId(String name) {
        return ProbeSichtbarkeit.NAMEN.containsKey(name) ? ProbeSichtbarkeit.NAMEN.get(name).toString() : name;
    }

    /** Erfolg ohne Inhalt (204) oder Problem Details ohne Daten (RFC 9457, design.md «Error Handling»). */
    @Dann("antwortet das Archiv mit Status {int} und liefert keine Daten")
    public void ohneDaten(int status) {
        assertThat(antwort.statusCode()).isEqualTo(status);
        if (status < 400) {
            assertThat(antwort.body().asString()).isEmpty();
        } else {
            ProblemDetails.pruefen(antwort, status);
        }
    }

    @Dann("antwortet das Archiv mit Status {int} und Fehlercode «{word}»")
    public void mitFehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Dann("antwortet das Archiv mit Status {int} und liefert die Dokumentart «{word}»")
    public void mitDaten(int status, String code) {
        assertThat(antwort.statusCode()).isEqualTo(status);
        assertThat(antwort.<String>path("code")).isEqualTo(code);
    }

    @Wenn("der Worker im Mandanten-Kontext «{word}» die Dokumentart «{word}» liest")
    public void worker(String mandant, String code) throws Exception {
        // eigener Thread ohne Request-Kontext, wie der Worker; der Test-Thread hat schon einen aktiven Kontext
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            gefunden = worker.submit(() -> kontext.mit(new MandantCode(mandant),
                    () -> QuarkusTransaction.requiringNew().call(() -> DokumentartEintrag.findById(code) != null))).get();
        } finally {
            worker.shutdown();
        }
    }

    @Dann("findet der Worker die Dokumentart")
    public void gefunden() {
        assertThat(gefunden).isTrue();
    }

    @Dann("findet der Worker die Dokumentart nicht")
    public void nichtGefunden() {
        assertThat(gefunden).isFalse();
    }

    @Dann("gilt in der Rechtematrix:")
    public void rechtematrix(DataTable tabelle) {
        List<Map<String, String>> zeilen = tabelle.asMaps();
        assertThat(zeilen).extracting(z -> z.get("Nr.")).containsExactly(
                Arrays.stream(Aktion.values()).map(Enum::name).toArray(String[]::new));
        for (Map<String, String> zeile : zeilen) {
            Aktion aktion = Aktion.valueOf(zeile.get("Nr."));
            for (Rolle rolle : Rolle.values()) {
                assertThat(Rechtematrix.ergebnis(rolle, aktion)).as(aktion + " " + rolle.code())
                        .isEqualTo(RechtematrixZelle.ergebnis(zeile.get(rolle.code())));
            }
        }
    }

    @After
    public void aufraeumen() throws SQLException {
        for (String code : eingetragen) {
            try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement loeschen =
                    verbindung.prepareStatement("delete from public.mandant where code = ?")) {
                loeschen.setString(1, code);
                loeschen.executeUpdate();
            }
        }
        eingetragen.clear();
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
