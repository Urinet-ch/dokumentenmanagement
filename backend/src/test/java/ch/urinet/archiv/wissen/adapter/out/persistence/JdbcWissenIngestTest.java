package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenIngestPort;
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
    private final Map<MandantCode, List<Long>> verbrauch = new HashMap<>();

    @BeforeEach
    void mandanten() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
            dokumente.put(code, new ArrayList<>());
            verbrauch.put(code, new ArrayList<>());
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            for (Long id : verbrauch.get(code)) {
                ausfuehren("delete from " + code.schema() + ".ki_verbrauch where id = ?", id);
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
        im(ZWEITER, () -> ingest.entwurfAnlegen(new WissenIngestPort.NeuerEntwurf(id, dokument, 1, 2, "FEHLER",
                "KERNAUSSAGEN", "test-modell", "process:wissen-ingest", "Probe")));

        assertThat(anzahl("select count(*) from " + ZWEITER.schema() + ".wissens_entwurf where id = ?", id))
                .isEqualTo(1);
        assertThat(anzahl("select count(*) from " + URINET.schema() + ".wissens_entwurf where id = ?", id)).isZero();
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
        UUID id = UUID.randomUUID();
        ausfuehren("insert into " + code.schema() + ".wissens_entwurf (id, anlass, dokument_id, status, erstellt_von, "
                + "erstellt) values (?, 'INGEST', ?, ?, 'probe', now())", id, dokument, status);
        return id;
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
}
