package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Wiederherstellung;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.Wiederherstellung;
import ch.urinet.archiv.sicherung.application.WiederherstellungsReferenz;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Lokaler Restore-Roundtrip mit synthetischen DB-Zeilen und getrennten MinIO-Quell- und Ziel-Buckets. */
@QuarkusTest
class SicherungRestoreUebungTest {

    private static final String QUELL_PRAEFIX = "restore-source-";
    private static final String ZIEL_PRAEFIX = "urinet-archiv-p-";

    @Inject
    S3Client s3;

    @Inject
    AblageEinstellungen einstellungen;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    WiederherstellungsReferenz referenz;

    @Inject
    AgroalDataSource datenquelle;

    @Test
    void stelltSynthetischeDokumenteWiederHerUndLiefertDateienUndManifest(@TempDir Path temp) throws Exception {
        S3Client verwaltung = TestBucket.verwaltung();
        MandantCode mandant = new MandantCode("urinet");
        mandantSicherstellen();
        String quellPraefix = QUELL_PRAEFIX + UUID.randomUUID().toString().substring(0, 8) + "-";
        String quellBucket = mandant.bucket(quellPraefix);
        String zielPraefix = ZIEL_PRAEFIX + UUID.randomUUID().toString().substring(0, 8) + "-";
        String zielBucket = mandant.bucket(zielPraefix);
        boolean quelleAngelegt = false;
        UUID archivId = UUID.randomUUID();
        UUID eingangId = UUID.randomUUID();
        String archivOriginal = "archiv/2026/" + archivId + "/original.pdf";
        String archivPdfa = "archiv/2026/" + archivId + "/pdfa.pdf";
        String eingangOriginal = "eingang/2026/" + eingangId + "/original.pdf";
        String eingangPdfa = "eingang/2026/" + eingangId + "/pdfa.pdf";
        String manifest = "manifest/2026-01-01.json";
        byte[] archivOriginalBytes = "synthetisches archiviertes Original".getBytes(StandardCharsets.UTF_8);
        byte[] archivPdfaBytes = "synthetische archivierte PDF/A".getBytes(StandardCharsets.UTF_8);
        byte[] eingangOriginalBytes = "synthetisches Original im Eingang".getBytes(StandardCharsets.UTF_8);
        byte[] eingangPdfaBytes = "synthetische PDF/A im Eingang".getBytes(StandardCharsets.UTF_8);
        byte[] manifestBytes = "{\"format\":\"synthetisch\"}".getBytes(StandardCharsets.UTF_8);
        List<UUID> dokumente = List.of(archivId, eingangId);
        boolean zielAngelegt = false;

        try {
            quelleAngelegt = true;
            verwaltung.createBucket(b -> b.bucket(quellBucket).objectLockEnabledForBucket(true));
            verwaltung.putBucketVersioning(b -> b.bucket(quellBucket)
                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
            zielAngelegt = true;
            verwaltung.createBucket(b -> b.bucket(zielBucket).objectLockEnabledForBucket(true));
            verwaltung.putBucketVersioning(b -> b.bucket(zielBucket)
                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));

            String archivOriginalQuelle = ablegen(quellBucket, archivOriginal, archivOriginalBytes);
            String archivPdfaQuelle = ablegen(quellBucket, archivPdfa, archivPdfaBytes);
            String eingangOriginalQuelle = ablegen(quellBucket, eingangOriginal, eingangOriginalBytes);
            String eingangPdfaQuelle = ablegen(quellBucket, eingangPdfa, eingangPdfaBytes);
            ablegen(quellBucket, manifest, manifestBytes);
            dokumentAnlegen(mandant.schema(), archivId, "ARCHIVIERT", archivPdfa, archivPdfaQuelle,
                    archivPdfaBytes, archivOriginal, archivOriginalQuelle, archivOriginalBytes);
            dokumentAnlegen(mandant.schema(), eingangId, "EINGANGSKORB", eingangPdfa, eingangPdfaQuelle,
                    eingangPdfaBytes, eingangOriginal, eingangOriginalQuelle, eingangOriginalBytes);

            AblageEinstellungen quellEinstellungen = new AblageEinstellungen(quellPraefix, Optional.of(Duration.ofDays(1)),
                    Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)), einstellungen.urlGueltigkeit(),
                    einstellungen.zwischenablage());
            AblagePort quellAblage = new S3Ablage(s3, S3Ablage.presigner(s3, true), quellEinstellungen,
                    Clock.systemUTC());
            SicherungBucketExport export = new SicherungBucketExport(quellAblage);
            List<SicherungBucketExport.InventarZeile> inventar = new ArrayList<>();
            export.inventar(mandant, inventar::add);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            export.zuwachs(mandant, ignoriert -> true, bytes);
            Path strom = temp.resolve("bucket.bin");
            java.nio.file.Files.write(strom, bytes.toByteArray());

            var zielEinstellungen = new AblageEinstellungen(zielPraefix, Optional.empty(), Sperrmodus.COMPLIANCE,
                    Optional.empty(), einstellungen.urlGueltigkeit(), einstellungen.zwischenablage());
            S3Wiederherstellung wiederherstellungsZiel = new S3Wiederherstellung(s3, zielEinstellungen);
            Wiederherstellung.Ergebnis ergebnis;
            try (var thread = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                ergebnis = thread.submit(() -> new Wiederherstellung(wiederherstellungsZiel, Clock.systemUTC(), referenz)
                        .wiederherstellen(mandant, inventar, strom, false)).get();
            }

            assertThat(ergebnis.exitCode()).isZero();
            assertThat(dokumentIds(mandant.schema(), dokumente)).containsExactlyInAnyOrderElementsOf(dokumente);
            assertDatei(zielBucket, mandant.schema(), archivId, archivOriginal, archivOriginalBytes, false, verwaltung);
            assertDatei(zielBucket, mandant.schema(), archivId, archivPdfa, archivPdfaBytes, true, verwaltung);
            assertDatei(zielBucket, mandant.schema(), eingangId, eingangOriginal, eingangOriginalBytes, false, verwaltung);
            assertDatei(zielBucket, mandant.schema(), eingangId, eingangPdfa, eingangPdfaBytes, true, verwaltung);

            var zielAblage = new S3Ablage(s3, S3Ablage.presigner(s3, true), zielEinstellungen, Clock.systemUTC());
            assertThat(zielAblage.lesenManifest(mandant, java.time.LocalDate.of(2026, 1, 1), ManifestTeil.JSON))
                    .get().extracting(d -> d.inhalt()).isEqualTo(manifestBytes);
        } finally {
            datenLoeschen(mandant.schema(), dokumente);
            if (zielAngelegt) {
                TestBucket.aufraeumen(verwaltung, zielBucket);
            }
            if (quelleAngelegt) {
                TestBucket.aufraeumen(verwaltung, quellBucket);
            }
            verwaltung.close();
        }
    }

    private String ablegen(String bucket, String schluessel, byte[] bytes) {
        return s3.putObject(r -> r.bucket(bucket).key(schluessel), RequestBody.fromBytes(bytes)).versionId();
    }

    private void mandantSicherstellen() throws SQLException {
        boolean vorhanden;
        try (Connection v = datenquelle.getConnection();
             PreparedStatement p = v.prepareStatement("select 1 from public.mandant where code = 'urinet'");
             ResultSet r = p.executeQuery()) {
            vorhanden = r.next();
        }
        if (!vorhanden) {
            provisionierung.anlegen("urinet", null, null);
        }
    }

    private void dokumentAnlegen(String schema, UUID id, String status, String pdfaSchluessel, String pdfaVersion,
                                 byte[] pdfaBytes, String originalSchluessel, String originalVersion,
                                 byte[] originalBytes) throws SQLException {
        try (Connection v = datenquelle.getConnection()) {
            try (PreparedStatement p = v.prepareStatement("insert into " + schema
                    + ".dokument (id, quelle, erstellt_von, status, s3_pdfa, s3_version_pdfa, sha256_pdfa) "
                    + "values (?, 'UPLOAD', 'restore-test', ?, ?, ?, ?)")) {
                p.setObject(1, id);
                p.setString(2, status);
                p.setString(3, pdfaSchluessel);
                p.setString(4, pdfaVersion);
                p.setString(5, Pruefsumme.von(pdfaBytes));
                p.executeUpdate();
            }
            try (PreparedStatement p = v.prepareStatement("insert into " + schema
                    + ".dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, s3_version) "
                    + "values (?, 1, 'ORIGINAL', ?, ?, ?)")) {
                p.setObject(1, id);
                p.setString(2, Pruefsumme.von(originalBytes));
                p.setString(3, originalSchluessel);
                p.setString(4, originalVersion);
                p.executeUpdate();
            }
        }
    }

    private List<UUID> dokumentIds(String schema, List<UUID> ids) throws SQLException {
        try (Connection v = datenquelle.getConnection();
             PreparedStatement p = v.prepareStatement("select id from " + schema
                     + ".dokument where id in (?, ?) order by id")) {
            p.setObject(1, ids.getFirst());
            p.setObject(2, ids.getLast());
            try (ResultSet r = p.executeQuery()) {
                List<UUID> ergebnis = new ArrayList<>();
                while (r.next()) {
                    ergebnis.add(r.getObject(1, UUID.class));
                }
                return ergebnis;
            }
        }
    }

    private void assertDatei(String bucket, String schema, UUID id, String schluessel, byte[] erwartet, boolean pdfa,
                             S3Client verwaltung) throws SQLException, java.io.IOException {
        String version = pdfa ? pdfaVersion(schema, id) : dateiVersion(schema, id, schluessel);
        try (var objekt = verwaltung.getObject(r -> r.bucket(bucket).key(schluessel).versionId(version))) {
            byte[] erhalten = objekt.readAllBytes();
            assertThat(Pruefsumme.von(erhalten)).isEqualTo(Pruefsumme.von(erwartet));
            assertThat(erhalten).containsExactly(erwartet);
        }
    }

    private String dateiVersion(String schema, UUID id, String schluessel) throws SQLException {
        try (Connection v = datenquelle.getConnection();
             PreparedStatement p = v.prepareStatement("select s3_version from " + schema
                     + ".dokument_datei where dokument_id = ? and s3_schluessel = ?")) {
            p.setObject(1, id);
            p.setString(2, schluessel);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getString(1);
            }
        }
    }

    private String pdfaVersion(String schema, UUID id) throws SQLException {
        try (Connection v = datenquelle.getConnection();
             PreparedStatement p = v.prepareStatement("select s3_version_pdfa from " + schema + ".dokument where id = ?")) {
            p.setObject(1, id);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getString(1);
            }
        }
    }

    private void datenLoeschen(String schema, List<UUID> ids) {
        try (Connection v = datenquelle.getConnection()) {
            try (PreparedStatement p = v.prepareStatement("delete from " + schema
                    + ".dokument_datei where dokument_id in (?, ?)")) {
                p.setObject(1, ids.getFirst());
                p.setObject(2, ids.getLast());
                p.executeUpdate();
            }
            try (PreparedStatement p = v.prepareStatement("delete from " + schema
                    + ".dokument where id in (?, ?)")) {
                p.setObject(1, ids.getFirst());
                p.setObject(2, ids.getLast());
                p.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Synthetische Testdokumente konnten nicht entfernt werden", e);
        }
    }
}
