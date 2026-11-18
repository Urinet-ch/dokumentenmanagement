package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.adapter.out.persistence.JdbcManifestDaten;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import ch.urinet.archiv.protokoll.domain.ProtokollHash;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cucumber-Nachweise zu Req 4.1–4.3 und 4.6 (Spec 04, Aufgabe 13.3). Testzeilen schreiben die Schritte nur in
 * Transaktionen, die sie zurückrollen, mit Zeiten im Jahr 2001: Kein Rest bleibt in {@code m_urinet}, und kein
 * echtes Manifestfenster enthält sie.
 */
public class WissenDatenmodellSchritte {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final Instant ZEIT = Instant.parse("2001-01-01T23:00:10Z");
    private static final List<String> TABELLEN = List.of("wissensseite", "wissensseite_version",
            "wissensseite_quelle", "wissensseite_dokument", "wissensseite_version_dokument", "wissensseite_link",
            "wissensseite_chunk", "wissens_entwurf", "wissens_aenderung", "wissens_log", "wissens_lint",
            "wissens_einstellung");
    private static final List<String> VERSION_PFLICHT = List.of("seite_id", "version", "daten", "inhalt",
            "erzeugt_von", "freigegeben_von", "zeit");
    private static final List<String> VERSION_OPTIONAL = List.of("grund", "entwurf_id", "aenderung_id");
    private static final List<String> NUR_EINFUEGEN = List.of("wissensseite_version",
            "wissensseite_version_dokument", "wissens_log");

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantDatenbank mandantDatenbank;

    @Inject
    Instance<AgroalDataSource> datenquelle;

    @Inject
    JdbcManifestDaten manifestDaten;

    @Inject
    MandantKontext kontext;

    @Inject
    UserTransaction transaktion;

    @Angenommen("das Wissensdatenmodell ist für den Mandanten «{word}» migriert")
    public void mandantMigriert(String code) throws SQLException {
        assertThat(code).isEqualTo(MANDANT.wert());
        boolean vorhanden;
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                vorhanden = zeile.next();
            }
        }
        if (!vorhanden) {
            provisionierung.anlegen(code, null, null);
        } else {
            mandantDatenbank.schemaMigrieren(MANDANT.schema());
        }
    }

    @Dann("enthält das Mandantenschema alle Wissensbasistabellen und den Changeset «{word}»")
    public void schemaVollstaendig(String changeset) throws SQLException {
        assertThat(changeset).isEqualTo("043-wissen-datenmodell");
        assertThat(werte("select table_name from information_schema.tables where table_schema = ?",
                MANDANT.schema())).containsAll(TABELLEN);
        assertThat(werte("select id from " + MANDANT.schema()
                + ".databasechangelog where exectype = 'EXECUTED'")).contains(changeset);
    }

    @Dann("speichert eine Seitenversion Fassung, Akteure, Zeit und Entwurf und lehnt eine doppelte Versionsnummer ab")
    public void versionVollstaendig() throws SQLException {
        assertThat(werte("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = 'wissensseite_version' and is_nullable = 'NO'", MANDANT.schema()))
                .containsAll(VERSION_PFLICHT);
        assertThat(werte("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = 'wissensseite_version' and is_nullable = 'YES'", MANDANT.schema()))
                .containsAll(VERSION_OPTIONAL);
        zurueckgerolltJdbc(befehl -> {
            UUID seite = seite(befehl);
            version(befehl, seite, 1);
            version(befehl, seite, 2);
            Savepoint vorher = befehl.getConnection().setSavepoint();
            assertThatThrownBy(() -> version(befehl, seite, 2)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("wissensseite_version_seite_nr_uk");
            befehl.getConnection().rollback(vorher);
            assertThatThrownBy(() -> version(befehl, seite, 0)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("wissensseite_version_nr_ck");
        });
    }

    @Dann("lehnen Wissensversionen, ihre Quellenbezüge und das Log UPDATE, DELETE und TRUNCATE ab")
    public void appendOnly() throws SQLException {
        String schema = MANDANT.schema();
        zurueckgerolltJdbc(befehl -> {
            UUID seite = seite(befehl);
            version(befehl, seite, 1);
            UUID dokument = UUID.randomUUID();
            befehl.execute("insert into " + schema + ".dokument (id, quelle, erstellt_von, status) values ('"
                    + dokument + "', 'UPLOAD', 'probe', 'NEU')");
            befehl.execute("insert into " + schema + ".wissensseite_version_dokument (seite_id, version, dokument_id) "
                    + "values ('" + seite + "', 1, '" + dokument + "')");
            befehl.execute("insert into " + schema + ".wissens_log (zeit, aktion, akteur, seiten) values ('"
                    + ZEIT + "', 'CREATION', 'human:probe', array['" + seite + "']::uuid[])");
            for (String tabelle : NUR_EINFUEGEN) {
                String spalte = tabelle.equals("wissens_log") ? "zeit" : "seite_id";
                for (String sql : List.of("update " + schema + "." + tabelle + " set " + spalte + " = " + spalte,
                        "delete from " + schema + "." + tabelle,
                        "truncate " + schema + "." + tabelle + " cascade")) {
                    Savepoint vorher = befehl.getConnection().setSavepoint();
                    assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                            .hasMessageContaining("nur Einfügen erlaubt");
                    befehl.getConnection().rollback(vorher);
                }
            }
        });
    }

    @Dann("enthält das Manifest Hashes für Wissensversionen und Log, ohne Seiteninhalte zu protokollieren")
    public void manifestHashtNur() throws Exception {
        String inhalt = "synthetischer-wissensinhalt-nicht-loggen";
        record Ergebnis(List<ManifestDaten.Blatt> versionen, List<ManifestDaten.Blatt> log, String protokoll) {
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Ergebnis ergebnis = CompletableFuture.supplyAsync(() -> kontext.mit(MANDANT, () -> {
                try {
                    transaktion.begin();
                    try (Connection verbindung = datenquelle.get().getConnection();
                         Statement befehl = verbindung.createStatement()) {
                        UUID seite = seite(befehl);
                        try (PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                                + ".wissensseite_version (seite_id, version, daten, inhalt, erzeugt_von, "
                                + "freigegeben_von, zeit) values (?, 1, '{}', ?, 'human:probe', 'human:probe', ?)")) {
                            p.setObject(1, seite);
                            p.setString(2, inhalt);
                            p.setObject(3, ZEIT.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                            p.executeUpdate();
                        }
                        try (PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                                + ".wissens_log (zeit, aktion, akteur, seiten, angaben) "
                                + "values (?, 'CREATION', 'human:probe', array[?::uuid], ?::jsonb)")) {
                            p.setObject(1, ZEIT.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                            p.setObject(2, seite);
                            p.setString(3, "{\"inhalt\":\"" + inhalt + "\"}");
                            p.executeUpdate();
                        }
                        try (LogMitschnitt protokoll = LogMitschnitt.starten()) {
                            return new Ergebnis(
                                    manifestDaten.blaetter(ProtokollHash.WISSENSSEITE_VERSION, ZEIT.minusSeconds(1),
                                            ZEIT.plusSeconds(5)),
                                    manifestDaten.blaetter(ProtokollHash.WISSENS_LOG, ZEIT.minusSeconds(1),
                                            ZEIT.plusSeconds(5)),
                                    protokoll.volltext());
                        }
                    } finally {
                        transaktion.rollback();
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);

            assertThat(ergebnis.versionen()).hasSize(1).allSatisfy(b -> assertThat(b.sha256()).hasSize(64));
            assertThat(ergebnis.log()).hasSize(1).allSatisfy(b -> assertThat(b.sha256()).hasSize(64));
            assertThat(ergebnis.protokoll()).doesNotContain(inhalt);
        } finally {
            worker.shutdown();
        }
    }

    private interface JdbcArbeit {
        void ausfuehren(Statement befehl) throws SQLException;
    }

    private void zurueckgerolltJdbc(JdbcArbeit arbeit) throws SQLException {
        try (Connection verbindung = datenquelle.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try (Statement befehl = verbindung.createStatement()) {
                arbeit.ausfuehren(befehl);
            } finally {
                verbindung.rollback();
            }
        }
    }

    private static UUID seite(Statement befehl) throws SQLException {
        UUID seite = UUID.randomUUID();
        befehl.execute("insert into " + MANDANT.schema() + ".wissensseite (id, pfad, typ, titel, beschreibung, "
                + "vertraulichkeit, inhalt, version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am) "
                + "values ('" + seite + "', 'probe/" + seite + "', 'Konzept', 'Synthetische Probe', 'Probe', "
                + "'NORMAL', 'synthetischer Inhalt', 1, 'human:probe', '" + ZEIT + "', 'human:probe', '" + ZEIT
                + "')");
        return seite;
    }

    private static void version(Statement befehl, UUID seite, int nr) throws SQLException {
        befehl.execute("insert into " + MANDANT.schema() + ".wissensseite_version (seite_id, version, daten, "
                + "inhalt, erzeugt_von, freigegeben_von, zeit) values ('" + seite + "', " + nr
                + ", '{}', 'synthetischer Inhalt', 'ai:probe', 'human:probe', '" + ZEIT + "')");
    }

    private List<String> werte(String sql, String... parameter) throws SQLException {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement p = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                p.setString(i + 1, parameter[i]);
            }
            try (ResultSet ergebnis = p.executeQuery()) {
                List<String> werte = new ArrayList<>();
                while (ergebnis.next()) {
                    werte.add(ergebnis.getString(1));
                }
                return werte;
            }
        }
    }
}
