package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.builder.MultiPartSpecBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu den Grenzen je Schlüssel (Spec 03, Aufgabe 11.11) gegen die Probe {@code /public/v1/probe} und die Suche.
 * Schlüssel entstehen zur Laufzeit (nur synthetische Daten) und werden danach gelöscht.
 */
public class WebseitenGrenzenSchritte {

    private static final int OBERGRENZE = 200;
    private static final byte[] UPLOAD = Testdateien.pdf();
    private static final String UPLOAD_PFAD = "/public/v1/erfassung/dokumente";

    @Inject
    WebseitenSchluesselVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MeterRegistry registry;

    private final Map<String, String> schluessel = new HashMap<>();
    private final Set<UUID> dokumente = ConcurrentHashMap.newKeySet();
    private final List<String> tokenIds = new ArrayList<>();
    private Response letzte;
    private Response ablehnung;
    private int ablehnungNr;
    private final List<Response> sammlung = new ArrayList<>();

    @After
    public void grenzenAufraeumen() throws SQLException {
        if (!dokumente.isEmpty()) {
            List<UUID> ids = List.copyOf(dokumente);
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement(
                         "delete from public.auftrag where typ in ('VERARBEITEN', 'KI_VORSCHLAG') "
                                 + "and nutzlast->>'dokumentId' = any (string_to_array(?, ','))")) {
                befehl.setString(1, String.join(",", ids.stream().map(UUID::toString).toList()));
                befehl.executeUpdate();
            }
            Testdokumente.loeschen(datenbank.get(), "urinet", ids);
            TestAblage.loeschen("urinet", "eingang/",
                    key -> ids.stream().anyMatch(id -> key.contains(id.toString())));
            dokumente.clear();
        }
        for (String id : tokenIds) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement("delete from public.api_client where id = ?")) {
                befehl.setString(1, id);
                befehl.executeUpdate();
            }
        }
        tokenIds.clear();
        schluessel.clear();
    }

    private void anlegen(String name, String art) {
        String text = verwaltung.erstellen("urinet", "probe-grenzen-" + name.toLowerCase(), art,
                "lesen".equals(art) ? "oeffentlich" : null, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        schluessel.put(name, text);
        if ("erfassung".equals(art)) {
            Response antwort = mit(name).contentType("application/json")
                    .body(Map.of("name", "Testperson"))
                    .when().put("/public/v1/erfassung/bearbeiter/wp:1");
            assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(204);
        }
    }

    @Angenommen("es gibt den Lese-Schlüssel «{word}» für Grenzen")
    public void leseSchluessel(String name) {
        anlegen(name, "lesen");
    }

    @Angenommen("es gibt den Lese-Schlüssel «{word}» und den Lese-Schlüssel «{word}» für Grenzen")
    public void zweiLeseSchluessel(String a, String b) {
        anlegen(a, "lesen");
        anlegen(b, "lesen");
    }

    @Angenommen("es gibt den Erfassungs-Schlüssel «{word}» und den Erfassungs-Schlüssel «{word}» für Grenzen")
    public void zweiErfassungsSchluessel(String a, String b) {
        anlegen(a, "erfassung");
        anlegen(b, "erfassung");
    }

    private RequestSpecification mit(String name) {
        return given().header("Authorization", "Bearer " + schluessel.get(name));
    }

    private static String hash(int i) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("client-" + i).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void bisAbgelehnt(String name, String pfad, boolean post) {
        ablehnung = null;
        for (int i = 1; i <= OBERGRENZE; i++) {
            RequestSpecification r = mit(name).header("X-Archiv-Client", hash(i));
            if (i % 2 == 0) {
                r = r.header("X-Archiv-Zugriff", "oeffentlich");
            }
            Response antwort = post && UPLOAD_PFAD.equals(pfad)
                    ? upload(r, "upload-probe.pdf", "grenzen-" + name)
                    : post ? r.when().post(pfad) : r.when().get(pfad);
            if (antwort.statusCode() == 429) {
                ablehnung = antwort;
                ablehnungNr = i;
                letzte = antwort;
                return;
            }
            if (post && UPLOAD_PFAD.equals(pfad)) {
                assertThat(antwort.statusCode()).as(antwort.asString()).isIn(200, 202);
                dokumentIdMerken(antwort);
            } else {
                assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
            }
        }
    }

    @Wenn("der Schlüssel «{word}» «{}» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird")
    public void aufrufenBisAbgelehnt(String name, String pfad) {
        bisAbgelehnt(name, pfad, false);
    }

    @Wenn("der Schlüssel «{word}» «{}» mit wechselnden Clients und Stufen hochlädt, bis eine Anfrage abgelehnt wird")
    public void hochladenBisAbgelehnt(String name, String pfad) {
        bisAbgelehnt(name, pfad, true);
    }

    @Wenn("der Schlüssel «{word}» «{}» mit einem anderen Client und der Stufe «{word}» aufgerufen wird")
    public void andererClient(String name, String pfad, String stufe) {
        letzte = mit(name).header("X-Archiv-Client", hash(9999)).header("X-Archiv-Zugriff", stufe).when().get(pfad);
    }

    @Wenn("der Schlüssel «{word}» «{}» mit einem ungültigen Client aufgerufen wird")
    public void ungueltigerClient(String name, String pfad) {
        letzte = mit(name).header("X-Archiv-Client", "kein-hash").when().get(pfad);
    }

    @Wenn("der Schlüssel «{word}» «{}» ohne Header aufgerufen wird")
    public void ohneHeader(String name, String pfad) {
        letzte = mit(name).when().get(pfad);
    }

    @Wenn("der Schlüssel «{word}» «{}» ohne Header hochgeladen wird")
    public void ohneHeaderHochladen(String name, String pfad) {
        letzte = UPLOAD_PFAD.equals(pfad)
                ? upload(mit(name), "upload-probe.pdf", "grenzen-" + name)
                : mit(name).when().post(pfad);
        if (UPLOAD_PFAD.equals(pfad) && letzte.statusCode() != 429) {
            dokumentIdMerken(letzte);
        }
    }

    @Wenn("der Schlüssel «{word}» eine Chat-Frage stellt")
    public void chatFrage(String name) {
        letzte = mit(name).header("X-Archiv-Client", hash(4242)).contentType("application/json")
                .body("{\"frage\":\"Gibt es Neues?\"}").when().post("/public/v1/chat");
    }

    @Wenn("«{word}» {int} Mal «{}» abruft")
    public void internAbrufen(String benutzer, int anzahl, String pfad) {
        sammlung.clear();
        String token = Testanmeldung.token(benutzer);
        for (int i = 0; i < anzahl; i++) {
            sammlung.add(given().auth().oauth2(token).when().get(pfad));
        }
    }

    @Dann("ist keine der Antworten 429")
    public void keine429() {
        assertThat(sammlung).isNotEmpty().noneMatch(a -> a.statusCode() == 429);
    }

    @Dann("wurde die erste Anfrage zwischen der {int}. und der {int}. abgelehnt")
    public void ersteAblehnung(int von, int bis) {
        assertThat(ablehnung).as("keine Ablehnung in %d Anfragen", OBERGRENZE).isNotNull();
        assertThat(ablehnungNr).isBetween(von, bis);
    }

    @Dann("die Ablehnung ist 429 «{word}» mit «Retry-After» zwischen {int} und {int} Sekunden und dem Grund «{word}»")
    public void ablehnungPruefen(String fehlercode, int von, int bis, String grund) {
        Response antwort = ablehnung != null ? ablehnung : letzte;
        assertThat(ProblemDetails.pruefen(antwort, 429, "grund")).isEqualTo(fehlercode);
        assertThat(Integer.parseInt(antwort.header("Retry-After"))).isBetween(von, bis);
        assertThat(antwort.jsonPath().getString("grund")).isEqualTo(grund);
    }

    @Dann("die Ablehnung nennt weder den Schlüssel noch einen Header-Wert")
    public void keineWerte() {
        String text = ablehnung.asString() + ablehnung.headers();
        assertThat(text).doesNotContain(hash(1), hash(2), "oeffentlich");
        schluessel.values().forEach(s -> assertThat(text).doesNotContain(s)
                .doesNotContain(ApiToken.lesen(s).orElseThrow().id()));
    }

    @Dann("antwortet die Grenze mit Status {int}")
    public void status(int status) {
        assertThat(letzte.statusCode()).as(letzte.asString()).isEqualTo(status);
    }

    @Dann("die Kennzahl der abgelehnten Anfragen trägt nur den Grund und zählt für «{word}» mindestens {int}")
    public void kennzahl(String grund, int mindestens) {
        java.util.Collection<Meter> zaehler = registry.find("archiv.webseite.grenze.abgelehnt").meters();
        assertThat(zaehler).isNotEmpty();
        zaehler.forEach(m -> assertThat(m.getId().getTags()).hasSize(1)
                .allMatch(t -> t.getKey().equals("grund") && Set.of("ANFRAGEN", "SUCHE", "UPLOADS").contains(t.getValue())));
        Counter z = registry.find("archiv.webseite.grenze.abgelehnt").tag("grund", grund).counter();
        assertThat(z).isNotNull();
        assertThat(z.count()).isGreaterThanOrEqualTo(mindestens);
    }

    private static Response upload(RequestSpecification request, String dateiname, String referenz) {
        return request.header("X-Archiv-Bearbeiter", "wp:1")
                .multiPart(new MultiPartSpecBuilder(UPLOAD).controlName("datei")
                        .fileName(dateiname).mimeType("application/pdf").build())
                .multiPart("referenz", referenz)
                .when().post(UPLOAD_PFAD);
    }

    private void dokumentIdMerken(Response antwort) {
        String id = antwort.jsonPath().getString("id");
        if (id != null) {
            dokumente.add(UUID.fromString(id));
        }
    }
}
