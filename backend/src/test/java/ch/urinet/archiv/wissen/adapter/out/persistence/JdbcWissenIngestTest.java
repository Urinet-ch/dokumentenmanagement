package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.Versionskopf;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.FreigabeDienst;
import ch.urinet.archiv.wissen.application.FreigabeAnnahme;
import ch.urinet.archiv.wissen.application.FreigabeKontext;
import ch.urinet.archiv.wissen.application.FreigabeSperre;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import ch.urinet.archiv.wissen.application.WissenIngestPort;
import ch.urinet.archiv.wissen.application.WissenIngestStarten;
import ch.urinet.archiv.wissen.domain.WissensStufe;
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
                    "delete from " + schema + ".wissensseite_version_dokument where seite_id in " + ids,
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

    private long anzahl(String sql, Object wert) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, wert);
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
