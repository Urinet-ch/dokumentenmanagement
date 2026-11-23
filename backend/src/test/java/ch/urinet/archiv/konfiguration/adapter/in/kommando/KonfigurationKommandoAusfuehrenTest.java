package ch.urinet.archiv.konfiguration.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 05, Aufgabe 17.7: Das Admin-Kommando {@code mandant konfiguration …} mit der echten Bean (Review PR #325, S1).
 * Es läuft wie im Wegwerf-Container ohne Anmeldung in einem eigenen Thread ohne Request-Kontext.
 */
@QuarkusTest
class KonfigurationKommandoAusfuehrenTest {

    private static final String MANDANT = "probe-konfig-befehl";
    private static final String SCHEMA = new MandantCode(MANDANT).schema();

    @Inject
    KonfigurationKommando kommando;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Inject
    ObjectMapper json;

    @TempDir
    Path ordner;

    private final ByteArrayOutputStream aus = new ByteArrayOutputStream();
    private final ByteArrayOutputStream fehler = new ByteArrayOutputStream();

    @BeforeEach
    void mandant() throws SQLException {
        if (werte("select code from public.mandant where code = ?", MANDANT).isEmpty()) {
            provisionierung.anlegen(MANDANT, null, null);
        }
    }

    @Test
    void exportierenUndUnveraendertEinspielenAendertNichts() throws Exception {
        Path datei = ordner.resolve("konfiguration.json");
        assertThat(ausfuehren("exportieren", MANDANT, datei.toString())).isZero();
        if (datei.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(datei))).isEqualTo("rw-------");
        }
        int versionen = anzahlVersionen();

        assertThat(ausfuehren("pruefen", MANDANT, datei.toString())).isZero();
        assertThat(ausfuehren("vergleichen", MANDANT, datei.toString(), "--json")).isZero();
        assertThat(json.readTree(ausgabe()).path("gueltig").asBoolean()).isTrue();
        assertThat(ausfuehren("einspielen", MANDANT, datei.toString(), "--wer", "probe", "--grund", "unverändert"))
                .isZero();
        assertThat(anzahlVersionen()).isEqualTo(versionen);
    }

    @Test
    void exportUeberschreibtKeineDatei() throws Exception {
        Path datei = ordner.resolve("vorhanden.json");
        Files.writeString(datei, "alt");
        assertThat(ausfuehren("exportieren", MANDANT, datei.toString())).isEqualTo(3);
        assertThat(Files.readString(datei)).isEqualTo("alt");
        assertThat(fehlerausgabe()).contains("existiert schon");
    }

    /** M3: Version und Zugriffsprotokoll tragen {@code betreiber:<name>} und den Grund. Req 6.4: veraltete Basis → 1. */
    @Test
    void einspielenProtokolliertBetreiberUndLehntVeralteteBasisAb() throws Exception {
        Path datei = ordner.resolve("export.json");
        assertThat(ausfuehren("exportieren", MANDANT, datei.toString())).isZero();
        ObjectNode inhalt = (ObjectNode) json.readTree(datei.toFile());
        ObjectNode art = (ObjectNode) inhalt.withArray("arten").get(0);
        art.put("name", art.path("name").asText() + " " + System.nanoTime());
        Path geaendert = ordner.resolve("geaendert.json");
        Files.writeString(geaendert, json.writeValueAsString(inhalt));
        int versionen = anzahlVersionen();

        assertThat(ausfuehren("einspielen", MANDANT, geaendert.toString(), "--wer", "probe-betreiber",
                "--grund", "Kommandotest", "--json")).isZero();
        assertThat(anzahlVersionen()).isEqualTo(versionen + 1);
        assertThat(werte("select wer || '|' || grund || '|' || quelle from " + SCHEMA
                + ".konfiguration_version order by version desc limit 1"))
                .containsExactly("betreiber:probe-betreiber|Kommandotest|DATEI");
        assertThat(werte("select benutzer || '|' || (details->>'grund') from " + SCHEMA
                + ".zugriff_protokoll where aktion = 'KONFIGURATION_AENDERN' order by id desc limit 1"))
                .containsExactly("betreiber:probe-betreiber|Kommandotest");

        assertThat(ausfuehren("einspielen", MANDANT, geaendert.toString(), "--wer", "probe-betreiber",
                "--grund", "noch einmal", "--json")).isEqualTo(1);
        assertThat(fehlerausgabe()).contains("KONFIGURATION_VERALTET");
        assertThat(json.readTree(ausgabe()).path("code").asText()).isEqualTo("KONFIGURATION_VERALTET");
        assertThat(anzahlVersionen()).isEqualTo(versionen + 1);
    }

    @Test
    void befundeBeiPruefenUndVergleichenOhneAenderung() throws Exception {
        Path datei = ordner.resolve("export.json");
        assertThat(ausfuehren("exportieren", MANDANT, datei.toString())).isZero();
        ObjectNode inhalt = (ObjectNode) json.readTree(datei.toFile());
        ((ObjectNode) inhalt.get("einstellungen")).put("geschaeftsjahrEnde", "02-30");
        Path ungueltig = ordner.resolve("ungueltig.json");
        Files.writeString(ungueltig, json.writeValueAsString(inhalt));
        int versionen = anzahlVersionen();

        assertThat(ausfuehren("pruefen", MANDANT, ungueltig.toString(), "--json")).isEqualTo(1);
        JsonNode analyse = json.readTree(ausgabe());
        assertThat(analyse.path("gueltig").asBoolean()).isFalse();
        assertThat(analyse.path("befunde").toString()).contains("DATUM_UNGUELTIG");
        assertThat(ausfuehren("vergleichen", MANDANT, ungueltig.toString())).isEqualTo(1);
        assertThat(ausfuehren("einspielen", MANDANT, ungueltig.toString(), "--wer", "probe", "--grund", "x",
                "--json")).isEqualTo(1);
        assertThat(json.readTree(ausgabe()).path("code").asText()).isEqualTo("BESTAETIGUNG_NOETIG");
        assertThat(ausfuehren("einspielen", MANDANT, ungueltig.toString(), "--wer", "probe", "--grund", "x",
                "--bestaetigt", "geschaeftsjahrEnde", "--json")).isEqualTo(1);
        assertThat(json.readTree(ausgabe()).path("befunde")).isNotEmpty();
        assertThat(anzahlVersionen()).isEqualTo(versionen);
    }

    @Test
    void riskanteDateiBrauchtAusdruecklicheBestaetigungAuchImKommando() throws Exception {
        Path datei = ordner.resolve("export.json");
        assertThat(ausfuehren("exportieren", MANDANT, datei.toString())).isZero();
        ObjectNode inhalt = (ObjectNode) json.readTree(datei.toFile());
        ObjectNode einstellungen = (ObjectNode) inhalt.get("einstellungen");
        String neu = einstellungen.path("geschaeftsjahrEnde").asText().equals("06-30") ? "12-31" : "06-30";
        einstellungen.put("geschaeftsjahrEnde", neu);
        Path geaendert = ordner.resolve("riskant.json");
        Files.writeString(geaendert, json.writeValueAsString(inhalt));
        int versionen = anzahlVersionen();

        assertThat(ausfuehren("einspielen", MANDANT, geaendert.toString(), "--wer", "probe",
                "--grund", "Test", "--json")).isEqualTo(1);
        assertThat(json.readTree(ausgabe()).path("code").asText()).isEqualTo("BESTAETIGUNG_NOETIG");
        assertThat(fehlerausgabe()).contains("geschaeftsjahrEnde");
        assertThat(anzahlVersionen()).isEqualTo(versionen);
        assertThat(ausfuehren("einspielen", MANDANT, geaendert.toString(), "--wer", "probe",
                "--grund", "Test", "--bestaetigt", "geschaeftsjahrEnde")).isZero();
        assertThat(anzahlVersionen()).isEqualTo(versionen + 1);
    }

    /** K4 (Review PR #325): Ein Seed darf die KI-Texte nicht freigeben; die Provisionierung bricht vor dem Seed ab. */
    @Test
    void seedMitKiFreigabeWirdAbgelehnt() throws Exception {
        String code = "probe-ki-freigabe";
        assertThatThrownBy(() -> provisionierung.anlegen(code, null, null))
                .isInstanceOfSatisfying(KonfigurationUngueltig.class, e -> assertThat(e.befunde())
                        .extracting(KonfigurationUngueltig.Befund::code).contains("KI_FREIGABE_UNGUELTIG"));
        assertThat(werte("select aktiv::text from public.mandant where code = ?", code)).containsExactly("false");
        assertThat(werte("select version::text from " + new MandantCode(code).schema()
                + ".konfiguration_version where quelle = ?", "SEED")).isEmpty();
    }

    /** S3: Die Grenze von 1 MiB gilt auch ohne bekannte Dateigrösse; gelesen werden höchstens 1 MiB + 1 Byte. */
    @Test
    void zuGrosseDateiWirdNichtGelesen() throws Exception {
        Path gross = ordner.resolve("gross.json");
        Files.write(gross, new byte[1024 * 1024 + 1]);
        assertThat(ausfuehren("pruefen", MANDANT, gross.toString())).isEqualTo(3);
        assertThat(fehlerausgabe()).contains("überschreitet 1 MiB");
    }

    private int ausfuehren(String... argumente) throws Exception {
        aus.reset();
        fehler.reset();
        FutureTask<Integer> aufgabe = new FutureTask<>(() -> kommando.ausfuehren(List.of(argumente),
                new PrintStream(aus, true, StandardCharsets.UTF_8), new PrintStream(fehler, true, StandardCharsets.UTF_8)));
        // Plattform-Thread wie das Kommando (ArchivMain): Die Frist der Schema-Prüfung misst dort CPU-Zeit, in einem
        // virtuellen Thread die Wanduhr mit jedem Warten (MandantProvisionierungTestadapter)
        Thread.ofPlatform().start(aufgabe);
        return aufgabe.get();
    }

    private String ausgabe() {
        return aus.toString(StandardCharsets.UTF_8);
    }

    private String fehlerausgabe() {
        return fehler.toString(StandardCharsets.UTF_8);
    }

    private int anzahlVersionen() throws SQLException {
        return Integer.parseInt(werte("select count(*) from " + SCHEMA + ".konfiguration_version").getFirst());
    }

    private List<String> werte(String sql, String... parameter) throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                abfrage.setString(i + 1, parameter[i]);
            }
            List<String> ergebnis = new ArrayList<>();
            try (ResultSet zeilen = abfrage.executeQuery()) {
                while (zeilen.next()) {
                    ergebnis.add(zeilen.getString(1));
                }
            }
            return ergebnis;
        }
    }
}
