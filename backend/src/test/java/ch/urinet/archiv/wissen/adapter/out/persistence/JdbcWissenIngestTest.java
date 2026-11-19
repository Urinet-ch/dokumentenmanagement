package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import ch.urinet.archiv.dokument.application.DokumentWiederholen;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.Versionskopf;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.application.FreigabeDienst;
import ch.urinet.archiv.wissen.application.FreigabeAnnahme;
import ch.urinet.archiv.wissen.application.FreigabeKontext;
import ch.urinet.archiv.wissen.application.FreigabeSperre;
import ch.urinet.archiv.wissen.application.ManuellerWissensentwurf;
import ch.urinet.archiv.wissen.application.ManuelleNeuerzeugung;
import ch.urinet.archiv.wissen.application.ManuelleNeuerzeugungSpeichern;
import ch.urinet.archiv.wissen.application.WissenIngestHandler;
import ch.urinet.archiv.wissen.application.DiffDienst;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import ch.urinet.archiv.wissen.application.WissenIngestPort;
import ch.urinet.archiv.wissen.application.WissenIngestStarten;
import ch.urinet.archiv.wissen.application.WissenIngestSpeichern;
import ch.urinet.archiv.wissen.application.WissenIngestVerarbeiten;
import ch.urinet.archiv.wissen.application.WissenKonfiguration;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

/**
 * Lesen und Anlegen für den Ingest-Start (Spec 04, Aufgabe 14.1a) je Mandant: Jeder Mandant sieht nur Dokumente,
 * Entwürfe und KI-Verbrauch seines Schemas (tech.md, Mandantentrennung in jedem Repository-Test).
 */
@QuarkusTest
class JdbcWissenIngestTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");
    /** Ein Monat weit vor allen echten Testläufen, damit fremder Verbrauch die Summe nicht stört. */
    private static final Instant VON = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant BIS = Instant.parse("2001-02-01T00:00:00Z");

    @Inject
    JdbcWissenIngest ingest;

    @Inject
    PanacheWissenHuelleRepository huellen;

    @Inject
    FreigabeDienst freigabe;

    @Inject
    FreigabeAnnahme freigabeAnnahme;

    @Inject
    FreigabeSperre freigabeSperre;

    @Inject
    JdbcFreigabeRepository freigabeRepository;

    @Inject
    FreigabeKontext freigabeKontext;

    @Inject
    DiffDienst diffDienst;

    @Inject
    WissenIngestSpeichern ingestSpeichern;

    @Inject
    ManuellerWissensentwurf manuellerEntwurfDienst;

    @Inject
    ManuelleNeuerzeugungSpeichern manuellSpeichern;

    @Inject
    WissenKonfiguration wissenKonfiguration;

    @Inject
    KiVerbrauchPort kiVerbrauch;

    @Inject
    ObjectMapper json;

    @Inject
    DokumentEreignisse ereignisse;

    @Inject
    DokumentWiederholen wiederholen;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    Instance<EntityManager> entityManager;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<MandantCode, List<UUID>> dokumente = new HashMap<>();
    private final Map<MandantCode, List<UUID>> entwuerfe = new HashMap<>();
    private final Map<MandantCode, List<Long>> verbrauch = new HashMap<>();
    private final Map<MandantCode, Monatsbudget> monatsbudgets = new HashMap<>();
    private final Map<MandantCode, List<UUID>> seiten = new HashMap<>();

    @Test
    void quellenAenderungBleibtTransaktionalUndImMandanten() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID fremdesDokument = quelle(ZWEITER);
        UUID seite = seite(URINET, "probe/quelle-" + dokument, "Unveränderter Inhalt", null);
        UUID fremdeSeite = seite(ZWEITER, "probe/quelle-" + fremdesDokument, "Fremder Inhalt", null);
        for (var eintrag : Map.of(URINET, Map.entry(seite, dokument),
                ZWEITER, Map.entry(fremdeSeite, fremdesDokument)).entrySet()) {
            String schema = eintrag.getKey().schema();
            UUID seitenId = eintrag.getValue().getKey();
            UUID dokumentId = eintrag.getValue().getValue();
            ausfuehren("insert into " + schema + ".wissensseite_quelle "
                    + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", seitenId, dokumentId);
            ausfuehren("insert into " + schema + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                    seitenId, dokumentId);
        }

        assertThatThrownBy(() -> im(URINET, () -> {
            ereignisse.vorAenderung();
            entityManager.get().createNativeQuery("update dokument set vertraulichkeit = 'INTERN' where id = :id")
                    .setParameter("id", dokument).executeUpdate();
            ereignisse.geaendert(dokument, Set.of("vertraulichkeit"));
            throw new IllegalStateException("Rollback-Probe");
        })).hasMessage("Rollback-Probe");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissensseite where id = ? and not quelle_pruefen and vertraulichkeit = 'NORMAL'", seite))
                .isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".dokument where id = ? and vertraulichkeit = 'NORMAL'", dokument)).isEqualTo(1);

        im(URINET, () -> {
            ereignisse.vorAenderung();
            entityManager.get().createNativeQuery("update dokument set vertraulichkeit = 'INTERN' where id = :id")
                    .setParameter("id", dokument).executeUpdate();
            ereignisse.geaendert(dokument, Set.of("vertraulichkeit"));
            return null;
        });
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissensseite where id = ? and quelle_pruefen and vertraulichkeit = 'INTERN'"
                + " and inhalt = 'Unveränderter Inhalt'", seite)).isEqualTo(1);
        assertThat(anzahl("select count(*) from " + ZWEITER.schema()
                + ".wissensseite where id = ? and not quelle_pruefen and vertraulichkeit = 'NORMAL'", fremdeSeite))
                .isEqualTo(1);
    }

    @Test
    void vertraulichkeitPerDokumentApiAendernEntziehtMitarbeiterDieSeite() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID seite = seite(URINET, "probe/quelle-" + dokument, "Unveränderter Inhalt", null);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", seite, dokument);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                seite, dokument);
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String mitarbeiter = Testanmeldung.token("mitarbeiter-urinet");
        String dokumentPfad = "/api/v1/dokumente/" + dokument;
        String seitenPfad = "/api/v1/wissensseiten/" + seite;

        given().auth().oauth2(mitarbeiter).when().get(seitenPfad).then().statusCode(200);
        String etag = given().auth().oauth2(inhaber).when().get(dokumentPfad).then().statusCode(200)
                .extract().header("ETag");
        given().auth().oauth2(inhaber).header("If-Match", etag).contentType("application/json")
                .body(Map.of("vertraulichkeit", "INTERN", "grund", "Synthetische Quellenprüfung"))
                .when().patch(dokumentPfad).then().statusCode(200);

        given().auth().oauth2(mitarbeiter).when().get(seitenPfad).then().statusCode(404);
        given().auth().oauth2(inhaber).when().get(seitenPfad).then().statusCode(200)
                .body("quellePruefen", org.hamcrest.Matchers.is(true));
    }

    @Test
    void quellenpruefungSchuetztGegenSpaetereAenderungUndProtokolliertBestaetigung() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID seite = seite(URINET, "probe/quelle-" + dokument, "Unveränderter Inhalt", null);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", seite, dokument);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                seite, dokument);
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String mitarbeiter = Testanmeldung.token("mitarbeiter-urinet");
        String pfad = "/api/v1/wissensseiten/" + seite;
        im(URINET, () -> {
            ereignisse.vorAenderung();
            ereignisse.geaendert(dokument, Set.of("titel"));
            return null;
        });
        String alterStand = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .body("quellePruefen", org.hamcrest.Matchers.is(true)).extract().header("ETag");
        given().auth().oauth2(mitarbeiter).header("If-Match", alterStand)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(403);
        given().auth().oauth2(Testanmeldung.token("treuhaender-urinet")).header("If-Match", alterStand)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(403);
        given().auth().oauth2(inhaber).when().post(pfad + "/quelle-geprueft").then().statusCode(428);

        im(URINET, () -> {
            ereignisse.vorAenderung();
            ereignisse.geaendert(dokument, Set.of("titel"));
            return null;
        });
        given().auth().oauth2(inhaber).header("If-Match", alterStand)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(412);
        String aktuellerStand = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .body("quellePruefen", org.hamcrest.Matchers.is(true)).extract().header("ETag");
        assertThat(aktuellerStand).isNotEqualTo(alterStand);
        given().auth().oauth2(inhaber).header("If-Match", aktuellerStand)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(204);
        given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .body("quellePruefen", org.hamcrest.Matchers.is(false)).body("version", org.hamcrest.Matchers.is(1));
        given().auth().oauth2(inhaber).header("If-Match", aktuellerStand)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(412);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_log where aktion = 'UPDATE' and ? = any(seiten)"
                + " and angaben @> '{\"quelle_geprueft\":true}'::jsonb", seite)).isEqualTo(1);
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).header("If-Match", aktuellerStand)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(404);
    }

    @Test
    void dokumentAbrufLiefertNurSichtbareVerwendendeSeiten() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID sichtbar = seite(URINET, "probe/offen-" + dokument, "Inhalt", null);
        UUID intern = seite(URINET, "probe/intern-" + dokument, "Inhalt", null);
        ausfuehren("update " + URINET.schema() + ".wissensseite set vertraulichkeit = 'INTERN', "
                + "vertraulichkeit_mindest = 'INTERN' where id = ?", intern);
        for (UUID id : List.of(sichtbar, intern)) {
            ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                    + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", id, dokument);
            ausfuehren("insert into " + URINET.schema() + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                    id, dokument);
        }
        String pfad = "/api/v1/dokumente/" + dokument;
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when().get(pfad).then().statusCode(200)
                .body("wissensseiten.id", org.hamcrest.Matchers.containsInAnyOrder(sichtbar.toString(), intern.toString()));
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet")).when().get(pfad).then().statusCode(200)
                .body("wissensseiten.id", org.hamcrest.Matchers.contains(sichtbar.toString()));
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).when().get(pfad).then().statusCode(404);
    }

    @Test
    void erneuteArchivierungMarkiertVerwendendeSeite() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID seite = seite(URINET, "probe/quelle-" + dokument, "Unveränderter Inhalt", null);
        ausfuehren("update " + URINET.schema() + ".dokument set status = 'ARCHIVIERUNG_FEHLER' where id = ?",
                dokument);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", seite, dokument);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                seite, dokument);

        assertThatThrownBy(() -> im(URINET, () -> {
            wiederholen.wiederholen(new Benutzer("probe", Rolle.INHABER), dokument, 0);
            long anzahl = ((Number) entityManager.get().createNativeQuery(
                            "select count(*) from wissensseite where id = :id and quelle_pruefen")
                    .setParameter("id", seite).getSingleResult()).longValue();
            assertThat(anzahl).isEqualTo(1);
            throw new IllegalStateException("Rollback-Probe");
        })).hasMessage("Rollback-Probe");
    }

    @Test
    void paralleleQuellenAenderungenWartenAufDieMandantensperre() throws Exception {
        UUID dokument = quelle(URINET);
        UUID seite = seite(URINET, "probe/quelle-" + dokument, "Unveränderter Inhalt", null);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", seite, dokument);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                seite, dokument);
        CountDownLatch gesperrt = new CountDownLatch(1);
        CountDownLatch fortfahren = new CountDownLatch(1);
        CountDownLatch zweiterGestartet = new CountDownLatch(1);
        ExecutorService parallel = Executors.newFixedThreadPool(2);
        try {
            var erster = parallel.submit(() -> kontext.mit(URINET, () -> QuarkusTransaction.requiringNew().call(() -> {
                ereignisse.vorAenderung();
                gesperrt.countDown();
                if (!fortfahren.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Zweite Transaktion nicht gestartet");
                }
                entityManager.get().createNativeQuery("update dokument set vertraulichkeit = 'INTERN' where id = :id")
                        .setParameter("id", dokument).executeUpdate();
                ereignisse.geaendert(dokument, Set.of("vertraulichkeit"));
                return null;
            })));
            assertThat(gesperrt.await(10, TimeUnit.SECONDS)).isTrue();
            var zweiter = parallel.submit(() -> kontext.mit(URINET, () -> QuarkusTransaction.requiringNew().call(() -> {
                zweiterGestartet.countDown();
                ereignisse.vorAenderung();
                entityManager.get().createNativeQuery("update dokument set vertraulichkeit = 'PERSONAL' where id = :id")
                        .setParameter("id", dokument).executeUpdate();
                ereignisse.geaendert(dokument, Set.of("vertraulichkeit"));
                return null;
            })));
            assertThat(zweiterGestartet.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> zweiter.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            fortfahren.countDown();
            erster.get(10, TimeUnit.SECONDS);
            zweiter.get(10, TimeUnit.SECONDS);
        } finally {
            fortfahren.countDown();
            parallel.shutdown();
            if (!parallel.awaitTermination(15, TimeUnit.SECONDS)) {
                parallel.shutdownNow();
                throw new IllegalStateException("Parallele Testtransaktionen sind nicht beendet");
            }
        }
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".dokument where id = ? and vertraulichkeit = 'PERSONAL'", dokument)).isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissensseite where id = ? and quelle_pruefen and vertraulichkeit = 'INTERN'", seite)).isEqualTo(1);
    }

    @Test
    void kontaktZusammenfuehrenMarkiertQuellseite() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID seite = seite(URINET, "probe/quelle-" + dokument, "Unveränderter Inhalt", null);
        UUID quelle = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        try {
            for (UUID kontakt : List.of(quelle, ziel)) {
                ausfuehren("insert into " + URINET.schema() + ".kontakt "
                                + "(id, typ, name, name_normalisiert, quelle) "
                                + "values (?, 'ORGANISATION', 'Probe', 'probe', 'MANUELL')", kontakt);
            }
            ausfuehren("update " + URINET.schema() + ".dokument set partner_id = ? where id = ?", quelle, dokument);
            ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                    + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", seite, dokument);
            ausfuehren("insert into " + URINET.schema() + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                    seite, dokument);
            String inhaber = Testanmeldung.token("inhaber-urinet");
            String pfad = "/api/v1/kontakte/" + quelle;
            String etag = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                    .extract().header("ETag");
            given().auth().oauth2(inhaber).header("If-Match", etag).contentType("application/json")
                    .body(Map.of("ziel", ziel.toString(), "grund", "Synthetische Zusammenführung"))
                    .when().post(pfad + "/zusammenfuehren").then().statusCode(200);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite where id = ? and quelle_pruefen", seite)).isEqualTo(1);
        } finally {
            ausfuehren("update " + URINET.schema() + ".dokument set partner_id = null where id = ?", dokument);
            ausfuehren("delete from " + URINET.schema() + ".kontakt where id = ? or id = ?", quelle, ziel);
        }
    }

    @BeforeEach
    void mandanten() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
            dokumente.put(code, new ArrayList<>());
            entwuerfe.put(code, new ArrayList<>());
            verbrauch.put(code, new ArrayList<>());
            seiten.put(code, new ArrayList<>());
            monatsbudgets.put(code, monatsbudget(code));
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            for (UUID id : entwuerfe.get(code)) {
                ausfuehren("delete from " + code.schema() + ".wissens_aenderung where entwurf_id = ?", id);
                ausfuehren("delete from " + code.schema() + ".wissens_entwurf where id = ?", id);
            }
            for (UUID id : seiten.get(code)) {
                ausfuehren("delete from " + code.schema() + ".wissensseite where id = ?", id);
            }
            for (Long id : verbrauch.get(code)) {
                ausfuehren("delete from " + code.schema() + ".ki_verbrauch where id = ?", id);
            }
            Monatsbudget vorher = monatsbudgets.get(code);
            if (vorher.vorhanden()) {
                ausfuehren("update " + code.schema() + ".wissens_einstellung set monatsbudget_tokens = ? where id = 1",
                        vorher.wert());
            } else {
                ausfuehren("delete from " + code.schema() + ".wissens_einstellung where id = 1");
            }
            Testdokumente.loeschen(datenbank.get(), code.wert(), dokumente.get(code));
        }
    }

    @Test
    void quelleNurImEigenenSchema() throws SQLException {
        UUID id = quelle(ZWEITER);

        assertThat(im(URINET, () -> ingest.quelleLaden(id))).isEmpty();
        WissenIngestPort.Quelle quelle = im(ZWEITER, () -> ingest.quelleLaden(id)).orElseThrow();
        assertThat(quelle.art()).isEqualTo("WISSENSQUELLE");
        assertThat(quelle.status()).isEqualTo("ARCHIVIERT");
        assertThat(quelle.seiten()).isEqualTo(2);
        assertThat(quelle.pdfaVorhanden()).isTrue();
    }

    @Test
    void graphLaedtNeueDokumentquellenNurAusDemEigenenMandanten() throws SQLException {
        UUID normal = quelle(URINET);
        UUID intern = quelle(URINET);
        UUID fremd = quelle(ZWEITER);
        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'INTERN' where id = ?", intern);

        var graph = im(URINET, () -> huellen.graphLaden(Set.of(normal, intern, fremd)));
        assertThat(graph.dokumentStufen()).containsEntry(normal, WissensStufe.NORMAL)
                .containsEntry(intern, WissensStufe.INTERN).doesNotContainKey(fremd);
        assertThat(im(ZWEITER, () -> huellen.graphLaden(Set.of(normal, fremd))).dokumentStufen())
                .containsEntry(fremd, WissensStufe.NORMAL).doesNotContainKey(normal);
    }

    @Test
    void verwerfenPrueftRechteUndVersionUndSchliesstNurNachLetzterEntscheidung() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        aenderung(URINET, entwurf, 2);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        Benutzer treuhaender = new Benutzer("treuhaender", Rolle.TREUHAENDER);

        assertThatThrownBy(() -> im(ZWEITER, () -> {
            freigabe.verwerfen(inhaber, entwurf, 1, () -> 0, "Grund", null);
            return null;
        })).isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> im(URINET, () -> {
            freigabe.verwerfen(treuhaender, entwurf, 1, () -> Versionskopf.version(null), "Grund", null);
            return null;
        })).isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> im(URINET, () -> {
            freigabe.verwerfen(inhaber, entwurf, 1, () -> Versionskopf.version(null), "Grund", null);
            return null;
        })).isInstanceOfSatisfying(FachFehler.class,
                fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.VERSION_FEHLT));

        im(URINET, () -> {
            freigabe.verwerfen(inhaber, entwurf, 1, () -> 0, "Grund", null);
            return null;
        });
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'VERWORFEN' and etag = 1", entwurf))
                .isEqualTo(1);
        assertThatThrownBy(() -> im(URINET, () -> {
            freigabe.verwerfen(inhaber, entwurf, 1, () -> 0, "Grund", null);
            return null;
        })).isInstanceOfSatisfying(FachFehler.class,
                fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.VERSION_KONFLIKT));

        im(URINET, () -> {
            freigabe.verwerfen(inhaber, entwurf, 2, () -> 0, "Grund", null);
            return null;
        });
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "ERLEDIGT");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".zugriff_protokoll where dokument_id = ? and aktion = 'WISSEN_VERWERFEN' "
                + "and details->>'grund' = 'Grund'", entwurf)).isEqualTo(2);
    }

    @Test
    void neuErzeugenSperrtNurVeralteteAenderungenMitAktuellemEtag() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        Benutzer treuhaender = new Benutzer("treuhaender", Rolle.TREUHAENDER);

        assertThatThrownBy(() -> im(ZWEITER,
                () -> freigabeSperre.zumNeuErzeugenLaden(inhaber, entwurf, 1, () -> 0)))
                .isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> im(URINET,
                () -> freigabeSperre.zumNeuErzeugenLaden(treuhaender, entwurf, 1, () -> 0)))
                .isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> im(URINET,
                () -> freigabeSperre.zumNeuErzeugenLaden(inhaber, entwurf, 1, () -> 0)))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.STATUS_KONFLIKT));
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', etag = 1 where entwurf_id = ?", entwurf);
        assertThatThrownBy(() -> im(URINET,
                () -> freigabeSperre.zumNeuErzeugenLaden(inhaber, entwurf, 1, () -> 0)))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.VERSION_KONFLIKT));
        assertThat(im(URINET, () -> freigabeSperre.zumNeuErzeugenLaden(inhaber, entwurf, 1, () -> 1)).status())
                .isEqualTo("VERALTET");
    }

    @Test
    void erneuerteAenderungWirdAtomarUndNurImEigenenMandantenGespeichert() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        UUID seiteId = seite(URINET, "konzepte/neu-" + UUID.randomUUID(), "Probe", null);
        aenderung(URINET, entwurf, 1);
        String pfad = im(URINET, () -> ingest.seiteLaden(seiteId).orElseThrow().metadaten().pfad());
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', aktion = 'AENDERN', seite_id = ?, "
                + "pfad = ?, basis_version = 1, etag = 1, vertraulichkeit_kontext = 'INTERN' "
                + "where entwurf_id = ?", seiteId, pfad, entwurf);
        ausfuehren("update " + URINET.schema()
                + ".wissens_entwurf set status = 'IN_ARBEIT', schritt = 'NEU_ERZEUGEN' where id = ?", entwurf);
        Wissensseite fassung = new Wissensseite(seiteId, pfad, Seitentyp.KONZEPT, "Neu", "Beschreibung",
                List.of(), Wissensseite.Status.AKTIV, null, List.of(), Wissensseite.Vertraulichkeit.NORMAL,
                "Neuer Inhalt", 2, null, null, null, null, false, false, null);
        var erneuert = new WissenIngestPort.NeueAenderung(entwurf, 1, "AENDERN", seiteId, pfad, 2, fassung,
                "Grund", List.of(), List.of(), List.of(), List.of(), diffDienst.vergleichen(null, fassung), "NORMAL");
        var fortschritt = WissenIngestPort.Fortschritt.leer();

        assertThatThrownBy(() -> im(ZWEITER, () -> {
            ingest.erneuerteAenderungSpeichern(erneuert, fortschritt, 10, 5);
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> im(URINET, () -> {
            ingest.erneuerteAenderungSpeichern(erneuert, fortschritt, 10, 5);
            throw new IllegalStateException("Testabbruch");
        })).hasMessage("Testabbruch");
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "IN_ARBEIT");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 1", entwurf))
                .isEqualTo(1);
        im(URINET, () -> {
            ingest.erneuerteAenderungSpeichern(erneuert, fortschritt, 10, 5);
            return null;
        });
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'OFFEN' and etag = 2 "
                + "and basis_version = 2 and inhalt = 'Neuer Inhalt' and vertraulichkeit_kontext = 'INTERN'",
                entwurf)).isEqualTo(1);
        assertThatThrownBy(() -> im(URINET, () -> {
            ingest.erneuerteAenderungSpeichern(erneuert, fortschritt, 10, 5);
            return null;
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neuErzeugenReihtGenauEinenAuftragAtomarMitDerSperreEin() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = entwurf(URINET, dokument, "OFFEN");
        UUID seiteId = seite(URINET, "konzepte/auftrag-" + UUID.randomUUID(), "Probe", null);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', aktion = 'AENDERN', seite_id = ?, "
                + "basis_version = 1, etag = 1 where entwurf_id = ?", seiteId, entwurf);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        assertThatThrownBy(() -> im(URINET, () -> {
            freigabe.neuErzeugen(inhaber, entwurf, 1, () -> 1);
            EntityManager em = entityManager.get();
            assertThat(((Number) em.createNativeQuery("""
                    select count(*) from public.auftrag where typ = 'WISSEN_INGEST'
                        and nutzlast->>'entwurfId' = :id and nutzlast->>'aenderungNr' = '1'
                    """).setParameter("id", entwurf.toString()).getSingleResult()).intValue()).isEqualTo(1);
            throw new IllegalStateException("Testabbruch");
        })).hasMessage("Testabbruch");
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
        assertThat(anzahl("select count(*) from public.auftrag where typ = 'WISSEN_INGEST' "
                + "and nutzlast->>'entwurfId' = ?", entwurf.toString())).isZero();
    }

    @Test
    void neuErzeugenEndpunktPrueftRechteMandantVersionUndDoppelstart() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = entwurf(URINET, dokument, "OFFEN");
        UUID seiteId = seite(URINET, "konzepte/erneuern-" + UUID.randomUUID(), "Probe", null);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', aktion = 'AENDERN', seite_id = ?, "
                + "basis_version = 1, etag = 1 where entwurf_id = ?", seiteId, entwurf);
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/neu-erzeugen";
        String inhaber = Testanmeldung.token("inhaber-urinet");
        try {
            given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).header("If-Match", "\"1\"")
                    .when().post(pfad).then().statusCode(404);
            given().auth().oauth2(Testanmeldung.token("treuhaender-urinet")).header("If-Match", "\"1\"")
                    .when().post(pfad).then().statusCode(403);
            given().auth().oauth2(inhaber).when().post(pfad).then().statusCode(428);
            given().auth().oauth2(inhaber).header("If-Match", "\"0\"").when().post(pfad).then().statusCode(412);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            given().auth().oauth2(inhaber).header("If-Match", "\"1\"").when().post(pfad).then().statusCode(202);
            given().auth().oauth2(inhaber).header("If-Match", "\"1\"").when().post(pfad).then().statusCode(409);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "IN_ARBEIT");
            assertThat(anzahl("select count(*) from public.auftrag where typ = 'WISSEN_INGEST' "
                    + "and nutzlast->>'entwurfId' = ? and nutzlast->>'aenderungNr' = '1'", entwurf.toString()))
                    .isEqualTo(1);
        } finally {
            ausfuehren("delete from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = ?",
                    entwurf.toString());
        }
    }

    @Test
    void neuErzeugenBrichtBeiZwischenzeitlicherSeitenaenderungOhneUeberschreibenAb() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = entwurf(URINET, dokument, "OFFEN");
        UUID seiteId = seite(URINET, "konzepte/geaendert-" + UUID.randomUUID(), "Probe", null);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', aktion = 'AENDERN', seite_id = ?, "
                + "basis_version = 1, etag = 1 where entwurf_id = ?", seiteId, entwurf);
        long auftragId = im(URINET, () -> {
            freigabe.neuErzeugen(new Benutzer("inhaber", Rolle.INHABER), entwurf, 1, () -> 1);
            EntityManager em = entityManager.get();
            long id = ((Number) em.createNativeQuery("""
                    select id from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = :id
                    """).setParameter("id", entwurf.toString()).getSingleResult()).longValue();
            em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                    .setParameter("id", id).executeUpdate();
            return id;
        });
        Auftrag auftrag = new Auftrag(auftragId, URINET, WissenIngestStarten.TYP,
                new Nutzlast(Map.of("entwurfId", entwurf, "aenderungNr", 1)), 0);
        try {
            im(URINET, () -> {
                ingestSpeichern.neuerzeugungSichern(auftrag, entwurf, seiteId, 2, WissensStufe.NORMAL,
                        WissenIngestPort.Fortschritt.leer(), 10, 5, stand -> {
                            throw new AssertionError("Eine veraltete Basis darf nicht gespeichert werden");
                        });
                return null;
            });
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 1", entwurf))
                    .isEqualTo(1);
        } finally {
            ausfuehren("delete from public.auftrag where id = ?", auftragId);
        }
    }

    @Test
    void bisherigerVorschlagDarfKeineUnzulaessigenQuellenAnDieKiGeben() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID zitiert = quelle(URINET);
        UUID quellseite = seite(URINET, "quellen/ki-" + dokument, "Probe", dokument);
        ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle "
                + "(seite_id, schluessel, dokument_id, seite_von) values (?, 'q1', ?, 1)", quellseite, zitiert);
        Wissensseite vorschlag = new Wissensseite(null, "konzepte/probe", Seitentyp.KONZEPT, "Probe", "Probe",
                List.of(), Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag("q1", dokument, 1, 1, "Seite 1", null),
                        new Quelleneintrag("q2", null, null, null, null, quellseite)),
                Wissensseite.Vertraulichkeit.NORMAL, "Probe.", 1, null, null, null, null, false, false, null);
        assertThat(im(URINET, () -> ingestSpeichern.vorschlagFuerKiZulaessig(vorschlag, WissensStufe.NORMAL)))
                .isTrue();
        assertThat(im(ZWEITER, () -> ingestSpeichern.vorschlagFuerKiZulaessig(vorschlag, WissensStufe.INTERN)))
                .isFalse();
        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'INTERN' where id = ?", dokument);
        assertThat(im(URINET, () -> ingestSpeichern.vorschlagFuerKiZulaessig(vorschlag, WissensStufe.NORMAL)))
                .isFalse();
        assertThat(im(URINET, () -> ingestSpeichern.vorschlagFuerKiZulaessig(vorschlag, WissensStufe.INTERN)))
                .isTrue();
        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?", zitiert);
        assertThat(im(URINET, () -> ingestSpeichern.vorschlagFuerKiZulaessig(vorschlag, WissensStufe.INTERN)))
                .isFalse();
    }

    @Test
    void neuErzeugenVerarbeitetAuftragMitAktuellerBasisUndSpeichertDasErgebnis() throws Exception {
        UUID dokument = quelle(URINET);
        UUID entwurf = entwurf(URINET, dokument, "OFFEN");
        String pfad = "konzepte/worker-" + UUID.randomUUID();
        UUID seiteId = seite(URINET, pfad, "# Aktuelle Fassung", null);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema() + ".wissensseite set version = 2 where id = ?", seiteId);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', aktion = 'AENDERN', seite_id = ?, "
                + "pfad = ?, basis_version = 1, etag = 1 where entwurf_id = ?", seiteId, pfad, entwurf);
        Quelleneintrag quelle = new Quelleneintrag("q-neu", dokument, 1, 1, "Seite 1", null);
        Wissensseite vorschlag = new Wissensseite(seiteId, pfad, Seitentyp.KONZEPT, "Probe", "Probe", List.of(),
                Wissensseite.Status.AKTIV, null, List.of(quelle), Wissensseite.Vertraulichkeit.NORMAL,
                "# Alter Vorschlag\n\nAussage. [^q-neu]", 1, null, null, null, null, false, false, null);
        var plan = new WissenIngestPort.Planung(pfad, "AENDERN", "Konzept", "Probe", List.of(1), seiteId, 1, false);
        var vorschlagDaten = new WissenIngestPort.SeitenVorschlag(1, "AENDERN", seiteId, pfad, 1, vorschlag,
                "Probe", List.of(), List.of(), List.of(), List.of(), "NORMAL", diffDienst.vergleichen(null, vorschlag));
        var fortschritt = new WissenIngestPort.Fortschritt(1, 1, false,
                List.of(new WissenIngestPort.Kernaussage(1, "q-neu", "Aussage.", List.of(1), "Seite 1", true)),
                List.of(plan), List.of(vorschlagDaten), List.of(), WissensStufe.NORMAL);
        ausfuehren("update " + URINET.schema()
                + ".wissens_entwurf set ki_modell = 'test-modell', ingest_daten = cast(? as jsonb) where id = ?",
                json.writeValueAsString(fortschritt), entwurf);
        long auftragId = im(URINET, () -> {
            freigabe.neuErzeugen(new Benutzer("inhaber", Rolle.INHABER), entwurf, 1, () -> 1);
            EntityManager em = entityManager.get();
            long id = ((Number) em.createNativeQuery("""
                    select id from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = :id
                    """).setParameter("id", entwurf.toString()).getSingleResult()).longValue();
            em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                    .setParameter("id", id).executeUpdate();
            return id;
        });
        Auftrag auftrag = new Auftrag(auftragId, URINET, WissenIngestStarten.TYP,
                new Nutzlast(Map.of("entwurfId", entwurf, "aenderungNr", 1)), 0);
        AtomicReference<KiPort.WissensAnfrage> anfrage = new AtomicReference<>();
        AtomicBoolean quelleWechselt = new AtomicBoolean();
        KiPort ki = new KiPort() {
            @Override
            public Antwort metadaten(Anfrage eingabe) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WissensAntwort wissensIngest(WissensAnfrage eingabe) {
                anfrage.set(eingabe);
                if (quelleWechselt.get()) {
                    try {
                        ausfuehren("update " + URINET.schema()
                                + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?", dokument);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return new WissensGueltig(new Seite("Probe", "Probe", List.of(),
                        "# Neuer Vorschlag\n\nAussage. [^q-neu]", List.of(), List.of(), "Probe"),
                        new Verbrauch("test-modell", 12, 8));
            }

            @Override
            public String antwortschema() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> artCodes() {
                throw new UnsupportedOperationException();
            }
        };
        try {
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> {
                try {
                    new WissenIngestVerarbeiten(ingestSpeichern, wissenKonfiguration, ki, kiVerbrauch, diffDienst)
                            .ausfuehren(auftrag, entwurf);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
            assertThat(anfrage.get().schritt()).isEqualTo(KiPort.WissensSchritt.SEITE);
            assertThat(anfrage.get().nachricht()).contains("# Aktuelle Fassung", "# Alter Vorschlag");
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ? and status = 'OFFEN' and etag = 2 "
                    + "and basis_version = 2 and inhalt like '%Neuer Vorschlag%'", entwurf)).isEqualTo(1);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".ki_verbrauch where ingest_entwurf_id = ? and tokens_ein = 12 and tokens_aus = 8", entwurf))
                    .isEqualTo(1);

            ausfuehren("update " + URINET.schema() + ".wissensseite set version = 3 where id = ?", seiteId);
            ausfuehren("update " + URINET.schema()
                    + ".wissens_aenderung set status = 'VERALTET', etag = 3 where entwurf_id = ?", entwurf);
            long zweiterAuftrag = im(URINET, () -> {
                freigabe.neuErzeugen(new Benutzer("inhaber", Rolle.INHABER), entwurf, 1, () -> 3);
                EntityManager em = entityManager.get();
                long id = ((Number) em.createNativeQuery("""
                        select max(id) from public.auftrag where typ = 'WISSEN_INGEST'
                            and nutzlast->>'entwurfId' = :id
                        """).setParameter("id", entwurf.toString()).getSingleResult()).longValue();
                em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                        .setParameter("id", id).executeUpdate();
                return id;
            });
            quelleWechselt.set(true);
            Auftrag zweiter = new Auftrag(zweiterAuftrag, URINET, WissenIngestStarten.TYP,
                    new Nutzlast(Map.of("entwurfId", entwurf, "aenderungNr", 1)), 0);
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> {
                try {
                    new WissenIngestVerarbeiten(ingestSpeichern, wissenKonfiguration, ki, kiVerbrauch, diffDienst)
                            .ausfuehren(zweiter, entwurf);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 3 "
                    + "and basis_version = 2 and inhalt like '%Neuer Vorschlag%'", entwurf)).isEqualTo(1);
        } finally {
            ausfuehren("delete from " + URINET.schema() + ".ki_verbrauch where ingest_entwurf_id = ?", entwurf);
            ausfuehren("delete from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = ?",
                    entwurf.toString());
        }
    }

    @Test
    void manuellerEntwurfWirdMitKiNeuErzeugtUndAlsKiFassungFreigegeben() throws Exception {
        UUID dokument = quelle(URINET);
        String pfad = "konzepte/manuell-" + UUID.randomUUID();
        UUID seiteId = seite(URINET, pfad, "# Bisher", null);
        Quelleneintrag quelle = new Quelleneintrag("q1", dokument, 1, 1, "Seite 1", null);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        UUID entwurf = im(URINET, () -> manuellerEntwurfDienst.anlegen(inhaber,
                new ManuellerWissensentwurf.Eingabe(seiteId, pfad, "Konzept", "Probe", "Probe", List.of(), null,
                        List.of(quelle), "# Manuell\n\nAussage. [^q1]", "Probe", List.of())));
        entwuerfe.get(URINET).add(entwurf);
        ausfuehren("update " + URINET.schema() + ".wissensseite set version = 2, inhalt = '# Aktuell' where id = ?",
                seiteId);
        assertThat(im(URINET, () -> freigabeSperre.basisAktuell(
                freigabeSperre.laden(inhaber, entwurf, 1, () -> 0)))).isFalse();
        long auftragId = im(URINET, () -> {
            freigabe.neuErzeugen(inhaber, entwurf, 1, () -> 1);
            EntityManager em = entityManager.get();
            long id = ((Number) em.createNativeQuery("""
                    select id from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = :id
                    """).setParameter("id", entwurf.toString()).getSingleResult()).longValue();
            em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                    .setParameter("id", id).executeUpdate();
            return id;
        });
        Auftrag auftrag = new Auftrag(auftragId, URINET, WissenIngestStarten.TYP,
                new Nutzlast(Map.of("entwurfId", entwurf, "aenderungNr", 1)), 0);
        AtomicReference<KiPort.WissensAnfrage> anfrage = new AtomicReference<>();
        AtomicBoolean quelleWechselt = new AtomicBoolean(true);
        KiPort testKi = new KiPort() {
            @Override
            public Antwort metadaten(Anfrage eingabe) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WissensAntwort wissensIngest(WissensAnfrage eingabe) {
                anfrage.set(eingabe);
                if (quelleWechselt.get()) {
                    try {
                        ausfuehren("update " + URINET.schema()
                                + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?", dokument);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return new WissensGueltig(new Seite("Probe", "Probe", List.of(),
                        "# Neuer Vorschlag\n\nAussage. [^q1]", List.of(), List.of(), "Probe"),
                        new Verbrauch("test-modell", 12, 8));
            }

            @Override
            public String antwortschema() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> artCodes() {
                throw new UnsupportedOperationException();
            }
        };
        try {
            var verarbeiten = new WissenIngestVerarbeiten(ingestSpeichern, wissenKonfiguration, testKi, kiVerbrauch,
                    diffDienst);
            var manuell = new ManuelleNeuerzeugung(manuellSpeichern, verarbeiten, wissenKonfiguration);
            var handler = new WissenIngestHandler(verarbeiten, ingestSpeichern, manuell, manuellSpeichern);
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> {
                try {
                    handler.ausfuehren(auftrag);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
            assertThat(anfrage.get().schritt()).isEqualTo(KiPort.WissensSchritt.SEITE);
            assertThat(anfrage.get().nachricht()).contains("# Aktuell", "# Manuell");
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 1 "
                    + "and basis_version = 1 and inhalt like '%Manuell%'", entwurf)).isEqualTo(1);

            ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'NORMAL' where id = ?",
                    dokument);
            ausfuehren("update public.auftrag set status = 'FEHLER' where id = ?", auftragId);
            long neuerAuftragId = im(URINET, () -> {
                freigabe.neuErzeugen(inhaber, entwurf, 1, () -> 1);
                EntityManager em = entityManager.get();
                long id = ((Number) em.createNativeQuery("""
                        select max(id) from public.auftrag where typ = 'WISSEN_INGEST'
                            and nutzlast->>'entwurfId' = :id
                        """).setParameter("id", entwurf.toString()).getSingleResult()).longValue();
                em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                        .setParameter("id", id).executeUpdate();
                return id;
            });
            quelleWechselt.set(false);
            Auftrag neuerAuftrag = new Auftrag(neuerAuftragId, URINET, WissenIngestStarten.TYP,
                    new Nutzlast(Map.of("entwurfId", entwurf, "aenderungNr", 1)), 0);
            CompletableFuture.runAsync(() -> kontext.mit(URINET,
                    () -> handler.nachLetztemFehlversuch(auftrag, UUID.randomUUID())), worker)
                    .get(1, TimeUnit.MINUTES);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "IN_ARBEIT");
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> {
                try {
                    handler.ausfuehren(neuerAuftrag);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ? and status = 'OFFEN' and etag = 2 "
                    + "and basis_version = 2 and inhalt like '%Neuer Vorschlag%'", entwurf)).isEqualTo(1);
            im(URINET, () -> {
                freigabe.annehmen(inhaber, entwurf, 1, () -> 2, null, null, null, null);
                return null;
            });
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite_version where entwurf_id = ? and erzeugt_von = ?",
                    entwurf, "infomaniak/" + wissenKonfiguration.modell())).isEqualTo(1);
        } finally {
            ausfuehren("delete from " + URINET.schema() + ".ki_verbrauch where ingest_entwurf_id = ?", entwurf);
            ausfuehren("delete from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = ?",
                    entwurf.toString());
            annahmeBereinigen(entwurf);
        }
    }

    @Test
    void manuellerEntwurfRuftBeiPersonalOderErschoepftemBudgetKeineKiAuf() throws Exception {
        UUID dokument = quelle(URINET);
        String pfad = "konzepte/ki-schutz-" + UUID.randomUUID();
        UUID seiteId = seite(URINET, pfad, "# Bisher", null);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        UUID entwurf = im(URINET, () -> manuellerEntwurfDienst.anlegen(inhaber,
                new ManuellerWissensentwurf.Eingabe(seiteId, pfad, "Konzept", "Probe", "Probe", List.of(), null,
                        List.of(new Quelleneintrag("q1", dokument, 1, 1, "Seite 1", null)),
                        "# Vorschlag\n\nAussage. [^q1]", "Probe", List.of())));
        entwuerfe.get(URINET).add(entwurf);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set status = 'VERALTET', etag = 1 where entwurf_id = ?", entwurf);
        AtomicBoolean aufgerufen = new AtomicBoolean();
        KiPort testKi = new KiPort() {
            @Override
            public Antwort metadaten(Anfrage eingabe) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WissensAntwort wissensIngest(WissensAnfrage eingabe) {
                aufgerufen.set(true);
                throw new AssertionError("Die KI darf nicht aufgerufen werden");
            }

            @Override
            public String antwortschema() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> artCodes() {
                throw new UnsupportedOperationException();
            }
        };
        var verarbeiten = new WissenIngestVerarbeiten(ingestSpeichern, wissenKonfiguration, testKi, kiVerbrauch,
                diffDienst);
        var handler = new WissenIngestHandler(verarbeiten, ingestSpeichern,
                new ManuelleNeuerzeugung(manuellSpeichern, verarbeiten, wissenKonfiguration), manuellSpeichern);
        try {
            ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?",
                    dokument);
            Auftrag erster = neuerzeugungStarten(entwurf, inhaber);
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> {
                try {
                    handler.ausfuehren(erster);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            assertThat(zeile(URINET, entwurf).get("hinweis").toString()).contains("Quellen");
            assertThat(aufgerufen).isFalse();

            ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'NORMAL' where id = ?",
                    dokument);
            ausfuehren("insert into " + URINET.schema()
                    + ".wissens_einstellung (id, monatsbudget_tokens) values (1, 1) "
                    + "on conflict (id) do update set monatsbudget_tokens = 1");
            Auftrag zweiter = neuerzeugungStarten(entwurf, inhaber);
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> {
                try {
                    handler.ausfuehren(zweiter);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN")
                    .containsEntry("hinweis", "Budget erschöpft");
            assertThat(aufgerufen).isFalse();
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 1",
                    entwurf)).isEqualTo(1);
        } finally {
            ausfuehren("delete from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = ?",
                    entwurf.toString());
        }
    }

    private Auftrag neuerzeugungStarten(UUID entwurf, Benutzer inhaber) {
        long id = im(URINET, () -> {
            freigabe.neuErzeugen(inhaber, entwurf, 1, () -> 1);
            EntityManager em = entityManager.get();
            long auftragId = ((Number) em.createNativeQuery("""
                    select max(id) from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = :id
                    """).setParameter("id", entwurf.toString()).getSingleResult()).longValue();
            em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                    .setParameter("id", auftragId).executeUpdate();
            return auftragId;
        });
        return new Auftrag(id, URINET, WissenIngestStarten.TYP,
                new Nutzlast(Map.of("entwurfId", entwurf, "aenderungNr", 1)), 0);
    }

    @Test
    void verwerfenRolltBeiNachfolgendemFehlerZurueck() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);

        assertThatThrownBy(() -> im(URINET, () -> {
            freigabe.verwerfen(inhaber, entwurf, 1, () -> 0, "Grund", null);
            throw new IllegalStateException("Testabbruch");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'OFFEN' and etag = 0", entwurf))
                .isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".zugriff_protokoll where dokument_id = ? and aktion = 'WISSEN_VERWERFEN'", entwurf)).isZero();
    }

    @Test
    void verwerfenEndpunktPrueftRolleUndIfMatch() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        UUID seite = seite(URINET, "konzepte/verwerfen-" + UUID.randomUUID(), "Probe", null);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set aktion = 'AENDERN', seite_id = ?, basis_version = 1 "
                + "where entwurf_id = ?", seite, entwurf);
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/verwerfen";
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String treuhaender = Testanmeldung.token("treuhaender-urinet");

        given().auth().oauth2(treuhaender).contentType("application/json").body("{\"grund\":\"Probe\"}")
                .when().post(pfad).then().statusCode(403);
        given().auth().oauth2(inhaber).contentType("application/json").body("{\"grund\":\"Probe\"}")
                .when().post(pfad).then().statusCode(428);
        given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json")
                .body("{\"grund\":\"Probe\"}").when().post(pfad).then().statusCode(204);
        given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json")
                .body("{\"grund\":\"Probe\"}").when().post(pfad).then().statusCode(412);
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "ERLEDIGT");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".zugriff_protokoll p join " + URINET.schema()
                + ".wissens_aenderung a on a.entwurf_id = p.dokument_id "
                + "where p.dokument_id = ? and p.aktion = 'WISSEN_VERWERFEN' "
                + "and p.details->>'grund' = 'Probe' and p.details->'wissen'->>'seiteId' = a.seite_id::text "
                + "and p.details->'wissen'->>'basisVersion' = '1' "
                + "and p.details->'wissen'->>'aenderungNr' = '1'", entwurf)).isEqualTo(1);
    }

    @Test
    void letzterIngestEntscheidSchreibtLogUndStartetWartendenAuftragAtomar() throws SQLException {
        assumeFalse(im(URINET, () -> ingest.warteschlangeBelegt()), "Ingest-Warteschlange ist bereits belegt");
        UUID entwurf = entwurf(URINET, quelle(URINET), "OFFEN");
        UUID wartend = entwurf(URINET, quelle(URINET), "WARTEND");
        aenderung(URINET, entwurf, 1);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);

        assertThatThrownBy(() -> im(URINET, () -> {
            freigabe.verwerfen(inhaber, entwurf, 1, () -> 0, "Grund", null);
            assertThat(entityManager.get().createNativeQuery(
                            "select status from wissens_entwurf where id = :id")
                    .setParameter("id", entwurf).getSingleResult()).isEqualTo("ERLEDIGT");
            assertThat(entityManager.get().createNativeQuery(
                            "select status from wissens_entwurf where id = :id")
                    .setParameter("id", wartend).getSingleResult()).isEqualTo("IN_ARBEIT");
            assertThat(((Number) entityManager.get().createNativeQuery(
                            "select count(*) from wissens_log where entwurf_id = :id and aktion = 'INGEST'")
                    .setParameter("id", entwurf).getSingleResult()).longValue()).isEqualTo(1);
            assertThat(((Number) entityManager.get().createNativeQuery(
                            "select count(*) from public.auftrag where typ = 'WISSEN_INGEST' "
                                    + "and nutzlast->>'entwurfId' = :id")
                    .setParameter("id", wartend.toString()).getSingleResult()).longValue()).isEqualTo(1);
            throw new IllegalStateException("Testabbruch");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Testabbruch");
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
        assertThat(zeile(URINET, wartend)).containsEntry("status", "WARTEND");
    }

    @Test
    void veralteteBasisWirdUnterSperreMarkiertUndBleibtNachCommitErhalten() throws SQLException {
        UUID seite = seite(URINET, "konzepte/basis-" + UUID.randomUUID(), "Probe", null);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set aktion = 'AENDERN', seite_id = ?, basis_version = 1 "
                + "where entwurf_id = ?", seite, entwurf);
        ausfuehren("update " + URINET.schema() + ".wissensseite set version = 2 where id = ?", seite);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);

        assertThat(im(URINET, () -> {
            var aenderung = freigabeSperre.laden(inhaber, entwurf, 1, () -> 0);
            return freigabeSperre.basisAktuell(aenderung);
        })).isFalse();
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 1", entwurf))
                .isEqualTo(1);
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
    }

    @Test
    void veralteteAenderungKannMitAktuellemEtagVerworfenWerden() throws SQLException {
        UUID seite = seite(URINET, "konzepte/veraltet-" + UUID.randomUUID(), "Probe", null);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set aktion = 'AENDERN', seite_id = ?, basis_version = 1 "
                + "where entwurf_id = ?", seite, entwurf);
        ausfuehren("update " + URINET.schema() + ".wissensseite set version = 2 where id = ?", seite);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        assertThat(im(URINET, () -> freigabeSperre.basisAktuell(
                freigabeSperre.laden(inhaber, entwurf, 1, () -> 0)))).isFalse();
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/verwerfen";
        String token = Testanmeldung.token("inhaber-urinet");
        given().auth().oauth2(token).header("If-Match", "\"0\"").contentType("application/json")
                .body("{\"grund\":\"Überholt\"}").when().post(pfad).then().statusCode(412);
        given().auth().oauth2(token).header("If-Match", "\"1\"").contentType("application/json")
                .body("{\"grund\":\"Überholt\"}").when().post(pfad).then().statusCode(204);
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "ERLEDIGT");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'VERWORFEN' and etag = 2", entwurf))
                .isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".zugriff_protokoll where dokument_id = ? and aktion = 'WISSEN_VERWERFEN'", entwurf))
                .isEqualTo(1);
    }

    @Test
    void freigabePrueftNeueQuellenMitIhrerAktuellenStufe() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String daten = """
                {"typ":"Synthese","titel":"Probe","beschreibung":"Ein Satz.","tags":[],
                 "status":"VERALTET","veraltetAb":null,
                 "quellen":[{"schluessel":"q1","dokumentId":"%s","seiteVon":1,"seiteBis":1,
                             "auszug":"Seite 1","quellSeiteId":null}],
                 "vertraulichkeit":"INTERN","inhalt":"Aussage.[^q1]","wissensquelleId":null}
                """.formatted(dokument);
        ausfuehren("update " + URINET.schema()
                        + ".wissens_aenderung set pfad = 'ueberblick', daten = cast(? as jsonb), "
                        + "inhalt = 'Aussage.[^q1]' where entwurf_id = ?", daten, entwurf);
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);

        var normal = im(URINET, () -> {
            var a = freigabeSperre.laden(inhaber, entwurf, 1, () -> 0);
            var vorschlag = freigabeRepository.vorschlagLaden(a);
            return freigabeKontext.pruefen(inhaber, entwurf, a, vorschlag, vorschlag.fassung(), "NORMAL");
        });
        assertThat(normal.mindeststufe()).isEqualTo(WissensStufe.NORMAL);
        assertThat(normal.pruefung().befunde()).extracting(WissensPruefung.Befund::code)
                .doesNotContain(WissensPruefung.Code.STUFE_UEBERBLICK);

        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'INTERN' where id = ?", dokument);
        var intern = im(URINET, () -> {
            var a = freigabeSperre.laden(inhaber, entwurf, 1, () -> 0);
            var vorschlag = freigabeRepository.vorschlagLaden(a);
            return freigabeKontext.pruefen(inhaber, entwurf, a, vorschlag, vorschlag.fassung(), "NORMAL");
        });
        assertThat(intern.mindeststufe()).isEqualTo(WissensStufe.NORMAL);
        assertThat(intern.wirksameStufe()).isEqualTo(WissensStufe.INTERN);
        assertThat(intern.pruefung().befunde()).extracting(WissensPruefung.Befund::code)
                .contains(WissensPruefung.Code.STUFE_UEBERBLICK);

        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?", dokument);
        var personal = im(URINET, () -> {
            var a = freigabeSperre.laden(inhaber, entwurf, 1, () -> 0);
            var vorschlag = freigabeRepository.vorschlagLaden(a);
            return freigabeKontext.pruefen(inhaber, entwurf, a, vorschlag, vorschlag.fassung(), null);
        });
        assertThat(personal.pruefung().befunde()).extracting(WissensPruefung.Befund::code)
                .contains(WissensPruefung.Code.QUELLE_PERSONAL);
    }

    @Test
    void fassungMitVersionQuellenLinksUndLogIstTeilDerTransaktion() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        UUID seite = UUID.randomUUID();
        Wissensseite fassung = new Wissensseite(seite, "konzepte/probe", Seitentyp.KONZEPT, "Probe",
                "Ein Satz.", List.of("probe"), Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag("q1", dokument, 1, 1, "Seite 1", null)),
                Wissensseite.Vertraulichkeit.INTERN, "Aussage.[^q1]", 1,
                null, null, null, null, false, false, null);

        assertThatThrownBy(() -> im(URINET, () -> {
            var aenderung = freigabeRepository.sperren(entwurf, 1).orElseThrow();
            freigabeRepository.fassungSpeichern(entwurf, aenderung, fassung, WissensStufe.INTERN,
                    WissensStufe.INTERN, "human:probe", "inhaber", null, Set.of("konzepte/andere"));
            EntityManager em = entityManager.get();
            assertThat(((Number) em.createNativeQuery("select count(*) from wissensseite_version where seite_id = :id")
                    .setParameter("id", seite).getSingleResult()).intValue()).isEqualTo(1);
            assertThat(((Number) em.createNativeQuery("select count(*) from wissensseite_quelle where seite_id = :id")
                    .setParameter("id", seite).getSingleResult()).intValue()).isEqualTo(1);
            assertThat(((Number) em.createNativeQuery("select count(*) from wissensseite_link where von_seite_id = :id")
                    .setParameter("id", seite).getSingleResult()).intValue()).isEqualTo(1);
            assertThat(((Number) em.createNativeQuery("select count(*) from wissens_log where entwurf_id = :id")
                    .setParameter("id", entwurf).getSingleResult()).intValue()).isEqualTo(1);
            assertThat(em.createNativeQuery("select status from wissens_aenderung where id = :id")
                    .setParameter("id", aenderung.id()).getSingleResult()).isEqualTo("ANGENOMMEN");
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissensseite where id = ?", seite)).isZero();
        assertThat(anzahl("select count(*) from " + ZWEITER.schema() + ".wissensseite where id = ?", seite)).isZero();
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_log where entwurf_id = ?", entwurf)).isZero();
    }

    @Test
    void angenommeneFassungEntferntQuellenhinweisNurInnerhalbDerTransaktion() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String pfad = "konzepte/probe-" + UUID.randomUUID();
        UUID seite = seite(URINET, pfad, "Probe", null);
        ausfuehren("update " + URINET.schema() + ".wissensseite set quelle_pruefen = true where id = ?", seite);
        ausfuehren("update " + URINET.schema() + ".wissens_aenderung "
                + "set aktion = 'AENDERN', seite_id = ?, pfad = ?, basis_version = 1 where entwurf_id = ?",
                seite, pfad, entwurf);
        Wissensseite fassung = new Wissensseite(seite, pfad, Seitentyp.KONZEPT, "Probe", "Probe", List.of(),
                Wissensseite.Status.AKTIV, null, List.of(), Wissensseite.Vertraulichkeit.NORMAL, "Probe", 2,
                null, null, null, null, false, false, null);
        assertThatThrownBy(() -> im(URINET, () -> {
            var aenderung = freigabeRepository.sperren(entwurf, 1).orElseThrow();
            freigabeRepository.fassungSpeichern(entwurf, aenderung, fassung, WissensStufe.NORMAL,
                    WissensStufe.NORMAL, "human:probe", "inhaber", null, Set.of());
            assertThat(entityManager.get().createNativeQuery("select quelle_pruefen from wissensseite where id = :id")
                    .setParameter("id", seite).getSingleResult()).isEqualTo(false);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissensseite where id = ? and quelle_pruefen", seite)).isEqualTo(1);
    }

    @Test
    void freigabeEntferntQuellenhinweisDerGeaendertenSeite() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String pfad = "konzepte/quelle-" + UUID.randomUUID();
        UUID seite = seite(URINET, pfad, "Alter Inhalt", null);
        ausfuehren("update " + URINET.schema() + ".wissensseite set quelle_pruefen = true where id = ?", seite);
        String daten = """
                {"typ":"Konzept","titel":"Probe","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","quellen":[{"schluessel":"q1","dokumentId":"%s","seiteVon":1,
                 "seiteBis":1,"auszug":"Seite 1","quellSeiteId":null}],"vertraulichkeit":"NORMAL",
                 "inhalt":"Aussage.[^q1]","wissensquelleId":null}
                """.formatted(dokument);
        ausfuehren("update " + URINET.schema()
                        + ".wissens_aenderung set aktion = 'AENDERN', seite_id = ?, pfad = ?, basis_version = 1, "
                        + "daten = cast(? as jsonb), inhalt = 'Aussage.[^q1]' where entwurf_id = ?",
                seite, pfad, daten, entwurf);
        try {
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).header("If-Match", "\"0\"")
                    .contentType("application/json").body("{}")
                    .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen")
                    .then().statusCode(204);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite where id = ? and not quelle_pruefen and version = 2", seite)).isEqualTo(1);
        } finally {
            annahmeBereinigen(entwurf);
        }
    }

    @Test
    void manuellerEntwurfWirdGeprueftUndNurMitA17Angelegt() throws SQLException {
        UUID dokument = quelle(URINET);
        String pfad = "konzepte/manuell-" + UUID.randomUUID();
        Map<String, Object> eingabe = Map.of("pfad", pfad, "typ", "Konzept", "titel", "Probe",
                "beschreibung", "Ein Satz.", "inhalt", "Aussage.[^q1]", "quellen",
                List.of(Map.of("schluessel", "q1", "dokumentId", dokument.toString(), "seiteVon", 1,
                        "seiteBis", 1, "auszug", "Seite 1")));
        given().auth().oauth2(Testanmeldung.token("treuhaender-urinet")).contentType("application/json")
                .body(eingabe).when().post("/api/v1/wissensentwuerfe").then().statusCode(403);
        String id = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                .body(eingabe).when().post("/api/v1/wissensentwuerfe").then().statusCode(201)
                .extract().path("id");
        UUID entwurf = UUID.fromString(id);
        entwuerfe.get(URINET).add(entwurf);
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissens_entwurf e join "
                + URINET.schema() + ".wissens_aenderung a on a.entwurf_id = e.id "
                + "where e.id = ? and e.anlass = 'MANUELL' and e.status = 'OFFEN' "
                + "and a.aktion = 'NEU' and a.befunde = '[]'::jsonb", entwurf)).isEqualTo(1);
        assertThat(anzahl("select count(*) from " + ZWEITER.schema() + ".wissens_entwurf where id = ?", entwurf))
                .isZero();
        String basisPfad = "konzepte/basis-" + UUID.randomUUID();
        UUID basis = seite(URINET, basisPfad, "Alter Inhalt.[^q1]", null);
        Map<String, Object> geaendert = new HashMap<>(eingabe);
        geaendert.put("seiteId", basis.toString());
        geaendert.put("pfad", basisPfad);
        geaendert.put("titel", "");
        String zweiteId = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json").body(geaendert)
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id");
        UUID zweiterEntwurf = UUID.fromString(zweiteId);
        entwuerfe.get(URINET).add(zweiterEntwurf);
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissens_aenderung "
                + "where entwurf_id = ? and aktion = 'AENDERN' and basis_version = 1 "
                + "and befunde @> '[{\"code\":\"FELD_FEHLT\"}]'::jsonb", zweiterEntwurf)).isEqualTo(1);
        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?", dokument);
        String dritteId = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json").body(eingabe)
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id");
        UUID dritterEntwurf = UUID.fromString(dritteId);
        entwuerfe.get(URINET).add(dritterEntwurf);
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissens_aenderung "
                + "where entwurf_id = ? and befunde @> '[{\"code\":\"QUELLE_PERSONAL\"}]'::jsonb",
                dritterEntwurf)).isEqualTo(1);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).header("If-Match", "\"0\"")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/wissensentwuerfe/" + dritterEntwurf + "/aenderungen/1/annehmen")
                .then().statusCode(422).body("befunde", hasItem("QUELLE_PERSONAL"));
    }

    @Test
    void annehmenPrueftAktuelleQuelleUndSchreibtAtomar() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String daten = """
                {"typ":"Konzept","titel":"Probe","beschreibung":"Ein Satz.","tags":[],
                 "status":"VERALTET","veraltetAb":null,
                 "quellen":[{"schluessel":"q1","dokumentId":"%s","seiteVon":1,"seiteBis":1,
                             "auszug":"Seite 1","quellSeiteId":null}],
                 "vertraulichkeit":"INTERN","inhalt":"Aussage.[^q1]","wissensquelleId":null}
                """.formatted(dokument);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set daten = cast(? as jsonb), inhalt = 'Aussage.[^q1]' "
                + "where entwurf_id = ?", daten, entwurf);
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen";
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String treuhaender = Testanmeldung.token("treuhaender-urinet");
        try {
            long verweigert = anzahl("select count(*) from " + URINET.schema()
                    + ".zugriff_protokoll where aktion = ?", "VERWEIGERT");
            given().auth().oauth2(treuhaender).contentType("application/json").body("{}")
                    .when().post(pfad).then().statusCode(403);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".zugriff_protokoll where aktion = ?", "VERWEIGERT")).isEqualTo(verweigert + 1);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite_version where entwurf_id = ?", entwurf)).isZero();
            given().auth().oauth2(inhaber).contentType("application/json").body("{}")
                    .when().post(pfad).then().statusCode(428);
            ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?",
                    dokument);
            given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json").body("{}")
                    .when().post(pfad).then().statusCode(422).body("befunde", hasItem("QUELLE_PERSONAL"));
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissens_log where entwurf_id = ?", entwurf)).isZero();
            ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'NORMAL' where id = ?",
                    dokument);
            given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json").body("{}")
                    .when().post(pfad).then().statusCode(204);
            given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json").body("{}")
                    .when().post(pfad).then().statusCode(412);
            assertThat(zeile(URINET, entwurf)).containsEntry("status", "ERLEDIGT");
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite_version where entwurf_id = ? and version = 1", entwurf)).isEqualTo(1);
            assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissensseite s join "
                    + URINET.schema() + ".wissens_aenderung a on a.seite_id = s.id "
                    + "where a.entwurf_id = ? and s.status = 'AKTIV' and s.vertraulichkeit = 'NORMAL'",
                    entwurf)).isEqualTo(1);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite_version_dokument h join " + URINET.schema()
                    + ".wissensseite_version v on v.seite_id = h.seite_id and v.version = h.version "
                    + "where v.entwurf_id = ?", entwurf)).isEqualTo(1);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".zugriff_protokoll p join " + URINET.schema()
                    + ".wissens_aenderung a on a.entwurf_id = p.dokument_id "
                    + "where p.dokument_id = ? and p.aktion = 'WISSEN_ANNEHMEN' "
                    + "and p.details->'wissen'->>'seiteId' = a.seite_id::text "
                    + "and p.details->'wissen'->>'neueVersion' = '1' "
                    + "and p.details->'wissen'->>'aenderungNr' = '1'", entwurf))
                    .isEqualTo(1);
            assertThat(anzahl("select count(*) from public.auftrag where typ = 'WISSEN_EMBEDDING' and "
                    + "nutzlast->>'seiteId' in (select seite_id::text from " + URINET.schema()
                    + ".wissens_aenderung where entwurf_id = ?)", entwurf)).isEqualTo(1);
        } finally {
            annahmeBereinigen(entwurf);
        }
    }

    @Test
    void geaenderteAnnahmeMitNullInListenWirdOhneSchreibvorgangAbgewiesen() throws SQLException {
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                        + ".wissens_aenderung set daten = cast(? as jsonb) where entwurf_id = ?",
                """
                {"typ":"Konzept","titel":"Probe","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","quellen":[],"inhalt":"Probe.","wissensquelleId":null}
                """, entwurf);
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen";
        String inhaber = Testanmeldung.token("inhaber-urinet");
        for (String fassung : List.of("{\"tags\":[null],\"quellen\":[]}",
                "{\"tags\":[],\"quellen\":[null]}")) {
            given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json")
                    .body("{\"fassung\":" + fassung + "}")
                    .when().post(pfad).then().statusCode(400);
        }
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'OFFEN' and etag = 0", entwurf))
                .isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissensseite_version where entwurf_id = ?", entwurf)).isZero();
    }

    @Test
    void annahmeRolltMitAuftragProtokollUndVersionshuelleZurueck() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String daten = """
                {"typ":"Konzept","titel":"Probe","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","veraltetAb":null,
                 "quellen":[{"schluessel":"q1","dokumentId":"%s","seiteVon":1,"seiteBis":1,
                             "auszug":"Seite 1","quellSeiteId":null}],
                 "vertraulichkeit":"NORMAL","inhalt":"Aussage.[^q1]","wissensquelleId":null}
                """.formatted(dokument);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set daten = cast(? as jsonb), inhalt = 'Aussage.[^q1]' "
                + "where entwurf_id = ?", daten, entwurf);
        AtomicReference<UUID> seite = new AtomicReference<>();
        assertThatThrownBy(() -> im(URINET, () -> {
            assertThat(freigabeAnnahme.ausfuehren(new Benutzer("inhaber", Rolle.INHABER),
                    entwurf, 1, () -> 0, null, null, null, null)).isTrue();
            EntityManager em = entityManager.get();
            seite.set((UUID) em.createNativeQuery("select seite_id from wissens_aenderung where entwurf_id = :id")
                    .setParameter("id", entwurf).getSingleResult());
            assertThat(((Number) em.createNativeQuery("select count(*) from wissensseite_version_dokument "
                    + "where seite_id = :id").setParameter("id", seite.get()).getSingleResult()).intValue())
                    .isEqualTo(1);
            assertThat(((Number) em.createNativeQuery("select count(*) from public.auftrag "
                    + "where typ = 'WISSEN_EMBEDDING' and nutzlast->>'seiteId' = :id")
                    .setParameter("id", seite.get().toString()).getSingleResult()).intValue()).isEqualTo(1);
            throw new IllegalStateException("Testabbruch");
        })).hasMessage("Testabbruch");
        assertThat(seite.get()).isNotNull();
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissensseite where id = ?", seite.get()))
                .isZero();
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".zugriff_protokoll where dokument_id = ? and aktion = 'WISSEN_ANNEHMEN'", entwurf)).isZero();
        assertThat(anzahl("select count(*) from public.auftrag where typ = 'WISSEN_EMBEDDING' "
                + "and nutzlast->>'seiteId' = ?", seite.get().toString())).isZero();
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
    }

    @Test
    void annahmeVeralteterBasisMeldet412UndBehaeltMarkierung() throws SQLException {
        UUID seite = seite(URINET, "konzepte/basis-" + UUID.randomUUID(), "Probe", null);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set aktion = 'AENDERN', seite_id = ?, basis_version = 1 "
                + "where entwurf_id = ?", seite, entwurf);
        ausfuehren("update " + URINET.schema() + ".wissensseite set version = 2 where id = ?", seite);
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen";
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).header("If-Match", "\"0\"")
                .contentType("application/json").body("{}").when().post(pfad).then().statusCode(412);
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissens_aenderung where entwurf_id = ? and status = 'VERALTET' and etag = 1", entwurf))
                .isEqualTo(1);
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "OFFEN");
    }

    @Test
    void paralleleAnnahmenErzeugenNurEineVersion() throws Exception {
        UUID dokument = quelle(URINET);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String daten = """
                {"typ":"Konzept","titel":"Probe","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","veraltetAb":null,
                 "quellen":[{"schluessel":"q1","dokumentId":"%s","seiteVon":1,"seiteBis":1,
                             "auszug":"Seite 1","quellSeiteId":null}],
                 "vertraulichkeit":"NORMAL","inhalt":"Aussage.[^q1]","wissensquelleId":null}
                """.formatted(dokument);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set daten = cast(? as jsonb), inhalt = 'Aussage.[^q1]' "
                + "where entwurf_id = ?", daten, entwurf);
        String pfad = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen";
        String token = Testanmeldung.token("inhaber-urinet");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService parallel = Executors.newFixedThreadPool(2)) {
            var erster = parallel.submit(() -> {
                start.await();
                return given().auth().oauth2(token).header("If-Match", "\"0\"")
                        .contentType("application/json").body("{}").when().post(pfad).statusCode();
            });
            var zweiter = parallel.submit(() -> {
                start.await();
                return given().auth().oauth2(token).header("If-Match", "\"0\"")
                        .contentType("application/json").body("{}").when().post(pfad).statusCode();
            });
            start.countDown();
            assertThat(List.of(erster.get(30, TimeUnit.SECONDS), zweiter.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 412);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite_version where entwurf_id = ?", entwurf)).isEqualTo(1);
        } finally {
            annahmeBereinigen(entwurf);
        }
    }

    @Test
    void paralleleFreigabenErhaltenMindeststufeZitierterSeiten() throws Exception {
        UUID dokument = quelle(URINET);
        String quellpfad = "konzepte/parallel-quelle-" + UUID.randomUUID();
        UUID quellseite = seite(URINET, quellpfad, "Probe", null);
        UUID quellEntwurf = manuellerEntwurf(URINET);
        UUID zitierEntwurf = manuellerEntwurf(URINET);
        aenderung(URINET, quellEntwurf, 1);
        aenderung(URINET, zitierEntwurf, 1);
        String quellDaten = """
                {"typ":"Konzept","titel":"Quelle","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","quellen":[{"schluessel":"q1","dokumentId":"%s","seiteVon":1,
                 "seiteBis":1,"auszug":"Seite 1"}],"inhalt":"Aussage.[^q1]"}
                """.formatted(dokument);
        String zitierDaten = """
                {"typ":"Konzept","titel":"Zitat","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","quellen":[{"schluessel":"q1","quellSeiteId":"%s"}],
                 "inhalt":"Aussage.[^q1]"}
                """.formatted(quellseite);
        ausfuehren("update " + URINET.schema()
                        + ".wissens_aenderung set aktion = 'AENDERN', seite_id = ?, basis_version = 1, pfad = ?, "
                        + "daten = cast(? as jsonb), inhalt = 'Aussage.[^q1]' where entwurf_id = ?",
                quellseite, quellpfad, quellDaten, quellEntwurf);
        ausfuehren("update " + URINET.schema()
                        + ".wissens_aenderung set pfad = ?, daten = cast(? as jsonb), "
                        + "inhalt = 'Aussage.[^q1]' where entwurf_id = ?",
                "konzepte/parallel-zitat-" + UUID.randomUUID(), zitierDaten, zitierEntwurf);
        String token = Testanmeldung.token("inhaber-urinet");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService parallel = Executors.newFixedThreadPool(2)) {
            var quelle = parallel.submit(() -> {
                start.await();
                return given().auth().oauth2(token).header("If-Match", "\"0\"")
                        .contentType("application/json").body("{\"vertraulichkeit\":\"INTERN\"}")
                        .when().post("/api/v1/wissensentwuerfe/" + quellEntwurf + "/aenderungen/1/annehmen")
                        .statusCode();
            });
            var zitat = parallel.submit(() -> {
                start.await();
                return given().auth().oauth2(token).header("If-Match", "\"0\"")
                        .contentType("application/json").body("{}")
                        .when().post("/api/v1/wissensentwuerfe/" + zitierEntwurf + "/aenderungen/1/annehmen")
                        .statusCode();
            });
            start.countDown();
            assertThat(List.of(quelle.get(30, TimeUnit.SECONDS), zitat.get(30, TimeUnit.SECONDS)))
                    .containsExactly(204, 204);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite s join " + URINET.schema()
                    + ".wissens_aenderung a on a.seite_id = s.id where a.entwurf_id in (?, ?) "
                    + "and s.vertraulichkeit = 'INTERN'", quellEntwurf, zitierEntwurf)).isEqualTo(2);
            assertThat(anzahl("select count(*) from " + URINET.schema()
                    + ".wissensseite_version where entwurf_id in (?, ?)", quellEntwurf, zitierEntwurf))
                    .isEqualTo(2);
        } finally {
            annahmeBereinigen(zitierEntwurf);
            annahmeBereinigen(quellEntwurf);
            ausfuehren("delete from " + URINET.schema() + ".wissensseite_quelle where seite_id = ?", quellseite);
            ausfuehren("delete from " + URINET.schema() + ".wissensseite_dokument where seite_id = ?", quellseite);
        }
    }

    @Test
    void ueberblickMitInternerZitatseiteZeigtBefundImEndpunkt() throws SQLException {
        UUID quellseite = seite(URINET, "konzepte/intern-" + UUID.randomUUID(), "Probe", null);
        ausfuehren("update " + URINET.schema()
                + ".wissensseite set vertraulichkeit = 'INTERN', vertraulichkeit_mindest = 'INTERN' where id = ?",
                quellseite);
        UUID entwurf = manuellerEntwurf(URINET);
        aenderung(URINET, entwurf, 1);
        String daten = """
                {"typ":"Synthese","titel":"Überblick","beschreibung":"Ein Satz.","tags":[],
                 "status":"AKTIV","veraltetAb":null,
                 "quellen":[{"schluessel":"q1","dokumentId":null,"seiteVon":null,"seiteBis":null,
                             "auszug":null,"quellSeiteId":"%s"}],
                 "vertraulichkeit":"NORMAL","inhalt":"Aussage.[^q1]","wissensquelleId":null}
                """.formatted(quellseite);
        ausfuehren("update " + URINET.schema()
                + ".wissens_aenderung set pfad = 'ueberblick', daten = cast(? as jsonb), "
                + "inhalt = 'Aussage.[^q1]' where entwurf_id = ?", daten, entwurf);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).header("If-Match", "\"0\"")
                .contentType("application/json").body("{\"vertraulichkeit\":\"NORMAL\"}")
                .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen")
                .then().statusCode(422).body("befunde", hasItem("STUFE_UEBERBLICK"));
        assertThat(anzahl("select count(*) from " + URINET.schema()
                + ".wissensseite_version where entwurf_id = ?", entwurf)).isZero();
    }

    private void annahmeBereinigen(UUID entwurf) throws SQLException {
        String schema = URINET.schema();
        String ids = "(select seite_id from " + schema
                + ".wissens_aenderung where entwurf_id = ? and aktion = 'NEU' and seite_id is not null)";
        try (Connection verbindung = datenbank.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try (var befehl = verbindung.createStatement()) {
                befehl.execute("set local session_replication_role = replica");
            }
            for (String sql : List.of(
                    "delete from public.auftrag where typ = 'WISSEN_EMBEDDING' and nutzlast->>'seiteId' in "
                            + "(select seite_id::text from " + schema + ".wissens_aenderung where entwurf_id = ?)",
                    "delete from " + schema + ".wissensseite_version_dokument h using " + schema
                            + ".wissensseite_version v where h.seite_id = v.seite_id and h.version = v.version "
                            + "and v.entwurf_id = ?",
                    "delete from " + schema + ".wissensseite_version where entwurf_id = ?",
                    "delete from " + schema + ".wissens_log where entwurf_id = ?",
                    "delete from " + schema + ".zugriff_protokoll where dokument_id = ?",
                    "delete from " + schema + ".wissensseite_quelle where seite_id in " + ids,
                    "delete from " + schema + ".wissensseite_dokument where seite_id in " + ids,
                    "delete from " + schema + ".wissensseite_link where von_seite_id in " + ids,
                    "delete from " + schema + ".wissensseite where id in " + ids)) {
                try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
                    befehl.setObject(1, entwurf);
                    befehl.executeUpdate();
                }
            }
            verbindung.commit();
        }
    }

    @Test
    void laufenderEntwurfNurImEigenenSchemaUndNurWartendOderInArbeit() throws SQLException {
        UUID wartend = quelle(URINET);
        UUID offen = quelle(URINET);
        entwurf(URINET, wartend, "WARTEND");
        entwurf(URINET, offen, "ERLEDIGT");

        assertThat(im(URINET, () -> ingest.entwurfFuerDokumentLaufend(wartend))).isTrue();
        assertThat(im(URINET, () -> ingest.entwurfFuerDokumentLaufend(offen))).isFalse();
        assertThat(im(ZWEITER, () -> ingest.entwurfFuerDokumentLaufend(wartend))).isFalse();
    }

    @Test
    void warteschlangeJeMandantNurMitWartendInArbeitOderOffenBelegt() throws SQLException {
        boolean zweiterVorher = im(ZWEITER, () -> ingest.warteschlangeBelegt());
        assumeFalse(im(URINET, () -> ingest.warteschlangeBelegt()),
                "urinet hat schon einen Ingest in der Warteschlange");

        for (String status : List.of("ERLEDIGT", "FEHLER", "ABGELEHNT")) {
            entwurf(URINET, quelle(URINET), status);
        }
        assertThat(im(URINET, () -> ingest.warteschlangeBelegt())).isFalse();

        // ein WARTEND ohne aktiven Entwurf belegt die Warteschlange ebenfalls (Req 5.13, Reihenfolge der Auslösung)
        for (String status : List.of("WARTEND", "IN_ARBEIT", "OFFEN")) {
            UUID id = entwurf(URINET, quelle(URINET), status);
            assertThat(im(URINET, () -> ingest.warteschlangeBelegt())).as(status).isTrue();
            assertThat(im(ZWEITER, () -> ingest.warteschlangeBelegt())).as(status).isEqualTo(zweiterVorher);
            ausfuehren("delete from " + URINET.schema() + ".wissens_entwurf where id = ?", id);
        }
    }

    @Test
    void verbrauchNurDesEigenenMandantenUndDerWissensZwecke() throws SQLException {
        verbrauch(URINET, "WISSEN_INGEST", 1_000, VON.plusSeconds(60));
        verbrauch(URINET, "WISSEN_LINT", 200, VON.plusSeconds(120));
        verbrauch(URINET, "ERFASSUNG", 50_000, VON.plusSeconds(180));
        verbrauch(URINET, "WISSEN_INGEST", 7_000, BIS);
        verbrauch(ZWEITER, "WISSEN_INGEST", 30, VON.plusSeconds(60));

        assertThat(im(URINET, () -> ingest.verbrauchImZeitraum(VON, BIS))).isEqualTo(1_200);
        assertThat(im(ZWEITER, () -> ingest.verbrauchImZeitraum(VON, BIS))).isEqualTo(30);
    }

    @Test
    void entwurfLandetImEigenenSchema() throws SQLException {
        UUID dokument = quelle(ZWEITER);
        UUID id = UUID.randomUUID();
        entwuerfe.get(ZWEITER).add(id);
        im(ZWEITER, () -> ingest.entwurfAnlegen(new WissenIngestPort.NeuerEntwurf(id, dokument, 1, 2, "FEHLER",
                "KERNAUSSAGEN", "test-modell", "process:wissen-ingest", "Probe")));

        assertThat(anzahl("select count(*) from " + ZWEITER.schema() + ".wissens_entwurf where id = ?", id))
                .isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissens_entwurf where id = ?", id)).isZero();
    }

    @Test
    void wartenderIngestWirdBeiAusgeschoepftemMonatsbudgetMitFehlerAbgeschlossen() throws SQLException {
        UUID entwurf = entwurf(URINET, quelle(URINET), "WARTEND");
        verbrauch(URINET, "WISSEN_INGEST", 1_000, VON.plusSeconds(60));
        monatsbudgetSetzen(URINET, 1_000);

        assertThat(im(URINET, () -> ingest.wartendenAktivieren(1_000, VON, BIS))).isEmpty();
        assertThat(zeile(URINET, entwurf)).containsEntry("status", "FEHLER")
                .containsEntry("hinweis", "Budget erschöpft");
    }

    @Test
    void wartenderIngestWirdNichtBeiOffenemIngestAktiviert() throws SQLException {
        UUID wartend = entwurf(URINET, quelle(URINET), "WARTEND");
        entwurf(URINET, quelle(URINET), "OFFEN");

        assertThat(im(URINET, () -> ingest.wartendenAktivieren(1_000_000, VON, BIS))).isEmpty();
        assertThat(zeile(URINET, wartend)).containsEntry("status", "WARTEND");
    }

    @Test
    void wartenderIngestWirdBeiVerfuegbaremBudgetAktiviert() throws SQLException {
        UUID wartend = entwurf(URINET, quelle(URINET), "WARTEND");

        assertThat(im(URINET, () -> ingest.wartendenAktivieren(1_000_000, VON, BIS))).contains(wartend);
        assertThat(zeile(URINET, wartend)).containsEntry("status", "IN_ARBEIT");
    }

    @Test
    void budgetReservierungZaehltLaufUndMonatNurImEigenenSchema() throws SQLException {
        UUID lauf = entwurf(URINET, quelle(URINET), "IN_ARBEIT");
        // nur ein aktiver Ingest je Mandant; für die Reservierung zählt der Status nicht
        UUID andererLauf = entwurf(URINET, quelle(URINET), "WARTEND");
        UUID fremderLauf = entwurf(ZWEITER, quelle(ZWEITER), "IN_ARBEIT");
        // Reservierungen anderer Tests laufen nach 5 Minuten ab; sie zählen hier als Sockel mit
        long monat = reserviert(URINET) + 1_000;

        WissenIngestPort.BudgetReservierung erste = im(URINET,
                () -> ingest.budgetReservieren(lauf, 600, 1_000, monat, VON, BIS));
        assertThat(erste.reserviert()).isTrue();

        WissenIngestPort.BudgetReservierung lauflimit = im(URINET,
                () -> ingest.budgetReservieren(lauf, 500, 1_000, monat + 10_000, VON, BIS));
        assertThat(lauflimit.reserviert()).isFalse();
        assertThat(lauflimit.monatsbudgetErschoepft()).isFalse();

        WissenIngestPort.BudgetReservierung monatslimit = im(URINET,
                () -> ingest.budgetReservieren(andererLauf, 500, 1_000, monat, VON, BIS));
        assertThat(monatslimit.reserviert()).isFalse();
        assertThat(monatslimit.monatsbudgetErschoepft()).isTrue();

        long fremdesMonatsbudget = reserviert(ZWEITER) + 1_000;
        assertThat(im(ZWEITER, () -> ingest.budgetReservieren(fremderLauf, 900, 1_000, fremdesMonatsbudget,
                VON, BIS)).reserviert()).isTrue();
        assertThat(im(URINET, () -> ingest.budgetReservieren(andererLauf, 400, 1_000, monat, VON, BIS))
                .reserviert()).isTrue();

        im(URINET, () -> {
            ingest.budgetFreigeben(erste.id());
            return null;
        });
        assertThat(im(URINET, () -> ingest.budgetReservieren(lauf, 500, 1_000, monat, VON, BIS)).reserviert())
                .isTrue();

        ausfuehren("update " + URINET.schema() + ".ki_verbrauch_reservierung set ablauf = now() - interval '1 second' "
                + "where entwurf_id in (?, ?)", lauf, andererLauf);
        assertThat(im(URINET, () -> ingest.budgetReservieren(lauf, 1_000, 1_000, monat, VON, BIS)).reserviert())
                .isTrue();
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".ki_verbrauch_reservierung "
                + "where entwurf_id = ?", andererLauf)).isZero();
    }

    @Test
    void holtNurHandlerFehltEntwuerfeOhneNeuerenIngestNach() throws SQLException {
        UUID alt = entwurf(URINET, quelle(URINET), "FEHLER", WissenIngestStarten.HANDLER_FEHLT);
        UUID neuer = entwurf(URINET, quelle(URINET), "FEHLER", "Neuerer Entwurf");
        ausfuehren("update " + URINET.schema() + ".wissens_entwurf set erstellt = erstellt + interval '1 second' "
                + "where id = ?", neuer);

        assertThat(im(URINET, () -> ingest.handlerFehltNachholen(WissenIngestStarten.HANDLER_FEHLT))).isEqualTo(1);
        assertThat(zeile(URINET, alt)).containsEntry("status", "WARTEND").containsEntry("hinweis", null);
        assertThat(zeile(URINET, neuer)).containsEntry("status", "FEHLER");
    }

    @Test
    void indexsucheVerbindetDieWoerterDerKernaussagenMitOder() throws SQLException {
        UUID lohn = seite(URINET, "probe/index-lohn", "Die Lohndeklaration ist bis Ende Januar fällig.", null);
        UUID uvg = seite(URINET, "probe/index-uvg", "Die Unfallversicherung wird jährlich abgerechnet.", null);
        UUID fremd = seite(URINET, "probe/index-fremd", "Ein Text über Fussballvereine.", null);
        String suchtext = "Die Lohndeklaration ist fällig.\nDie Unfallversicherung wird abgerechnet.";

        assertThat(im(URINET, () -> ingest.seitenIndexLaden(java.util.Set.of(lohn, uvg, fremd), suchtext)))
                .extracting(WissenIngestPort.SeitenMetadaten::id).containsExactlyInAnyOrder(lohn, uvg);
        assertThat(im(ZWEITER, () -> ingest.seitenIndexLaden(java.util.Set.of(lohn, uvg, fremd), suchtext)))
                .isEmpty();
        assertThat(JdbcWissenIngest.oderSuche("Am 1. April: UVG-Prämie, uvg!")).isEqualTo("april or uvg or prämie");
        assertThat(JdbcWissenIngest.oderSuche("ab 12 zu")).isEmpty();
    }

    @Test
    void findetDieQuelleSeiteNurImEigenenSchema() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID ohneSeite = quelle(URINET);
        UUID seite = seite(URINET, "quellen/probe-" + dokument.toString().substring(0, 8), "Quelle", dokument);

        assertThat(im(URINET, () -> ingest.quelleSeiteFinden(dokument))).contains(seite);
        assertThat(im(URINET, () -> ingest.quelleSeiteFinden(ohneSeite))).isEmpty();
        assertThat(im(ZWEITER, () -> ingest.quelleSeiteFinden(dokument))).isEmpty();
    }

    @Test
    void hoechsteQuellenNummerAusSeitenUndVorschlaegenNurImEigenenSchema() throws SQLException {
        UUID dokument = quelle(URINET);
        UUID anderes = quelle(URINET);
        String kurz = dokument.toString().substring(0, 8);
        UUID seite = seite(URINET, "quellen/probe-" + kurz, "Quelle", dokument);
        for (String schluessel : List.of("q-" + kurz + "-7", "q-" + kurz + "-12", "q-" + kurz + "-x", "fremd-99")) {
            ausfuehren("insert into " + URINET.schema() + ".wissensseite_quelle (seite_id, schluessel, dokument_id, "
                    + "seite_von, seite_bis, auszug) values (?, ?, ?, 1, 1, 'Seite')", seite, schluessel, dokument);
        }

        assertThat(im(URINET, () -> ingest.hoechsteQuellenNummer(dokument))).isEqualTo(12);
        assertThat(im(URINET, () -> ingest.hoechsteQuellenNummer(anderes))).isZero();
        assertThat(im(ZWEITER, () -> ingest.hoechsteQuellenNummer(dokument))).isZero();

        UUID entwurf = entwurf(URINET, dokument, "ABGELEHNT");
        ausfuehren("insert into " + URINET.schema() + ".wissens_aenderung (entwurf_id, nr, aktion, pfad, daten, "
                + "inhalt, diff, status) values (?, 1, 'NEU', 'konzepte/probe', cast(? as jsonb), 'Probe', "
                + "'[]'::jsonb, 'VERWORFEN')", entwurf, "{\"quellen\": [{\"schluessel\": \"q-" + kurz
                + "-31\", \"dokumentId\": \"" + dokument + "\"}, {\"schluessel\": \"q-" + kurz
                + "-77\", \"dokumentId\": \"" + anderes + "\"}]}");

        assertThat(im(URINET, () -> ingest.hoechsteQuellenNummer(dokument))).isEqualTo(31);
        assertThat(im(ZWEITER, () -> ingest.hoechsteQuellenNummer(dokument))).isZero();
    }

    @Test
    void holtHandlerFehltNichtNachWennDasDokumentKeineWissensquelleMehrIst() throws SQLException {
        UUID dokument = quelle(URINET);
        ausfuehren("update " + URINET.schema() + ".dokument set art = 'PROJEKTDOKUMENT' where id = ?", dokument);
        UUID entwurf = entwurf(URINET, dokument, "FEHLER", WissenIngestStarten.HANDLER_FEHLT);

        im(URINET, () -> ingest.handlerFehltNachholen(WissenIngestStarten.HANDLER_FEHLT));

        assertThat(zeile(URINET, entwurf)).containsEntry("status", "FEHLER");
    }

    private UUID seite(MandantCode code, String pfad, String inhalt, UUID wissensquelle) throws SQLException {
        UUID id = UUID.randomUUID();
        seiten.get(code).add(id);
        ausfuehren("insert into " + code.schema() + ".wissensseite (id, pfad, typ, titel, beschreibung, "
                        + "vertraulichkeit, inhalt, version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am, "
                        + "wissensquelle_id) values (?, ?, ?, 'Probe', 'Probe', 'NORMAL', ?, 1, 'probe', now(), "
                        + "'probe', now(), ?)",
                id, pfad, wissensquelle == null ? "Konzept" : "Quelle", inhalt, wissensquelle);
        return id;
    }

    private UUID quelle(MandantCode code) throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(code.wert(), "WISSENSQUELLE", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        dokumente.get(code).add(id);
        ausfuehren("update " + code.schema() + ".dokument set volltext = 'Seite 1\fSeite 2\f', seiten = 2, "
                + "s3_pdfa = 'archiv/probe.pdf', s3_version_pdfa = 'v1', pdfa_fehlt = false where id = ?", id);
        return id;
    }

    private UUID manuellerEntwurf(MandantCode code) throws SQLException {
        UUID id = UUID.randomUUID();
        entwuerfe.get(code).add(id);
        ausfuehren("insert into " + code.schema()
                + ".wissens_entwurf (id, anlass, status, erstellt_von, erstellt) "
                + "values (?, 'MANUELL', 'OFFEN', 'inhaber', now())", id);
        return id;
    }

    private void aenderung(MandantCode code, UUID entwurfId, int nr) throws SQLException {
        ausfuehren("insert into " + code.schema()
                + ".wissens_aenderung (entwurf_id, nr, aktion, pfad, daten, inhalt, diff, status) "
                + "values (?, ?, 'NEU', 'konzepte/probe', '{}'::jsonb, '', '{}'::jsonb, 'OFFEN')", entwurfId, nr);
    }

    private UUID entwurf(MandantCode code, UUID dokument, String status) throws SQLException {
        return entwurf(code, dokument, status, null);
    }

    private UUID entwurf(MandantCode code, UUID dokument, String status, String hinweis) throws SQLException {
        UUID id = UUID.randomUUID();
        entwuerfe.get(code).add(id);
        ausfuehren("insert into " + code.schema()
                        + ".wissens_entwurf (id, anlass, dokument_id, status, schritt, hinweis, erstellt_von, erstellt) "
                        + "values (?, 'INGEST', ?, ?, case when ? = 'WARTEND' then 'KERNAUSSAGEN' end, ?, 'probe', now())",
                id, dokument, status, status, hinweis);
        return id;
    }

    private void monatsbudgetSetzen(MandantCode code, int tokens) throws SQLException {
        ausfuehren("update " + code.schema() + ".wissens_einstellung set monatsbudget_tokens = ? where id = 1",
                tokens);
    }

    private Monatsbudget monatsbudget(MandantCode code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select monatsbudget_tokens from " + code.schema() + ".wissens_einstellung where id = 1");
             ResultSet ergebnis = abfrage.executeQuery()) {
            if (!ergebnis.next()) {
                return new Monatsbudget(false, null);
            }
            int wert = ergebnis.getInt(1);
            return new Monatsbudget(true, ergebnis.wasNull() ? null : wert);
        }
    }

    private Map<String, Object> zeile(MandantCode code, UUID id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select status, hinweis from " + code.schema()
                     + ".wissens_entwurf where id = ?")) {
            abfrage.setObject(1, id);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                Map<String, Object> werte = new HashMap<>();
                werte.put("status", ergebnis.getString(1));
                werte.put("hinweis", ergebnis.getString(2));
                return werte;
            }
        }
    }

    private void verbrauch(MandantCode code, String zweck, int tokens, Instant zeit) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("insert into " + code.schema()
                     + ".ki_verbrauch (zweck, modell, tokens_ein, tokens_aus, zeit) values (?, 'test-modell', ?, 0, ?) "
                     + "returning id")) {
            befehl.setString(1, zweck);
            befehl.setInt(2, tokens);
            befehl.setObject(3, OffsetDateTime.ofInstant(zeit, ZoneOffset.UTC));
            try (ResultSet z = befehl.executeQuery()) {
                z.next();
                verbrauch.get(code).add(z.getLong(1));
            }
        }
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

    private long anzahl(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                abfrage.setObject(i + 1, werte[i]);
            }
            try (ResultSet z = abfrage.executeQuery()) {
                z.next();
                return z.getLong(1);
            }
        }
    }

    private long reserviert(MandantCode code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select coalesce(sum(tokens), 0) from "
                     + code.schema() + ".ki_verbrauch_reservierung where ablauf > now()");
             ResultSet z = abfrage.executeQuery()) {
            z.next();
            return z.getLong(1);
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.execute();
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

    private record Monatsbudget(boolean vorhanden, Integer wert) {
    }
}
