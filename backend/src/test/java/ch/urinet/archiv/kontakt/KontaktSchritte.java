package ch.urinet.archiv.kontakt;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.kontakt.application.KontaktVerwaltung;
import ch.urinet.archiv.kontakt.application.PartnerAbgleich;
import ch.urinet.archiv.kontakt.domain.AbgleichErgebnis;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.kontakt.domain.Merkmal;
import ch.urinet.archiv.kontakt.domain.PartnerAngaben;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Kontakten (Req 2.18, 2.6 Zeile A6, 2.5, 1.2) und zum Partner-Abgleich (Req 7.4). Der Abgleich läuft wie
 * im Worker in einem eigenen Thread im Mandanten-Kontext. Alle Werte sind synthetisch.
 */
public class KontaktSchritte {

    private static final String KONTAKTE = "/api/v1/kontakte";
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");

    @Inject
    KontaktVerwaltung verwaltung;

    @Inject
    PartnerAbgleich abgleich;

    @Inject
    MandantKontext kontext;

    @Inject
    ApiTokenVerwaltung tokens;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private String tokenId;
    private String token;

    private final Map<String, UUID> ids = new HashMap<>();
    private final List<String> werte = new ArrayList<>();
    private Response antwort;
    private AbgleichErgebnis ergebnis;
    private LogMitschnitt mitschnitt;
    private boolean aktiv;

    @Angenommen("in den Mandanten «{word}» und «{word}» gibt es keine Kontakte")
    public void keineKontakte(String a, String b) throws SQLException {
        aktiv = true;
        for (String mandant : List.of(a, b)) {
            leeren(mandant);
        }
    }

    @Angenommen("alle Logeinträge zu Kontakten werden mitgeschnitten")
    public void mitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Angenommen("«{word}» hat den Kontakt «{}» angelegt")
    public void angelegt(String benutzer, String name) {
        anlegen(benutzer, name);
        assertThat(antwort.statusCode()).isEqualTo(201);
    }

    @Angenommen("im Mandanten «{word}» gibt es die Kontakte:")
    public void kontakteAnlegen(String mandant, DataTable tabelle) throws Exception {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            KontaktAngaben angaben = new KontaktAngaben(KontaktTyp.valueOf(zeile.get("typ")), zeile.get("name"), null,
                    null, null, null, zeile.get("email"), null, zeile.get("uid"), zeile.get("iban"), null);
            Kontakt kontakt = imWorker(mandant, () -> verwaltung.anlegen(angaben));
            ids.put(zeile.get("name"), kontakt.id());
        }
    }

    @Wenn("«{word}» den Kontakt «{}» anlegt")
    public void anlegen(String benutzer, String name) {
        senden(benutzer, Map.of("typ", "ORGANISATION", "name", name), null);
    }

    @Wenn("«{word}» den Kontakt «{}» samt E-Mail «{}» erfasst")
    public void anlegenMitEmail(String benutzer, String name, String email) {
        senden(benutzer, Map.of("typ", "ORGANISATION", "name", name, "email", email), null);
    }

    @Wenn("«{word}» einen Kontakt ohne Name anlegt")
    public void ohneName(String benutzer) {
        senden(benutzer, Map.of("typ", "ORGANISATION", "name", " "), null);
    }

    @Wenn("«{word}» einen Kontakt anlegt, dessen Feld «{word}» {int} Zeichen hat")
    public void anlegenMitLaenge(String benutzer, String feld, int laenge) {
        Map<String, String> koerper = new HashMap<>(Map.of("typ", "ORGANISATION", "name", "Musterfirma Probe AG"));
        koerper.put(feld, "x".repeat(laenge));
        senden(benutzer, koerper, null);
    }

    @Wenn("«{word}» den Kontakt «{}» so ändert, dass das Feld «{word}» {int} Zeichen hat")
    public void aendernMitLaenge(String benutzer, String name, String feld, int laenge) {
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(name) + "\"")
                .body(Map.of(feld, "x".repeat(laenge))).when().patch(KONTAKTE + "/" + ids.get(name));
    }

    @Wenn("«{word}» den Kontakt «{}» in «{}» umbenennt")
    public void umbenennen(String benutzer, String name, String neu) {
        aendern(benutzer, name, neu, "\"" + version(name) + "\"");
    }

    @Wenn("«{word}» den Kontakt «{}» ohne If-Match umbenennt")
    public void ohneIfMatch(String benutzer, String name) {
        aendern(benutzer, name, "Ohne Version AG", null);
    }

    @Wenn("«{word}» den Kontakt «{}» mit If-Match «{}» umbenennt")
    public void mitIfMatch(String benutzer, String name, String ifMatch) {
        aendern(benutzer, name, "Falsche Version AG", ifMatch);
    }

    @Wenn("«{word}» Kontakte mit «{}» sucht")
    public void suchen(String benutzer, String text) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("q", text).when().get(KONTAKTE);
    }

    @Angenommen("für «{word}» gibt es ein API-Token mit Scope «{word}»")
    public void apiToken(String mandant, String scope) {
        ApiTokenVerwaltung.Erstellt erstellt = tokens.erstellen(mandant, "Probe Kontakte", scope);
        tokenId = erstellt.client().id();
        token = erstellt.token().text();
    }

    @Wenn("das API-Token einen Kontakt «{}» anlegt")
    public void tokenAnlegen(String name) {
        antwort = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(Map.of("typ", "ORGANISATION", "name", name)).when().post(KONTAKTE);
    }

    @Wenn("das API-Token Kontakte mit «{}» sucht")
    public void tokenSuchen(String text) {
        antwort = given().header("Authorization", "Bearer " + token).queryParam("q", text).when().get(KONTAKTE);
    }

    /** Liste, Detail, Zusammenführen und Markieren (Req 17.5, 17.6) mit dem API-Token; {@code id} beliebig. */
    @Wenn("das API-Token die Kontakte über «{word} {}» erreichen will")
    public void tokenPflegen(String methode, String pfad) {
        String ziel = KONTAKTE + pfad.replace("{id}", UUID.randomUUID().toString());
        RequestSpecification anfrage = given().header("Authorization", "Bearer " + token).header("If-Match", "\"0\"")
                .contentType(ContentType.JSON);
        antwort = "GET".equals(methode) ? anfrage.when().get(ziel)
                : anfrage.body(Map.of("grund", "Probe")).when().post(ziel);
    }

    @Wenn("der Abgleich in «{word}» den Partner «{}» mit E-Mail «{}», UID «{}» und IBAN «{}» sucht")
    public void abgleichen(String mandant, String name, String email, String uid, String iban) throws Exception {
        PartnerAngaben angaben = new PartnerAngaben(name, email, uid, iban);
        ergebnis = imWorker(mandant, () -> abgleich.abgleichen(angaben));
    }

    @Dann("antwortet die Kontakt-Schnittstelle mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).isEqualTo(status);
    }

    @Dann("antwortet die Kontakt-Schnittstelle mit Status {int} und Fehlercode «{word}»")
    public void fehler(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Dann("antwortet die Kontakt-Schnittstelle mit Status {int} und ETag «{}»")
    public void etag(int status, String etag) {
        assertThat(antwort.statusCode()).isEqualTo(status);
        assertThat(antwort.header("ETag")).isEqualTo(etag);
        assertThat(antwort.<Integer>path("version")).isEqualTo(Integer.parseInt(etag.replace("\"", "")));
    }

    @Dann("der Kontakt hat die E-Mail «{}» und die Quelle «{word}»")
    public void kontaktWerte(String email, String quelle) {
        assertThat(antwort.<String>path("email")).isEqualTo(email);
        assertThat(antwort.<String>path("quelle")).isEqualTo(quelle);
    }

    @Dann("der Kontakt «{}» hat noch Version {int}")
    public void versionUnveraendert(String name, int version) {
        assertThat(version(name)).isEqualTo(version);
    }

    @Dann("es gibt keine Kontakte mehr")
    public void keineMehr() throws SQLException {
        for (String mandant : MANDANTEN) {
            assertThat(anzahl(mandant)).as(mandant).isZero();
        }
    }

    @Dann("enthält die Trefferliste den Kontakt «{}»")
    public void enthaelt(String name) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("id")).contains(ids.get(name).toString());
    }

    @Dann("enthält die Trefferliste den Kontakt «{}» nicht")
    public void enthaeltNicht(String name) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("id")).doesNotContain(ids.get(name).toString());
        assertThat(antwort.jsonPath().<String>getList("name")).doesNotContain(name);
    }

    @Dann("verknüpft der Abgleich den Kontakt «{}» über «{word}»")
    public void verknuepft(String name, String merkmal) {
        assertThat(ergebnis).isEqualTo(new AbgleichErgebnis.Verknuepft(ids.get(name), Merkmal.valueOf(merkmal)));
    }

    @Dann("ist der Abgleich unsicher zwischen «{}» und «{}»")
    public void unsicher(String a, String b) {
        assertThat(ergebnis).isInstanceOf(AbgleichErgebnis.Unsicher.class);
        assertThat(((AbgleichErgebnis.Unsicher) ergebnis).kandidaten()).containsExactlyInAnyOrder(ids.get(a), ids.get(b));
    }

    /** «verknüpft: Name», «unsicher: Name, Name» (Reihenfolge bei Gleichstand offen) oder «neuer Partner». */
    @Dann("ergibt der Abgleich «{}»")
    public void ergibt(String erwartet) {
        if (erwartet.startsWith("verknüpft: ")) {
            verknuepft(erwartet.substring("verknüpft: ".length()), "NAME");
        } else if (erwartet.startsWith("unsicher: ")) {
            List<UUID> kandidaten = Arrays.stream(erwartet.substring("unsicher: ".length()).split(","))
                    .map(String::strip).map(ids::get).toList();
            assertThat(ergebnis).isInstanceOf(AbgleichErgebnis.Unsicher.class);
            AbgleichErgebnis.Unsicher unsicher = (AbgleichErgebnis.Unsicher) ergebnis;
            assertThat(unsicher.merkmal()).isEqualTo(Merkmal.NAME);
            assertThat(unsicher.kandidaten()).containsExactlyInAnyOrderElementsOf(kandidaten);
        } else {
            assertThat(erwartet).isEqualTo("neuer Partner");
            assertThat(ergebnis).isEqualTo(new AbgleichErgebnis.NeuerPartner());
        }
    }

    @Dann("kein Logeintrag enthält einen Wert der Kontakte")
    public void logOhneWerte() {
        String log = mitschnitt.volltext().toLowerCase(Locale.ROOT);
        assertThat(werte).isNotEmpty();
        for (String wert : werte) {
            assertThat(log).as("Log").doesNotContain(wert.strip().toLowerCase(Locale.ROOT));
            String normalisiert = Normalisierer.name(wert);
            if (normalisiert != null && !normalisiert.isBlank()) {
                assertThat(log).as("Log").doesNotContain(normalisiert);
            }
        }
        assertThat(log).contains(ids.get("Musterfirma Probe AG").toString());
    }

    @After
    public void aufraeumen() throws SQLException {
        if (mitschnitt != null) {
            mitschnitt.close();
        }
        if (tokenId != null) {
            try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement loeschen =
                    verbindung.prepareStatement("delete from public.api_client where id = ?")) {
                loeschen.setString(1, tokenId);
                loeschen.execute();
            }
        }
        if (aktiv) {
            for (String mandant : MANDANTEN) {
                leeren(mandant);
            }
        }
    }

    private void senden(String benutzer, Map<String, String> koerper, String name) {
        werte.addAll(koerper.entrySet().stream().filter(e -> !e.getKey().equals("typ")).map(Map.Entry::getValue)
                .filter(w -> !w.isBlank()).toList());
        antwort = anfrage(benutzer).body(koerper).when().post(KONTAKTE);
        if (antwort.statusCode() == 201) {
            ids.put(koerper.get("name"), UUID.fromString(antwort.path("id")));
        }
    }

    private void aendern(String benutzer, String name, String neu, String ifMatch) {
        werte.add(neu);
        RequestSpecification anfrage = anfrage(benutzer).body(Map.of("name", neu));
        if (ifMatch != null) {
            anfrage.header("If-Match", ifMatch);
        }
        antwort = anfrage.when().patch(KONTAKTE + "/" + ids.get(name));
    }

    private static RequestSpecification anfrage(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON);
    }

    private <T> T imWorker(String mandant, Supplier<T> arbeit) throws Exception {
        // eigener Thread ohne Request-Kontext wie der Worker; MandantKontext.mit darf nicht in einer Transaktion beginnen
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            return worker.submit(() -> kontext.mit(new MandantCode(mandant), arbeit)).get(30, TimeUnit.SECONDS);
        } finally {
            worker.shutdown();
        }
    }

    private int version(String name) {
        UUID id = ids.get(name);
        for (String mandant : MANDANTEN) {
            try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                    verbindung.prepareStatement("select version from " + schema(mandant) + ".kontakt where id = ?")) {
                abfrage.setObject(1, id);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (zeile.next()) {
                        return zeile.getInt(1);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Kontakt fehlt: " + name);
    }

    private int anzahl(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from " + schema(mandant) + ".kontakt");
             ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            return zeile.getInt(1);
        }
    }

    private void leeren(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement loeschen =
                verbindung.prepareStatement("delete from " + schema(mandant) + ".verkaufschance; delete from "
                        + schema(mandant) + ".kontakt")) {
            loeschen.execute();
        }
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
