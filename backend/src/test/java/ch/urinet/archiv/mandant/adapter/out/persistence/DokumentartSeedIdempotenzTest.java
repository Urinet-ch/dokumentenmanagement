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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seed ist idempotent (Req 9.2): zweimal einspielen ergibt dieselben Arten, und eine seither geänderte Art überschreibt
 * der Seed nie still ({@code on conflict (code) do nothing}).
 */
@QuarkusTest
class DokumentartSeedIdempotenzTest {

    private static final String SCHEMA = "m_probe_seed_idem";

    @Inject
    MandantDatenbank datenbank;

    @AfterEach
    void aufraeumen() throws SQLException {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + SCHEMA + " cascade");
            befehl.execute("drop schema if exists m_probe_seed_abbruch cascade");
        }
    }

    @Test
    void abbruchNachErsterArtRolltZurueckUndWiederholungBleibtMandantengetrennt() throws SQLException {
        String zweitesSchema = "m_probe_seed_abbruch";
        datenbank.schemaAnlegen(SCHEMA);
        datenbank.schemaAnlegen(zweitesSchema);
        datenbank.dokumentartenEinspielen(zweitesSchema, List.of(art("SEED_A", "Anderer Mandant")));
        var ungueltig = new DokumentartVorlage("SEED_B", "B", null, List.of(), List.of(), null, "UNGUELTIG",
                null, null, false, true);

        assertThatThrownBy(() -> datenbank.dokumentartenEinspielen(SCHEMA,
                List.of(art("SEED_A", "A"), ungueltig)))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(SQLException.class);

        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".dokumentart")).isEqualTo("0");
            assertThat(wert(befehl, "select name from " + zweitesSchema + ".dokumentart"))
                    .isEqualTo("Anderer Mandant");
            var fehlenderVerweis = new DokumentartVorlage("SEED_B", "B", null, List.of(), List.of(), null,
                    "NORMAL", null, null, false, true, false, List.of(), "FEHLT", null, true, List.of(), null, 0);
            assertThatThrownBy(() -> datenbank.dokumentartenEinspielen(SCHEMA,
                    List.of(art("SEED_A", "A"), fehlenderVerweis)))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(SQLException.class);
            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".dokumentart")).isEqualTo("0");
            datenbank.dokumentartenEinspielen(SCHEMA, List.of(art("SEED_A", "A"), art("SEED_B", "B")));
            datenbank.dokumentartenEinspielen(SCHEMA, List.of(art("SEED_A", "A"), art("SEED_B", "B")));
            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".dokumentart")).isEqualTo("2");
            assertThat(wert(befehl, "select name from " + zweitesSchema + ".dokumentart"))
                    .isEqualTo("Anderer Mandant");
        }
    }

    @Test
    void erneutesEinspielenAendertWederZahlNochGeaenderteArt() throws SQLException {
        var seed = List.of(art("SEED_A", "Seed A"), art("SEED_B", "Seed B"));
        datenbank.schemaAnlegen(SCHEMA);
        datenbank.dokumentartenEinspielen(SCHEMA, seed);
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("update " + SCHEMA + ".dokumentart set name = 'Geändert', aktiv = false where code = 'SEED_A'");

            datenbank.dokumentartenEinspielen(SCHEMA, seed);

            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".dokumentart")).isEqualTo("2");
            assertThat(wert(befehl, "select name from " + SCHEMA + ".dokumentart where code = 'SEED_A'")).isEqualTo("Geändert");
            assertThat(wert(befehl, "select aktiv from " + SCHEMA + ".dokumentart where code = 'SEED_A'")).isEqualTo("f");
            assertThat(wert(befehl, "select name from " + SCHEMA + ".dokumentart where code = 'SEED_B'")).isEqualTo("Seed B");
        }
    }

    private static DokumentartVorlage art(String code, String name) {
        return new DokumentartVorlage(code, name, null, List.of(), List.of(), null, "NORMAL", null, null, false, true);
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
