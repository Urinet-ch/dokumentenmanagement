package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort;
import ch.urinet.archiv.integration.application.webseite.WebseitenLesePort;
import ch.urinet.archiv.integration.domain.webseite.FeedCursor;
import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Einmalige Plan- und N+1-Prüfung mit 20'000 synthetischen Dokumenten und 50'000 Feed-Einträgen. Die Spalte
 * {@code chunk.embedding} hat im Produktivschema fix 3'584 Dimensionen; die Testvektoren belegen davon nur 16.
 */
@QuarkusTest
@Tag("start-1")
@EnabledIfSystemProperty(named = "archiv.db-leistung", matches = "true")
class DatenbankAbfragenLeistungTest {

    private static final List<MandantCode> MANDANTEN =
            List.of(new MandantCode("urinet"), new MandantCode("vhf-gsk"));
    private static final int DOKUMENTE_PRO_MANDANT = 10_000;
    private static final int CHUNKS_PRO_DOKUMENT = 5;
    private static final int FEED_PRO_MANDANT = 25_000;
    private static final int FEED_MAX = 100;
    private static final List<String> STUFEN = List.of("oeffentlich", "mitglieder", "vorstand");

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantKontext kontext;

    @Inject
    WebseitenLesePort webseitenLese;

    @Inject
    AenderungFeedPort aenderungFeed;

    @Inject
    EntityManager em;

    private final String lauf = UUID.randomUUID().toString().replace("-", "");
    private final List<List<UUID>> dokumentIds = MANDANTEN.stream()
            .<List<UUID>>map(m -> new ArrayList<>()).toList();
    private final List<String> arten = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Test
    void plaeneUndAnzahlDerAnweisungenBleibenBeiZwanzigtausendDokumentenBegrenzt() throws Exception {
        datenAnlegen();
        Statistics statistik = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistik.setStatisticsEnabled(true);

        for (int i = 0; i < MANDANTEN.size(); i++) {
            MandantCode mandant = MANDANTEN.get(i);
            ListenFilter filter = new ListenFilter(arten.get(i), "2099", lauf);
            Lesezugriff zugriff = new Lesezugriff("leistungsprobe", Zugriffsstufe.VORSTAND,
                    Zugriffsstufe.VORSTAND, Optional.empty());

            statistik.clear();
            long start10 = System.nanoTime();
            WebseiteSeite seite10 = imMandanten(mandant,
                    () -> webseitenLese.liste(zugriff, filter, WebseiteSortierung.DATUM_AB, 1, 10));
            long dauer10 = millisekundenSeit(start10);
            long anweisungen10 = statistik.getPrepareStatementCount();

            statistik.clear();
            long start100 = System.nanoTime();
            WebseiteSeite seite100 = imMandanten(mandant,
                    () -> webseitenLese.liste(zugriff, filter, WebseiteSortierung.DATUM_AB, 1, 100));
            long dauer100 = millisekundenSeit(start100);
            long anweisungen100 = statistik.getPrepareStatementCount();

            assertThat(seite10.eintraege()).hasSize(10);
            assertThat(seite100.eintraege()).hasSize(100);
            assertThat(seite10.gesamt()).isEqualTo(1_000);
            assertThat(seite100.gesamt()).isEqualTo(seite10.gesamt());
            assertThat(anweisungen10).isPositive().isEqualTo(anweisungen100);
            assertThat(dauer10).isLessThan(30_000);
            assertThat(dauer100).isLessThan(30_000);

            long startFeed = System.nanoTime();
            AenderungFeedPort.Lesung feed = imMandanten(mandant,
                    () -> aenderungFeed.lesen(new FeedCursor(0, 0), FEED_MAX, STUFEN));
            long dauerFeed = millisekundenSeit(startFeed);
            assertThat(feed.zeilen()).hasSize(FEED_MAX);
            assertThat(dauerFeed).isLessThan(30_000);

            String listenPlan = planListen(mandant, arten.get(i));
            String feedPlan = planFeed(mandant);
            String vektorPlan = planVektor(mandant);
            String exportPlan = planExport(mandant);
            System.out.printf("%s: Liste 10=%d ms, 100=%d ms, SQL=%d/%d; Feed=%d ms%n",
                    mandant.wert(), dauer10, dauer100, anweisungen10, anweisungen100, dauerFeed);
            System.out.println("Listenplan " + mandant.wert() + ":\n" + listenPlan);
            System.out.println("Feedplan " + mandant.wert() + ":\n" + feedPlan);
            System.out.println("Vektorplan " + mandant.wert() + ":\n" + vektorPlan);
            System.out.println("Exportplan " + mandant.wert() + ":\n" + exportPlan);
            assertThat(listenPlan).contains("dokument_webseite_liste_ix");
            assertThat(feedPlan).contains("webseite_aenderung_xid_ix");
            assertThat(vektorPlan).contains("chunk_embedding_hnsw");
            assertThat(exportPlan).contains("dokument_pk");
        }

        assertIndexPlan("select id from " + MANDANTEN.getFirst().schema()
                + ".chat_nachricht where gespraech_id = '00000000-0000-4000-8000-000000000001' "
                + "order by id limit 50", "chat_nachricht_gespraech_ix");
        assertIndexPlan("select datum from " + MANDANTEN.getFirst().schema()
                + ".manifest where datum between date '2099-01-01' and date '2099-12-31' order by datum",
                "manifest_pk");
        assertIndexPlan("select id from public.auftrag where status = 'OFFEN' "
                + "and (nicht_vor is null or nicht_vor <= now()) "
                + "and typ = any(array['OCR'::text]) order by id limit 10",
                "auftrag_status_nicht_vor_ix", "auftrag_status_lebenszeichen_ix");
        assertIndexPlan("select max(erstellt) from public.sicherung_export "
                + "where art = 'probe' and mandant is not distinct from 'urinet'",
                "sicherung_export_fenster_idx");
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdownNow();
        for (int i = 0; i < MANDANTEN.size(); i++) {
            Testdokumente.loeschen(datenbank.get(), MANDANTEN.get(i).wert(), dokumentIds.get(i));
        }
    }

    private <T> T imMandanten(MandantCode mandant, Supplier<T> arbeit) throws Exception {
        return worker.submit(() -> kontext.mit(mandant,
                () -> QuarkusTransaction.requiringNew().call(arbeit::get))).get(30, TimeUnit.SECONDS);
    }

    private void datenAnlegen() throws SQLException {
        for (int i = 0; i < MANDANTEN.size(); i++) {
            MandantCode mandant = MANDANTEN.get(i);
            mandantVorhanden(mandant);
            String schema = mandant.schema();
            String art = art(schema);
            arten.add(art);
            String zusatz = "{\"jahr\":\"2099\",\"zugriff\":\"mitglieder\",\"veroeffentlicht\":true}";

            try (Connection verbindung = datenbank.get().getConnection()) {
                verbindung.setAutoCommit(false);
                try (PreparedStatement dokument = verbindung.prepareStatement("insert into " + schema
                        + ".dokument (id, art, titel, datum, quelle, vertraulichkeit, erstellt_von, status, "
                        + "bestaetigt, bestaetigt_von, geschaeftsjahr, volltext, zusatz) "
                        + "values (?, ?, ?, ?, 'UPLOAD', 'NORMAL', 'db-leistung', 'ARCHIVIERT', "
                        + "clock_timestamp(), 'db-leistung', ?, ?, cast(? as jsonb))");
                     PreparedStatement chunk = verbindung.prepareStatement("insert into " + schema
                             + ".chunk (dokument_id, nr, text, seite_von, seite_bis, embedding) "
                             + "values (?, ?, ?, ?, ?, cast(? as public.halfvec))")) {
                    for (int n = 0; n < DOKUMENTE_PRO_MANDANT; n++) {
                        UUID id = UUID.randomUUID();
                        dokumentIds.get(i).add(id);
                        String ausgabe = n % 10 == 0 ? lauf : "andere-" + n % 10;
                        String dokumentZusatz = zusatz.substring(0, zusatz.length() - 1)
                                + ",\"ausgabe\":\"" + ausgabe + "\"}";
                        dokument.setObject(1, id);
                        dokument.setString(2, art);
                        dokument.setString(3, "Synthetische Leistungsprobe " + n);
                        dokument.setObject(4, LocalDate.of(2099, 1, 1).plusDays(n % 365));
                        dokument.setInt(5, n % 10 == 0 ? 2099 : 2098);
                        dokument.setString(6, "Synthetischer Text für die Datenbank-Leistungsprüfung " + n);
                        dokument.setString(7, dokumentZusatz);
                        dokument.addBatch();

                        for (int abschnitt = 1; abschnitt <= CHUNKS_PRO_DOKUMENT; abschnitt++) {
                            chunk.setObject(1, id);
                            chunk.setInt(2, abschnitt);
                            chunk.setString(3, "Synthetischer Abschnitt " + abschnitt + " zu Dokument " + n);
                            chunk.setInt(4, abschnitt);
                            chunk.setInt(5, abschnitt);
                            if (abschnitt == 1) {
                                chunk.setString(6, embedding(n));
                            } else {
                                chunk.setNull(6, Types.VARCHAR);
                            }
                            chunk.addBatch();
                        }

                        if (n % 250 == 249) {
                            dokument.executeBatch();
                            chunk.executeBatch();
                            verbindung.commit();
                        }
                    }
                    dokument.executeBatch();
                    chunk.executeBatch();
                    verbindung.commit();
                } catch (SQLException e) {
                    verbindung.rollback();
                    throw e;
                } finally {
                    verbindung.setAutoCommit(true);
                }

                verbindung.setAutoCommit(false);
                try (PreparedStatement feed = verbindung.prepareStatement("insert into " + schema
                        + ".webseite_aenderung (xid, dokument_id, stufe_nachher) "
                        + "select pg_current_xact_id(), ?, 'mitglieder' from generate_series(1, ?)")) {
                    feed.setObject(1, dokumentIds.get(i).getFirst());
                    feed.setInt(2, FEED_PRO_MANDANT - DOKUMENTE_PRO_MANDANT);
                    feed.executeUpdate();
                    verbindung.commit();
                } catch (SQLException e) {
                    verbindung.rollback();
                    throw e;
                } finally {
                    verbindung.setAutoCommit(true);
                }

                try (Statement analyze = verbindung.createStatement()) {
                    analyze.execute("analyze " + schema + ".dokument");
                    analyze.execute("analyze " + schema + ".chunk");
                    analyze.execute("analyze " + schema + ".webseite_aenderung");
                }
            }
        }
    }

    private void mandantVorhanden(MandantCode mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (zeile.next()) {
                    return;
                }
            }
        }
        provisionierung.anlegen(mandant.wert(), null, null);
    }

    private String art(String schema) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select code from " + schema + ".dokumentart where aktiv order by code limit 1");
             ResultSet zeile = abfrage.executeQuery()) {
            if (!zeile.next()) {
                throw new IllegalStateException("Kein aktiver Dokumentart-Seed für die Leistungsprüfung");
            }
            return zeile.getString(1);
        }
    }

    private String planListen(MandantCode mandant, String art) throws SQLException {
        String sql = "select d.id from " + mandant.schema() + ".dokument d where "
                + "d.status = 'ARCHIVIERT' and d.vertraulichkeit = 'NORMAL' and d.bestaetigt is not null "
                + "and d.zusatz -> 'veroeffentlicht' = 'true'::jsonb and d.art = ? "
                + "and d.zusatz ->> 'jahr' = '2099' and d.zusatz ->> 'ausgabe' = ? "
                + "and d.zusatz ->> 'zugriff' = any(array['oeffentlich','mitglieder','vorstand']) "
                + "order by d.datum desc nulls last, d.id limit 10";
        return plan(sql, statement -> {
            statement.setString(1, art);
            statement.setString(2, lauf);
        });
    }

    private String planFeed(MandantCode mandant) throws SQLException {
        String sql = "select e.id from " + mandant.schema() + ".webseite_aenderung e "
                + "where (e.xid, e.id) > (?::xid8, ?) and e.xid < pg_snapshot_xmin(pg_current_snapshot()) "
                + "and (e.stufe_vorher = any(?) or e.stufe_nachher = any(?)) "
                + "order by e.xid, e.id limit ?";
        return plan(sql, statement -> {
            statement.setString(1, "0");
            statement.setLong(2, 0);
            statement.setArray(3, statement.getConnection().createArrayOf("text", STUFEN.toArray()));
            statement.setArray(4, statement.getConnection().createArrayOf("text", STUFEN.toArray()));
            statement.setInt(5, FEED_MAX);
        });
    }

    private String planVektor(MandantCode mandant) throws SQLException {
        String sql = "select c.dokument_id from " + mandant.schema()
                + ".chunk c where c.embedding is not null "
                + "order by c.embedding operator(public.<=>) cast(? as public.halfvec) limit 50";
        return plan(sql, statement -> statement.setString(1, embedding(1)));
    }

    private String planExport(MandantCode mandant) throws SQLException {
        String sql = "select d.id from " + mandant.schema() + ".dokument d left join " + mandant.schema()
                + ".dokument_finanz f on f.dokument_id = d.id where d.status = 'ARCHIVIERT' "
                + "and d.geschaeftsjahr = 2099 and d.id > ?::uuid order by d.id limit 100";
        return plan(sql, statement -> statement.setString(1, "00000000-0000-0000-0000-000000000000"));
    }

    private void assertIndexPlan(String sql, String... indexes) throws SQLException {
        String plan = plan("explain " + sql, statement -> {
        }, true);
        System.out.println("Indexplan " + String.join(" oder ", indexes) + ":\n" + plan);
        assertThat(plan).containsAnyOf(indexes);
    }

    private String plan(String sql, SqlBinder binder) throws SQLException {
        return plan("explain " + sql, binder, false);
    }

    private String plan(String sql, SqlBinder binder, boolean erzwingeIndex) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try {
                if (erzwingeIndex) {
                    try (Statement einstellung = verbindung.createStatement()) {
                        einstellung.execute("set local enable_seqscan = off");
                    }
                }
                try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
                    binder.binden(abfrage);
                    StringBuilder plan = new StringBuilder();
                    try (ResultSet zeile = abfrage.executeQuery()) {
                        while (zeile.next()) {
                            plan.append(zeile.getString(1)).append('\n');
                        }
                    }
                    return plan.toString();
                }
            } finally {
                verbindung.rollback();
            }
        }
    }

    private static String embedding(int seed) {
        float[] vektor = new float[EmbeddingPort.DIMENSION];
        vektor[seed % 16] = 1;
        return Vektortext.text(vektor);
    }

    private static long millisekundenSeit(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void binden(PreparedStatement statement) throws SQLException;
    }
}
