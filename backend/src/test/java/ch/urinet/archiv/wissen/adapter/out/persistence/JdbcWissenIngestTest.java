package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenIngestPort;
import ch.urinet.archiv.wissen.application.WissenIngestStarten;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

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
            for (UUID id : seiten.get(code)) {
                ausfuehren("delete from " + code.schema() + ".wissensseite where id = ?", id);
            }
            for (UUID id : entwuerfe.get(code)) {
                ausfuehren("delete from " + code.schema() + ".wissens_aenderung where entwurf_id = ?", id);
                ausfuehren("delete from " + code.schema() + ".wissens_entwurf where id = ?", id);
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
