package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Wiederherstellung;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.adapter.out.persistence.JdbcManifestDaten;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ProtokollHash;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.Wiederherstellung;
import ch.urinet.archiv.sicherung.application.WiederherstellungsReferenz;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cucumber-Nachweise zu Req 4.1–4.3, 4.6, 4.7 und 13.9. */
public class WissenDatenmodellSchritte {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final List<String> TABELLEN = List.of("wissensseite", "wissensseite_version",
            "wissensseite_quelle", "wissensseite_dokument", "wissensseite_version_dokument", "wissensseite_link",
            "wissensseite_chunk", "wissens_entwurf", "wissens_aenderung", "wissens_log", "wissens_lint",
            "wissens_einstellung");

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
    S3Client s3;

    @Inject
    AblageEinstellungen einstellungen;

    @Inject
    WiederherstellungsReferenz referenz;

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

    @Dann("lehnen Wissensversionen und Log UPDATE und DELETE ab")
    public void appendOnly() throws SQLException {
        UUID seite = UUID.randomUUID();
        String schema = MANDANT.schema();
        try (Connection verbindung = datenquelle.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("insert into " + schema + ".wissensseite "
                    + "(id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt, version, erzeugt_von, erzeugt_am, "
                    + "freigegeben_von, freigegeben_am) values ('" + seite + "', 'probe/" + seite + "', 'Konzept', "
                    + "'Synthetische Probe', 'Probe', 'NORMAL', 'synthetischer Inhalt', 1, 'human:probe', "
                    + "clock_timestamp(), 'human:probe', clock_timestamp())");
            befehl.execute("insert into " + schema + ".wissensseite_version "
                    + "(seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit) values ('" + seite
                    + "', 1, '{}', 'synthetischer Inhalt', 'human:probe', 'human:probe', clock_timestamp())");
            befehl.execute("insert into " + schema
                    + ".wissens_log (zeit, aktion, akteur, seiten) values (clock_timestamp(), 'CREATION', "
                    + "'human:probe', array['" + seite + "']::uuid[])");
            for (String tabelle : List.of("wissensseite_version", "wissens_log")) {
                assertThatThrownBy(() -> befehl.execute("update " + schema + "." + tabelle + " set zeit = zeit"))
                        .as(tabelle + " UPDATE").isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
                assertThatThrownBy(() -> befehl.execute("delete from " + schema + "." + tabelle))
                        .as(tabelle + " DELETE").isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
        }
    }

    @Dann("enthält das Manifest Hashes für Wissensversionen und Log, ohne Seiteninhalte zu protokollieren")
    public void manifestHashtNur() throws Exception {
        String inhalt = "synthetischer-wissensinhalt-nicht-loggen";
        UUID seite = UUID.randomUUID();
        Instant zeit = Instant.now().plus(Duration.ofHours(1));
        try (Connection verbindung = datenquelle.get().getConnection()) {
            try (PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                    + ".wissensseite (id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt, version, "
                    + "erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am) values (?, ?, 'Konzept', "
                    + "'Synthetische Probe', 'Probe', 'NORMAL', ?, 1, 'human:probe', ?, 'human:probe', ?)")) {
                p.setObject(1, seite);
                p.setString(2, "probe/" + seite);
                p.setString(3, inhalt);
                p.setObject(4, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                p.setObject(5, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                p.executeUpdate();
            }
            try (PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                    + ".wissensseite_version (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit) "
                    + "values (?, 1, '{}', ?, 'human:probe', 'human:probe', ?)")) {
                p.setObject(1, seite);
                p.setString(2, inhalt);
                p.setObject(3, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                p.executeUpdate();
            }
            try (PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                    + ".wissens_log (zeit, aktion, akteur, seiten, angaben) "
                    + "values (?, 'CREATION', 'human:probe', array[?::uuid], ?::jsonb)")) {
                p.setObject(1, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                p.setObject(2, seite);
                p.setString(3, "{\"inhalt\":\"" + inhalt + "\"}");
                p.executeUpdate();
            }
        }
        try (LogMitschnitt protokoll = LogMitschnitt.starten()) {
            try (var worker = Executors.newSingleThreadExecutor()) {
                var blaetter = worker.submit(() -> kontext.mit(MANDANT, () ->
                        QuarkusTransaction.requiringNew().call(() -> List.of(
                                manifestDaten.blaetter(ProtokollHash.WISSENSSEITE_VERSION, zeit.minusSeconds(1),
                                        zeit.plusSeconds(5)),
                                manifestDaten.blaetter(ProtokollHash.WISSENS_LOG, zeit.minusSeconds(1),
                                        zeit.plusSeconds(5)))))).get();
                assertThat(blaetter.get(0)).hasSize(1);
                assertThat(blaetter.get(1)).hasSize(1);
            }
            assertThat(protokoll.volltext()).doesNotContain(inhalt);
        }
    }

    @Dann("bleiben die Seiten- und Versionszahlen beim Restore-Roundtrip unverändert")
    public void restoreZaehltWissensseiten() throws Exception {
        UUID seite = UUID.randomUUID();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                     + ".wissensseite (id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt, version, "
                     + "erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am) values (?, ?, 'Konzept', "
                     + "'Synthetische Probe', 'Probe', 'NORMAL', 'synthetisch', 1, 'human:probe', "
                     + "clock_timestamp(), 'human:probe', clock_timestamp())")) {
            p.setObject(1, seite);
            p.setString(2, "probe/" + seite);
            p.executeUpdate();
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement("insert into " + MANDANT.schema()
                     + ".wissensseite_version (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit) "
                     + "values (?, 1, '{}', 'synthetisch', 'human:probe', 'human:probe', clock_timestamp())")) {
            p.setObject(1, seite);
            p.executeUpdate();
        }

        long[] vorher = wissensAnzahlen();
        S3Client verwaltung = TestBucket.verwaltung();
        String praefix = "urinet-archiv-p-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String bucket = MANDANT.bucket(praefix);
        boolean angelegt = false;
        var ziel = new AblageEinstellungen(praefix, Optional.empty(), Sperrmodus.COMPLIANCE, Optional.empty(),
                einstellungen.urlGueltigkeit(), einstellungen.zwischenablage());
        var datei = Files.createTempFile("wissen-restore-", ".bin");
        try {
            Files.writeString(datei, SicherungBucketExport.BUCKET_FORMAT, StandardCharsets.US_ASCII);
            verwaltung.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
            angelegt = true;
            verwaltung.putBucketVersioning(b -> b.bucket(bucket)
                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
            var ergebnis = new Wiederherstellung(new S3Wiederherstellung(s3, ziel), Clock.systemUTC(), referenz)
                    .wiederherstellen(MANDANT, List.of(), datei, false);
            assertThat(ergebnis.exitCode()).isZero();
            assertThat(wissensAnzahlen()).containsExactly(vorher);
        } finally {
            Files.deleteIfExists(datei);
            if (angelegt) {
                TestBucket.aufraeumen(verwaltung, bucket);
            }
            verwaltung.close();
        }
    }

    private long[] wissensAnzahlen() throws SQLException {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement("select (select count(*) from " + MANDANT.schema()
                     + ".wissensseite), (select count(*) from " + MANDANT.schema() + ".wissensseite_version)");
             ResultSet ergebnis = p.executeQuery()) {
            assertThat(ergebnis.next()).isTrue();
            return new long[]{ergebnis.getLong(1), ergebnis.getLong(2)};
        }
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
