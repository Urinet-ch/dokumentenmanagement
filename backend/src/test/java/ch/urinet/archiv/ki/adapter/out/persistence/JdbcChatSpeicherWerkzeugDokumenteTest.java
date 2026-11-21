package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.ChatSpeicher;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.ki.application.chat.ChatWerkzeugDokument;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.chat.Quelle;
import ch.urinet.archiv.ki.domain.chat.WerkzeugAufruf;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Req 5.3: {@code werkzeugDokumente} liefert nur die Treffer des angefragten Gesprächs, in der Reihenfolge des ersten
 * Auftretens; ein anderes Gespräch (gleicher oder anderer Benutzer) und ein anderer Mandant sehen sie nie, ein neues
 * Gespräch beginnt leer (die Nummerierung bei 1).
 */
@QuarkusTest
class JdbcChatSpeicherWerkzeugDokumenteTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");

    @Inject
    ChatSpeicher speicher;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> urinetGespraeche = new ArrayList<>();
    private final List<UUID> zweiterGespraeche = new ArrayList<>();
    private final List<UUID> entwuerfe = new ArrayList<>();
    private final List<UUID> dokumente = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (UUID id : entwuerfe) {
                for (String tabelle : List.of("wissens_aenderung where entwurf_id", "wissens_entwurf where id")) {
                    try (PreparedStatement befehl = verbindung.prepareStatement(
                            "delete from m_urinet." + tabelle + " = ?")) {
                        befehl.setObject(1, id);
                        befehl.executeUpdate();
                    }
                }
            }
            loeschen(verbindung, URINET, urinetGespraeche);
            loeschen(verbindung, ZWEITER, zweiterGespraeche);
        }
        Testdokumente.loeschen(datenbank.get(), "urinet", dokumente);
    }

    private static void loeschen(Connection verbindung, MandantCode mandant, List<UUID> gespraeche)
            throws SQLException {
        for (UUID id : gespraeche) {
            for (String tabelle : List.of("chat_nachricht where gespraech_id", "chat_gespraech where id")) {
                try (PreparedStatement befehl = verbindung.prepareStatement(
                        "delete from " + mandant.schema() + "." + tabelle + " = ?")) {
                    befehl.setObject(1, id);
                    befehl.executeUpdate();
                }
            }
        }
    }

    @Test
    void gespeicherteAntwortHatImVerlaufDieselbeIdUndIstNurDemEigentuemerSichtbar() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID gespraech = gespraech(URINET, "benutzer-a", urinetGespraeche);
        ChatAntwort antwort = new ChatAntwort("Antwort", List.of(), List.of(), Optional.empty(), ChatStatus.OK);
        long id = im(URINET, () -> speicher.antwortSpeichern(gespraech, antwort, Instant.now()));

        assertThat(id).isPositive();
        assertThat(im(URINET, () -> speicher.gespraech(gespraech, "benutzer-a")).orElseThrow().nachrichten())
                .singleElement().satisfies(n -> assertThat(n.id()).isEqualTo(id));
        assertThat(im(URINET, () -> speicher.gespraech(gespraech, "benutzer-b"))).isEmpty();
        assertThat(im(ZWEITER, () -> speicher.gespraech(gespraech, "benutzer-a"))).isEmpty();
    }

    @Test
    void analyseKontextIstMandantenUndEigentuemergebundenUndNachAufraeumenUnvollstaendig() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID gespraech = gespraech(URINET, "benutzer-a", urinetGespraeche);
        Instant alt = Instant.parse("2000-01-01T00:00:00Z");
        im(URINET, () -> {
            speicher.frageSpeichern(gespraech, "Alte Frage", alt);
            return null;
        });
        UUID dokument = UUID.randomUUID();
        var werkzeug = new WerkzeugAufruf("dokument_zeigen", Map.of(), List.of(dokument), Optional.empty(),
                Optional.of(ChatEreignis.Kontextstufe.INTERN));
        ChatAntwort antwort = new ChatAntwort("Antwort", List.of(werkzeug), List.of(new Quelle.Dokument(dokument, "T")),
                Optional.empty(), ChatStatus.OK, true);
        long id = im(URINET, () -> speicher.antwortSpeichern(gespraech, antwort, Instant.now()));
        var kontext = im(URINET, () -> speicher.analyseDaten(id, "benutzer-a")).orElseThrow();
        assertThat(kontext.vollstaendig()).isTrue();
        assertThat(kontext.werkzeuge()).singleElement().satisfies(w -> {
            assertThat(w.name()).isEqualTo("dokument_zeigen");
            assertThat(w.kontextstufe()).isEqualTo("INTERN");
        });
        assertThat(kontext.quellen()).singleElement().satisfies(q -> assertThat(q.dokumentId()).isEqualTo(dokument));
        assertThat(im(URINET, () -> speicher.gespraech(gespraech, "benutzer-a")).orElseThrow().nachrichten())
                .last().satisfies(n -> assertThat(n.analyseMoeglich()).isTrue());
        assertThat(im(URINET, () -> speicher.analyseDaten(id, "benutzer-b"))).isEmpty();
        assertThat(im(ZWEITER, () -> speicher.analyseDaten(id, "benutzer-a"))).isEmpty();
        im(URINET, () -> speicher.aufraeumen(alt.plusSeconds(1)));
        assertThat(im(URINET, () -> speicher.analyseDaten(id, "benutzer-a")).orElseThrow().vollstaendig()).isFalse();
        assertThat(im(URINET, () -> speicher.gespraech(gespraech, "benutzer-a")).orElseThrow().nachrichten())
                .last().satisfies(n -> assertThat(n.analyseMoeglich()).isFalse());
    }

    @Test
    void loeschenDurchAltenAufraeumerEntwertetDenVerlauf() throws SQLException {
        if (!vorhanden(URINET)) {
            provisionierung.anlegen(URINET.wert(), null, null);
        }
        UUID gespraech = gespraech(URINET, "benutzer-a", urinetGespraeche);
        Instant alt = Instant.parse("2000-01-01T00:00:00Z");
        im(URINET, () -> {
            speicher.frageSpeichern(gespraech, "Alte Frage", alt);
            return null;
        });
        long id = im(URINET, () -> speicher.antwortSpeichern(gespraech,
                new ChatAntwort("Antwort", List.of(), List.of(), Optional.empty(), ChatStatus.OK, true),
                Instant.now()));
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "delete from m_urinet.chat_nachricht where gespraech_id = ? and zeit < ?")) {
            befehl.setObject(1, gespraech);
            befehl.setObject(2, java.sql.Timestamp.from(alt.plusSeconds(1)));
            assertThat(befehl.executeUpdate()).isEqualTo(1);
        }
        assertThat(im(URINET, () -> speicher.analyseDaten(id, "benutzer-a")).orElseThrow().vollstaendig()).isFalse();
    }

    @Test
    void analyseEndpunktPrueftRechteEigentumKontextUndErzeugtEinenEntwurf() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        String mitarbeiter = Testanmeldung.token("mitarbeiter-urinet");
        String inhaber = Testanmeldung.token("inhaber-urinet");
        UUID gespraech = gespraech(URINET, Testanmeldung.sub("mitarbeiter-urinet"), urinetGespraeche);
        UUID dokument = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", null, "NORMAL", true, "probe", null, "ARCHIVIERT"));
        dokumente.add(dokument);
        ChatAntwort erlaubt = new ChatAntwort("Ein synthetischer Satz.", List.of(
                new WerkzeugAufruf("wissen_index", Map.of(), List.of(), Optional.empty(),
                        Optional.of(ChatEreignis.Kontextstufe.INTERN))), List.of(new Quelle.Dokument(dokument, "Probe")),
                Optional.empty(), ChatStatus.OK, true);
        long id = im(URINET, () -> speicher.antwortSpeichern(gespraech, erlaubt, Instant.now()));
        String pfad = "/api/v1/wissensentwuerfe/analyse";
        Map<String, String> anfrage = Map.of("nachrichtId", Long.toString(id));
        given().auth().oauth2(Testanmeldung.token("treuhaender-urinet")).contentType("application/json")
                .body(anfrage).when().post(pfad).then().statusCode(403);
        given().auth().oauth2(inhaber).contentType("application/json")
                .body(anfrage).when().post(pfad).then().statusCode(404);
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).contentType("application/json")
                .body(anfrage).when().post(pfad).then().statusCode(404);
        String entwurf = given().auth().oauth2(mitarbeiter).contentType("application/json")
                .body(anfrage).when().post(pfad).then().statusCode(201).extract().path("id");
        entwuerfe.add(UUID.fromString(entwurf));
        given().auth().oauth2(inhaber).when().get("/api/v1/wissensentwuerfe/" + entwurf).then().statusCode(200)
                .body("entwurf.anlass", is("ANALYSE"))
                .body("aenderungen[0].vertraulichkeitKontext", is("INTERN"))
                .body("aenderungen[0].befunde.code", hasItem("SEITE_FEHLT"))
                .body("aenderungen[0].befunde.code", hasItem("AUSSAGE_OHNE_BELEG"));
        given().auth().oauth2(mitarbeiter).when().get("/api/v1/wissensentwuerfe/" + entwurf).then().statusCode(200)
                .body("status", is("OFFEN")).body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("aenderungen")));
        given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json")
                .body("{}").when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen")
                .then().statusCode(422);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "update m_urinet.dokument set vertraulichkeit = 'PERSONAL' where id = ?")) {
            befehl.setObject(1, dokument);
            assertThat(befehl.executeUpdate()).isEqualTo(1);
        }
        given().auth().oauth2(mitarbeiter).contentType("application/json")
                .body(anfrage).when().post(pfad).then().statusCode(404);
        ChatAntwort verboten = new ChatAntwort("Zahl", List.of(
                new WerkzeugAufruf("kennzahl", Map.of(), List.of(), Optional.empty(),
                        Optional.of(ChatEreignis.Kontextstufe.NORMAL))), List.of(), Optional.empty(), ChatStatus.OK, true);
        long unzulaessig = im(URINET, () -> speicher.antwortSpeichern(gespraech, verboten, Instant.now()));
        given().auth().oauth2(mitarbeiter).contentType("application/json")
                .body(Map.of("nachrichtId", Long.toString(unzulaessig))).when().post(pfad).then().statusCode(422);
        long spaeter = im(URINET, () -> speicher.antwortSpeichern(gespraech,
                new ChatAntwort("Spätere Antwort", List.of(), List.of(), Optional.empty(), ChatStatus.OK, true),
                Instant.now()));
        given().auth().oauth2(mitarbeiter).contentType("application/json")
                .body(Map.of("nachrichtId", Long.toString(spaeter))).when().post(pfad).then().statusCode(422);
        assertThat(im(URINET, () -> speicher.gespraech(gespraech, Testanmeldung.sub("mitarbeiter-urinet")))
                .orElseThrow().nachrichten()).last().satisfies(n -> assertThat(n.analyseMoeglich()).isFalse());
    }

    @Test
    void werkzeugDokumenteLiefernNurDieTrefferDesGespraechs() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID a = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID gleicherBenutzer = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID anderer = gespraech(URINET, "benutzer-b", urinetGespraeche);
        UUID neu = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID fremderMandant = gespraech(ZWEITER, "benutzer-a", zweiterGespraeche);
        UUID eins = UUID.randomUUID();
        UUID zwei = UUID.randomUUID();
        UUID drei = UUID.randomUUID();
        UUID nurAndere = UUID.randomUUID();
        antwort(URINET, a, new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(eins, zwei), Optional.empty()));
        antwort(URINET, a, new WerkzeugAufruf("dokument_zeigen", Map.of(), List.of(zwei), Optional.empty()),
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(drei), Optional.empty()));
        antwort(URINET, gleicherBenutzer,
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(nurAndere), Optional.empty()));
        antwort(URINET, anderer,
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(nurAndere), Optional.empty()));
        antwort(ZWEITER, fremderMandant,
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(nurAndere), Optional.empty()));

        assertThat(im(URINET, () -> speicher.werkzeugDokumente(a))).containsExactly(
                new ChatWerkzeugDokument("dokumente_suchen", eins), new ChatWerkzeugDokument("dokumente_suchen", zwei),
                new ChatWerkzeugDokument("dokument_zeigen", zwei), new ChatWerkzeugDokument("dokumente_suchen", drei));
        assertThat(im(URINET, () -> speicher.werkzeugDokumente(gleicherBenutzer))).extracting(
                ChatWerkzeugDokument::dokumentId).containsExactly(nurAndere);
        assertThat(im(URINET, () -> speicher.werkzeugDokumente(neu))).as("neues Gespräch beginnt leer").isEmpty();
        assertThat(im(URINET, () -> speicher.werkzeugDokumente(fremderMandant)))
                .as("Gespräch eines anderen Mandanten ist im Schema nicht vorhanden").isEmpty();
        assertThat(im(ZWEITER, () -> speicher.werkzeugDokumente(a))).isEmpty();
    }

    @Test
    void antwortenOhneWerkzeugeFragenUndAbgebrocheneDurchgaengeStoerenDieAbfrageNicht() throws SQLException {
        if (!vorhanden(URINET)) {
            provisionierung.anlegen(URINET.wert(), null, null);
        }
        UUID g = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID eins = UUID.randomUUID();
        UUID zwei = UUID.randomUUID();
        im(URINET, () -> {
            speicher.frageSpeichern(g, "Frage ohne Werkzeug", Instant.now());
            return null;
        });
        antwort(URINET, g);
        antwort(URINET, g, new WerkzeugAufruf("kennzahl", Map.of(), List.of(), Optional.empty()));
        im(URINET, () -> {
            speicher.antwortSpeichern(g, new ChatAntwort("", List.of(new WerkzeugAufruf("dokumente_suchen", Map.of(),
                    List.of(eins), Optional.empty())), List.of(), Optional.empty(), ChatStatus.ABGEBROCHEN),
                    Instant.now());
            return null;
        });
        im(URINET, () -> {
            speicher.frageSpeichern(g, "noch eine Frage", Instant.now());
            return null;
        });
        antwort(URINET, g, new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(zwei, eins), Optional.empty()));

        assertThat(im(URINET, () -> speicher.werkzeugDokumente(g))).containsExactly(
                new ChatWerkzeugDokument("dokumente_suchen", eins), new ChatWerkzeugDokument("dokumente_suchen", zwei),
                new ChatWerkzeugDokument("dokumente_suchen", eins));
    }

    private UUID gespraech(MandantCode mandant, String benutzer, List<UUID> merken) {
        UUID id = UUID.randomUUID();
        im(mandant, () -> {
            speicher.gespraechAnlegen(id, benutzer, "Test " + id, Instant.now());
            return null;
        });
        merken.add(id);
        return id;
    }

    private void antwort(MandantCode mandant, UUID gespraech, WerkzeugAufruf... aufrufe) {
        ChatAntwort antwort = new ChatAntwort("Antwort", List.of(aufrufe), List.of(), Optional.empty(), ChatStatus.OK);
        im(mandant, () -> {
            speicher.antwortSpeichern(gespraech, antwort, Instant.now());
            return null;
        });
    }

    private <T> T im(MandantCode code, Supplier<T> arbeit) {
        try {
            return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> QuarkusTransaction.requiringNew()
                    .call(arbeit::get)), worker).get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException laufzeit ? laufzeit : new IllegalStateException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean vorhanden(MandantCode code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
