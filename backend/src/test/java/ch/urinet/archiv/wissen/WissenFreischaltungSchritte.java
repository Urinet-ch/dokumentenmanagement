package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.wissen.application.WissenFreischalten;
import ch.urinet.archiv.wissen.application.WissenLintStarten;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 04, Requirement 22 (Wissen je Mandant freischalten). Geschaltet wird wie im Betrieb über
 * {@link WissenFreischalten}, den Anwendungsfall des Betreiber-Kommandos; nach jedem Szenario mit
 * {@code @wissen-freischaltung} sind die Standard-Testmandanten wieder freigeschaltet (Testdaten,
 * {@link WissenInTestdaten}). Alle Daten sind synthetisch.
 */
public class WissenFreischaltungSchritte {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> BESTAND = List.of(
            "select count(*) from %s.wissensseite",
            "select count(*) from %s.wissensseite_version",
            "select count(*) from %s.wissensseite_quelle",
            "select count(*) from %s.wissens_entwurf",
            "select count(*) from %s.wissens_aenderung",
            "select count(*) from %s.wissens_log",
            "select count(*) from %s.wissens_lint",
            "select count(*) from %s.wissens_einstellung",
            "select count(*) from %s.wissensquelle_markierung",
            "select count(*) from %s.wissensseite where fuer_webseite");

    @Inject
    WissenFreischalten freischalten;

    @Inject
    MandantKontext kontext;

    @Inject
    WissenLintStarten lint;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, List<Long>> gezaehlt = new LinkedHashMap<>();
    private Optional<UUID> geplanterLint;
    private long lintLaeufeVorher;

    @After("@wissen-freischaltung")
    public void standardWiederherstellen() {
        for (String code : List.of(WissenInTestdaten.URINET, WissenInTestdaten.VHF_GSK)) {
            ohneAnfrage(() -> freischalten.setzen(new MandantCode(code), true));
        }
    }

    /** Wie das Kommando: eigener Plattform-Thread ohne Anmeldung und ohne Request-Kontext des Schritts. */
    private static <T> T ohneAnfrage(java.util.concurrent.Callable<T> arbeit) {
        java.util.concurrent.FutureTask<T> aufgabe = new java.util.concurrent.FutureTask<>(arbeit);
        Thread.ofPlatform().start(aufgabe);
        try {
            return aufgabe.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @Angenommen("Wissen ist für «{word}» abgeschaltet")
    @Wenn("Wissen für «{word}» abgeschaltet wird")
    public void abschalten(String code) {
        assertThat(ohneAnfrage(() -> freischalten.setzen(new MandantCode(code), false))).isPresent();
    }

    @Angenommen("Wissen ist für «{word}» freigeschaltet")
    @Wenn("Wissen für «{word}» wieder freigeschaltet wird")
    public void freischalten(String code) {
        assertThat(ohneAnfrage(() -> freischalten.setzen(new MandantCode(code), true))).isPresent();
    }

    @Dann("ist Wissen für «{word}» nicht freigeschaltet")
    public void nichtFreigeschaltet(String code) {
        assertThat(freischalten.zustand(new MandantCode(code))).contains(false);
    }

    /**
     * Spalten {@code methode}, {@code pfad} und {@code koerper} (JSON, leer: ohne Körper); {@code <unbekannt>} im Pfad
     * wird zu einer zufälligen UUID, damit der Pfad ohne die Sperre 404 ergäbe.
     */
    @Dann("antworten für «{word}» diese Anfragen mit Status {int} und Fehlercode «{word}»:")
    public void anfragen(String benutzer, int status, String code, DataTable tabelle) {
        String token = Testanmeldung.token(benutzer);
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Response antwort = senden(given().auth().oauth2(token), zeile);
            assertThat(ProblemDetails.pruefen(antwort, status))
                    .as(zeile.get("methode") + " " + zeile.get("pfad") + ": " + antwort.asString()).isEqualTo(code);
        }
    }

    @Dann("antworten ohne Anmeldung diese Anfragen mit Status {int}:")
    public void ohneAnmeldung(int status, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Response antwort = senden(given(), zeile);
            assertThat(antwort.statusCode()).as(zeile.get("methode") + " " + zeile.get("pfad")).isEqualTo(status);
        }
    }

    private static Response senden(RequestSpecification anfrage, Map<String, String> zeile) {
        String pfad = zeile.get("pfad").replace("<unbekannt>", UUID.randomUUID().toString());
        String koerper = zeile.get("koerper");
        if (koerper != null && !koerper.isBlank()) {
            anfrage = anfrage.contentType(ContentType.JSON).body(koerper);
        }
        return anfrage.when().request(zeile.get("methode"), pfad);
    }

    @Angenommen("der Bestand der Wissensbasis von «{word}» ist gezählt")
    public void bestandZaehlen(String code) throws SQLException {
        gezaehlt.put(code, bestand(code));
    }

    @Dann("ist der Bestand der Wissensbasis von «{word}» unverändert")
    public void bestandUnveraendert(String code) throws SQLException {
        assertThat(gezaehlt).containsKey(code);
        assertThat(bestand(code)).as("Seiten, Versionen, Quellen, Entwürfe, Änderungen, Log, Lint, Einstellungen, "
                + "Markierungen, Webfreigaben").isEqualTo(gezaehlt.get(code));
    }

    private List<Long> bestand(String code) throws SQLException {
        String schema = new MandantCode(code).schema();
        List<Long> zahlen = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String sql : BESTAND) {
                try (PreparedStatement abfrage = verbindung.prepareStatement(sql.formatted(schema));
                     ResultSet zeile = abfrage.executeQuery()) {
                    zeile.next();
                    zahlen.add(zeile.getLong(1));
                }
            }
        }
        return zahlen;
    }

    @Wenn("der Lint-Zeitplan von «{word}» jetzt fällig ist")
    public void lintFaellig(String code) throws SQLException {
        lintLaeufeVorher = zahl("select count(*) from " + new MandantCode(code).schema() + ".wissens_lint");
        geplanterLint = ohneAnfrage(() -> kontext.mit(new MandantCode(code), () -> lint.geplant(Instant.now())));
    }

    @Dann("legt der Zeitplan in «{word}» keinen Lint-Lauf an")
    public void keinLint(String code) throws SQLException {
        assertThat(geplanterLint).isEmpty();
        assertThat(zahl("select count(*) from " + new MandantCode(code).schema() + ".wissens_lint"))
                .isEqualTo(lintLaeufeVorher);
    }

    @Dann("wartet in «{word}» kein Auftrag der Wissensbasis")
    public void keinAuftrag(String code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from public.auftrag where mandant = ? "
                        + "and typ like 'WISSEN%' and status in ('OFFEN', 'LAEUFT')")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                assertThat(zeile.getLong(1)).isZero();
            }
        }
    }

    @Dann("bietet die Anfrage {int} dem Sprachmodell genau die Werkzeuge «{}»")
    public void werkzeugeDerAnfrage(int nummer, String namen) throws Exception {
        List<String> anfragen = KiWireMock.chatAnfragen();
        assertThat(anfragen).hasSizeGreaterThanOrEqualTo(nummer);
        JsonNode anfrage = JSON.readTree(anfragen.get(nummer - 1));
        List<String> da = new ArrayList<>();
        anfrage.path("tools").forEach(t -> da.add(t.path("function").path("name").asText()));
        assertThat(da).containsExactlyInAnyOrderElementsOf(Arrays.stream(namen.split(",")).map(String::strip).toList());
    }

    private long zahl(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql); ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            return zeile.getLong(1);
        }
    }
}
