package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.application.WiederherstellungsReferenz;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kommando {@code festhalten} und Mandantenprüfung über Register und {@code JdbcSicherungExportSpeicher} gegen die
 * echte Datenbank (Aufgabe 5.5b; Req 16.2, 16.8). {@code sicherung_export} ist append-only, darum hat jeder Test eine
 * eigene Prüfsumme und zählt nur seine Zeilen.
 */
@QuarkusTest
class SicherungKommandoDbTest {

    private static final String AKTIV = "urinet";
    private static final String INAKTIV = "vhf-gsk";

    @Inject
    SicherungKommando kommando;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    SicherungExportSpeicher exportSpeicher;

    @Inject
    WiederherstellungsReferenz wiederherstellungsReferenz;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Test
    void festhaltenSchreibtZeilenMitMandantUndGlobal() throws SQLException {
        vorhanden(AKTIV);
        String bucket = sha();
        String inventar = sha();
        String global = sha();

        assertThat(aufruf("festhalten", "--art", "bucket", "--mandant", AKTIV, "--sha256", bucket)).isZero();
        assertThat(aufruf("festhalten", "--art", "inventar", "--mandant", AKTIV, "--sha256", inventar)).isZero();
        assertThat(aufruf("festhalten", "--art", "db", "--sha256", global.toUpperCase())).isZero();

        assertThat(zeile(bucket)).containsExactly("bucket", AKTIV);
        assertThat(zeile(inventar)).containsExactly("inventar", AKTIV);
        assertThat(zeile(global)).containsExactly("db", null);
    }

    @Test
    void unbekannterOderInaktiverMandantGibtExitZweiOhneZeile() throws SQLException {
        vorhanden(INAKTIV);
        aktiv(INAKTIV, false);
        try {
            for (String code : List.of(INAKTIV, "gibt-es-nicht")) {
                for (String art : List.of("bucket", "inventar")) {
                    String sha = sha();
                    assertThat(aufruf("festhalten", "--art", art, "--mandant", code, "--sha256", sha))
                            .as(art + " " + code).isEqualTo(2);
                    assertThat(zeile(sha)).as(art + " " + code).isEmpty();
                }
                assertThat(aufruf("inventar", "--mandant", code)).as("inventar " + code).isEqualTo(2);
                assertThat(aufruf("zuwachs", "--mandant", code)).as("zuwachs " + code).isEqualTo(2);
            }
        } finally {
            aktiv(INAKTIV, true);
        }
    }

    @Test
    void liestDenJuengstenZeitpunktGetrenntNachArtUndMandant() throws SQLException, InterruptedException {
        vorhanden(AKTIV);
        vorhanden(INAKTIV);
        MandantCode aktiv = new MandantCode(AKTIV);
        MandantCode anderer = new MandantCode(INAKTIV);

        exportSpeicher.festhalten(SicherungsArt.BUCKET, Optional.of(aktiv), sha());
        Instant erster = exportSpeicher.juengsterEintrag(SicherungsArt.BUCKET, Optional.of(aktiv)).orElseThrow();
        Thread.sleep(10);
        exportSpeicher.festhalten(SicherungsArt.BUCKET, Optional.of(aktiv), sha());
        Instant juengster = exportSpeicher.juengsterEintrag(SicherungsArt.BUCKET, Optional.of(aktiv)).orElseThrow();

        Thread.sleep(10);
        exportSpeicher.festhalten(SicherungsArt.BUCKET, Optional.of(anderer), sha());
        Instant andererMandant = exportSpeicher.juengsterEintrag(SicherungsArt.BUCKET, Optional.of(anderer))
                .orElseThrow();
        assertThat(juengster).isAfter(erster);
        assertThat(andererMandant).isAfter(juengster);
        assertThat(exportSpeicher.juengsterEintrag(SicherungsArt.BUCKET, Optional.of(aktiv)))
                .contains(juengster);

        Thread.sleep(10);
        exportSpeicher.festhalten(SicherungsArt.INVENTAR, Optional.of(aktiv), sha());
        Instant inventar = exportSpeicher.juengsterEintrag(SicherungsArt.INVENTAR, Optional.of(aktiv)).orElseThrow();
        Thread.sleep(10);
        exportSpeicher.festhalten(SicherungsArt.DB, Optional.empty(), sha());
        Instant datenbank = exportSpeicher.juengsterEintrag(SicherungsArt.DB, Optional.empty()).orElseThrow();

        assertThat(inventar).isAfter(juengster);
        assertThat(datenbank).isAfter(inventar);
        assertThat(exportSpeicher.juengsterEintrag(SicherungsArt.DB, Optional.of(aktiv))).isEmpty();
        assertThat(exportSpeicher.juengsterEintrag(SicherungsArt.BUCKET, Optional.empty())).isEmpty();
    }

    @Test
    void aktualisiertNachRestoreNurAktiveDokumentDateireferenzen() throws Exception {
        vorhanden(AKTIV);
        UUID id = UUID.randomUUID();
        String hash = sha();
        String dateiSchluessel = "archiv/restore-test/" + id + "/original.pdf";
        String eingangSchluessel = "eingang/2026/" + id + "/arbeitsfassung.pdf";
        String pdfaSchluessel = "archiv/restore-test/" + id + "/pdfa.pdf";
        String alteDateiVersion = "quelle-" + id;
        String neueDateiVersion = "ziel-" + id;
        String alteEingangVersion = "quelle-eingang-" + id;
        String neueEingangVersion = "ziel-eingang-" + id;
        String altePdfaVersion = "quelle-pdfa-" + id;
        String neuePdfaVersion = "ziel-pdfa-" + id;

        try {
            try (Connection v = datenbank.get().getConnection()) {
                try (PreparedStatement p = v.prepareStatement("insert into m_urinet.dokument "
                        + "(id, quelle, erstellt_von, status, s3_pdfa, s3_version_pdfa, sha256_pdfa) "
                        + "values (?, 'UPLOAD', 'restore-test', 'ARCHIVIERT', ?, ?, ?)")) {
                    p.setObject(1, id);
                    p.setString(2, pdfaSchluessel);
                    p.setString(3, altePdfaVersion);
                    p.setString(4, hash);
                    p.executeUpdate();
                }
                try (PreparedStatement p = v.prepareStatement("insert into m_urinet.dokument_datei "
                        + "(dokument_id, nr, rolle, sha256, s3_schluessel, s3_version) "
                        + "values (?, 1, 'ORIGINAL', ?, ?, ?)")) {
                    p.setObject(1, id);
                    p.setString(2, hash);
                    p.setString(3, dateiSchluessel);
                    p.setString(4, alteDateiVersion);
                    p.executeUpdate();
                }
                try (PreparedStatement p = v.prepareStatement("insert into m_urinet.dokument_datei "
                        + "(dokument_id, nr, rolle, sha256, s3_schluessel, s3_version) "
                        + "values (?, 1, 'ARBEITSFASSUNG', ?, ?, ?)")) {
                    p.setObject(1, id);
                    p.setString(2, hash);
                    p.setString(3, eingangSchluessel);
                    p.setString(4, alteEingangVersion);
                    p.executeUpdate();
                }
            }
            MandantCode mandant = new MandantCode(AKTIV);
            try (var thread = Executors.newSingleThreadExecutor()) {
                thread.submit(() -> {
                    wiederherstellungsReferenz.versionErsetzt(mandant, dateiSchluessel, alteDateiVersion, neueDateiVersion);
                    wiederherstellungsReferenz.versionErsetzt(mandant, eingangSchluessel, alteEingangVersion,
                            neueEingangVersion);
                    wiederherstellungsReferenz.versionErsetzt(mandant, pdfaSchluessel, altePdfaVersion, neuePdfaVersion);
                    wiederherstellungsReferenz.versionErsetzt(mandant, "manifest/restore.json", "alt", "neu");
                }).get();
            }

            assertThat(dateiVersion(id, dateiSchluessel)).isEqualTo(neueDateiVersion);
            assertThat(dateiVersion(id, eingangSchluessel)).isEqualTo(neueEingangVersion);
            assertThat(pdfaVersion(id)).isEqualTo(neuePdfaVersion);
        } finally {
            try (Connection v = datenbank.get().getConnection()) {
                try (PreparedStatement p = v.prepareStatement("delete from m_urinet.dokument_datei where dokument_id = ?")) {
                    p.setObject(1, id);
                    p.executeUpdate();
                }
                try (PreparedStatement p = v.prepareStatement("delete from m_urinet.dokument where id = ?")) {
                    p.setObject(1, id);
                    p.executeUpdate();
                }
            }
        }
    }

    private String dateiVersion(UUID id, String schluessel) throws SQLException {
        try (Connection v = datenbank.get().getConnection();
             PreparedStatement p = v.prepareStatement(
                     "select s3_version from m_urinet.dokument_datei where dokument_id = ? and s3_schluessel = ?")) {
            p.setObject(1, id);
            p.setString(2, schluessel);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getString(1);
            }
        }
    }

    private String pdfaVersion(UUID id) throws SQLException {
        try (Connection v = datenbank.get().getConnection();
             PreparedStatement p = v.prepareStatement("select s3_version_pdfa from m_urinet.dokument where id = ?")) {
            p.setObject(1, id);
            try (ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getString(1);
            }
        }
    }

    private int aufruf(String... argumente) {
        return kommando.ausfuehren(List.of(argumente),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private static String sha() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private void vorhanden(String code) throws SQLException {
        try (Connection v = datenbank.get().getConnection();
             PreparedStatement p = v.prepareStatement("select 1 from public.mandant where code = ?")) {
            p.setString(1, code);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    provisionierung.anlegen(code, null, null);
                }
            }
        }
    }

    private void aktiv(String code, boolean aktiv) throws SQLException {
        try (Connection v = datenbank.get().getConnection();
             PreparedStatement p = v.prepareStatement("update public.mandant set aktiv = ? where code = ?")) {
            p.setBoolean(1, aktiv);
            p.setString(2, code);
            assertThat(p.executeUpdate()).as("Seed-Mandant " + code).isEqualTo(1);
        }
    }

    /** Art und Mandant der Zeile mit dieser Prüfsumme; leer, wenn es keine gibt. */
    private List<String> zeile(String sha) throws SQLException {
        try (Connection v = datenbank.get().getConnection();
             PreparedStatement p = v.prepareStatement("select art, mandant from public.sicherung_export where sha256 = ?")) {
            p.setString(1, sha);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    return List.of();
                }
                var ergebnis = new java.util.ArrayList<String>();
                ergebnis.add(r.getString(1));
                ergebnis.add(r.getString(2));
                assertThat(r.next()).as("genau eine Zeile").isFalse();
                return ergebnis;
            }
        }
    }
}
