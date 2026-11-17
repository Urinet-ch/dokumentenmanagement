package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein neuer Mandant bekommt nach dem Seed eine Version mit Quelle {@code SEED} (Spec 05, Req 1.1); ein zweiter Lauf
 * ohne Unterschied legt keine weitere an. Das Abbild trägt {@code zusatzSchema} auch aus jsonb als Text.
 */
@QuarkusTest
class KonfigurationSeedVersionTest {

    private static final String SCHEMA = "m_probe_konf_seed";

    @Inject
    MandantDatenbank datenbank;

    @AfterEach
    void aufraeumen() throws SQLException {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void seedErzeugtGenauEineVersionMitAbbildDerArten() throws SQLException {
        datenbank.schemaAnlegen(SCHEMA);
        datenbank.dokumentartenEinspielen(SCHEMA, List.of(
                new DokumentartVorlage("SEED_B", "B", null, List.of(), List.of(),
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}", "NORMAL", 10,
                        "GESCHAEFTSJAHR", false, true),
                new DokumentartVorlage("SEED_A", "A", null, List.of(), List.of(), null, "NORMAL", null, null, false,
                        true)));

        datenbank.konfigurationSeedAnlegen(SCHEMA);
        datenbank.konfigurationSeedAnlegen(SCHEMA);

        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".konfiguration_version")).isEqualTo("2");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 1"))
                    .isEqualTo("MIGRATION");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 2"))
                    .isEqualTo("SEED");
            assertThat(wert(befehl, "select inhalt #>> '{arten,0,code}' from " + SCHEMA
                    + ".konfiguration_version where version = 2")).isEqualTo("SEED_A");
            assertThat(wert(befehl, "select jsonb_typeof(inhalt #> '{arten,1,zusatzSchema}') from " + SCHEMA
                    + ".konfiguration_version where version = 2")).isEqualTo("string");
            assertThat(wert(befehl, "select jsonb_typeof(inhalt #> '{arten,0,gruppen}') from " + SCHEMA
                    + ".konfiguration_version where version = 2")).isEqualTo("array");
        }
    }

    private static Connection verbinden() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static String wert(Statement befehl, String sql) throws SQLException {
        try (ResultSet ergebnis = befehl.executeQuery(sql)) {
            ergebnis.next();
            return ergebnis.getString(1);
        }
    }
}
