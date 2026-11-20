package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein neuer Mandant bekommt nach dem Seed eine Version mit Quelle {@code SEED} (Spec 05, Req 1.1); ein zweiter Lauf
 * ohne Unterschied legt keine weitere an. Das Abbild trägt {@code zusatzSchema} auch aus jsonb als Text.
 */
@QuarkusTest
class KonfigurationSeedVersionTest {

    private static final String SCHEMA = "m_probe_konf_seed";
    private static final String MIGRATION_SCHEMA = "m_probe_konf_migration";

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

        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("update " + SCHEMA
                    + ".ki_texte set rolle = 'Synthetische Rolle', feldregeln = 'Synthetische Feldregeln', "
                    + "ki_freigegeben = true where id = 1");
            befehl.execute("insert into " + SCHEMA + ".regel (code, art, typ, parameter, schwere, meldung, aktiv) "
                    + "values ('seed_folge', 'SEED_A', 'FOLGE', "
                    + "'{\"feld\":\"geschaeftsjahr\",\"zeitraum\":\"GESAMT\"}', 'WARNUNG', 'Nummer fehlt', true)");
        }
        datenbank.konfigurationSeedAnlegen(SCHEMA);
        datenbank.konfigurationSeedAnlegen(SCHEMA);

        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".konfiguration_version")).isEqualTo("3");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 1"))
                    .isEqualTo("MIGRATION");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 2"))
                    .isEqualTo("MIGRATION");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 3"))
                    .isEqualTo("SEED");
            assertThat(wert(befehl, "select inhalt #>> '{arten,0,code}' from " + SCHEMA
                    + ".konfiguration_version where version = 3")).isEqualTo("SEED_A");
            assertThat(wert(befehl, "select jsonb_typeof(inhalt #> '{arten,1,zusatzSchema}') from " + SCHEMA
                    + ".konfiguration_version where version = 3")).isEqualTo("string");
            assertThat(wert(befehl, "select jsonb_typeof(inhalt #> '{arten,0,gruppen}') from " + SCHEMA
                    + ".konfiguration_version where version = 3")).isEqualTo("array");
            assertThat(wert(befehl, "select inhalt #>> '{regeln,0,code}' from " + SCHEMA
                    + ".konfiguration_version where version = 3")).isEqualTo("seed_folge");
            assertThat(wert(befehl, "select inhalt #>> '{kiTexte,kiFreigegeben}' from " + SCHEMA
                    + ".konfiguration_version where version = 3")).isEqualTo("true");
            // 048 lief ohne Arten; 054 hängt die migrierten KI-Texte an, danach folgt der Seed.
            assertThat(wert(befehl, "select inhalt -> 'kiTexte' is null from " + SCHEMA
                    + ".konfiguration_version where version = 1")).isEqualTo("t");
        }
    }

    @Test
    void changeset040UebernimmtVorhandeneArtenUndFuegtMigrationsabbildAn() throws Exception {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
            befehl.execute("create schema " + MIGRATION_SCHEMA);
            befehl.execute("set search_path to " + MIGRATION_SCHEMA);
            befehl.execute("""
                    create table dokumentart (
                        code text primary key, name text not null, beschreibung text, gruppen text[],
                        pflichtfelder text[], zusatz_schema jsonb, vertraulichkeit text not null,
                        aufbewahrung_jahre integer, aufbewahrung_ab text,
                        gesetzliche_pflicht boolean not null, aktiv boolean not null)
                    """);
            befehl.execute("""
                    create table konfiguration_version (
                        version integer primary key, wer text not null, grund text, quelle text not null,
                        inhalt jsonb not null, sha256 text not null)
                    """);
            befehl.execute("""
                    insert into dokumentart values ('PROBE_ALT', 'Probe alt', null, '{}', '{}',
                        '{"type":"object"}', 'NORMAL', 10, 'GESCHAEFTSJAHR', false, true)
                    """);
            befehl.execute("""
                    insert into konfiguration_version values (7, 'system:migration', null, 'MIGRATION',
                        '{"version":7,"marker":"vorher"}', 'alter-hash')
                    """);
            liquibase040(verbindung, MIGRATION_SCHEMA);
            verbindung.commit();

            assertThat(wert(befehl, "select count(*) from konfiguration_version")).isEqualTo("2");
            assertThat(wert(befehl, "select inhalt ->> 'marker' from konfiguration_version where version = 7"))
                    .isEqualTo("vorher");
            assertThat(wert(befehl, "select quelle from konfiguration_version where version = 8"))
                    .isEqualTo("MIGRATION");
            assertThat(wert(befehl, """
                    select inhalt #>> '{arten,0,code}' from konfiguration_version where version = 8
                    """)).isEqualTo("PROBE_ALT");
            assertThat(wert(befehl, """
                    select inhalt #>> '{arten,0,selbstAusgestellt}' from konfiguration_version where version = 8
                    """)).isEqualTo("false");
            assertThat(wert(befehl, """
                    select jsonb_typeof(inhalt #> '{arten,0,belegnummerMuster}')
                    from konfiguration_version where version = 8
                    """)).isEqualTo("array");
            assertThat(wert(befehl, "select name from dokumentart where code = 'PROBE_ALT'")).isEqualTo("Probe alt");
        } finally {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
            }
        }
    }

    /**
     * Datenerhalt und Muster des Changesets in einer eigenen, kurzlebigen Datenbank: Nur dort darf das Schema
     * {@code m_urinet} samt Zeile in {@code public.mandant} ohne Berührung der echten Daten angelegt werden.
     */
    @Test
    void changesets040Und046UebernehmenAusstellerMusterUndRegelnFuerUrinet() throws Exception {
        String name = "probe_konf_migration_" + Long.toHexString(System.nanoTime());
        try (Connection verwaltung = verbinden(null); Statement befehl = verwaltung.createStatement()) {
            befehl.execute("create database " + name);
        }
        try {
            try (Connection verbindung = verbinden(name); Statement befehl = verbindung.createStatement()) {
                befehl.execute("""
                        create table public.mandant (code text, schema_name text, eigene_arten text[],
                            geschaeftsjahr_ende text, kennungen jsonb)
                        """);
                befehl.execute("""
                        insert into public.mandant values ('urinet', 'm_urinet', array['AUSGANGSRECHNUNG','OFFERTE','X'],
                            '12-31', '{"uid":"CHE-1","ibans":["CH1"]}')
                        """);
                befehl.execute("create schema m_urinet");
                befehl.execute("set search_path to m_urinet");
                befehl.execute("""
                        create table dokumentart (
                            code text primary key, name text not null, beschreibung text, gruppen text[],
                            pflichtfelder text[], zusatz_schema jsonb, vertraulichkeit text not null,
                            aufbewahrung_jahre integer, aufbewahrung_ab text,
                            gesetzliche_pflicht boolean not null, aktiv boolean not null)
                        """);
                befehl.execute("""
                        create table konfiguration_version (
                            version integer primary key, wer text not null, grund text, quelle text not null,
                            inhalt jsonb not null, sha256 text not null)
                        """);
                for (String code : List.of("AUSGANGSRECHNUNG", "OFFERTE", "LIEFERANTENBELEG", "QUITTUNG",
                        "WISSENSQUELLE", "LOHNABRECHNUNG", "BANK", "BEHOERDE", "KORRESPONDENZ", "PROJEKTDOKUMENT",
                        "VERSICHERUNG", "VERTRAG", "X")) {
                    befehl.execute("insert into dokumentart values ('" + code + "', '" + code
                            + "', null, '{}', '{}', '{\"type\":\"object\"}', 'NORMAL', 10, 'GESCHAEFTSJAHR', false, true)");
                }
                liquibase040(verbindung, "m_urinet");
                verbindung.commit();

                // eigene_arten wird übernommen (nicht die Standardliste), auch eine Art ausserhalb der Standardliste
                assertThat(wert(befehl, "select string_agg(code, ',' order by code) from dokumentart "
                        + "where selbst_ausgestellt")).isEqualTo("AUSGANGSRECHNUNG,OFFERTE,X");
                assertThat(wert(befehl, "select array_to_string(belegnummer_muster, '|') from dokumentart "
                        + "where code = 'AUSGANGSRECHNUNG'")).isEqualTo("^\\d{4}-\\d{3}$|^R\\d{4}$");
                assertThat(wert(befehl, "select array_to_string(belegnummer_muster, '|') from dokumentart where code = 'OFFERTE'"))
                        .isEqualTo("^O-\\d{4}-\\d{3}$");
                assertThat(wert(befehl, "select array_to_string(belegnummer_muster, '|') from dokumentart "
                        + "where code = 'LIEFERANTENBELEG'")).isEqualTo("^F\\d{4}$");
                assertThat(wert(befehl, "select fremdbeleg_art from dokumentart where code = 'AUSGANGSRECHNUNG'"))
                        .isEqualTo("LIEFERANTENBELEG");
                assertThat(wert(befehl, "select fremdbeleg_art from dokumentart where code = 'OFFERTE'"))
                        .isEqualTo("LIEFERANTENBELEG");
                assertThat(wert(befehl, "select ohne_zahlungsdaten_art from dokumentart "
                        + "where code = 'LIEFERANTENBELEG'")).isEqualTo("QUITTUNG");
                assertThat(wert(befehl, "select ki_vorschlag::text from dokumentart where code = 'WISSENSQUELLE'"))
                        .isEqualTo("false");
                assertThat(wert(befehl, "select count(*) from dokumentart where ki_vorschlag")).isEqualTo("12");
                // Abbild der Migration trägt dieselben Werte und die Mandanten-Einstellungen
                assertThat(wert(befehl, "select inhalt #>> '{einstellungen,kennungen,uid}' from konfiguration_version"))
                        .isEqualTo("CHE-1");
                assertThat(wert(befehl, "select count(*) from konfiguration_version, "
                        + "jsonb_array_elements(inhalt -> 'arten') a where (a ->> 'selbstAusgestellt')::boolean"))
                        .isEqualTo("3");
                assertThat(wert(befehl, "select a ->> 'fremdbelegArt' from konfiguration_version, "
                        + "jsonb_array_elements(inhalt -> 'arten') a where a ->> 'code' = 'OFFERTE'"))
                        .isEqualTo("LIEFERANTENBELEG");

                liquibase(verbindung, "m_urinet", "046-regel.xml");
                verbindung.commit();

                assertThat(wert(befehl, "select array_to_string(belegnummer_muster, '|') from dokumentart "
                        + "where code = 'AUSGANGSRECHNUNG'"))
                        .isEqualTo("^(?<jahr>\\d{4})-(?<nr>\\d{3})$|^R(?<nr>\\d{4})$");
                assertThat(wert(befehl, "select inhalt #>> '{arten,0,belegnummerMuster,0}' "
                        + "from konfiguration_version where version = 1")).isEqualTo("^\\d{4}-\\d{3}$");
                assertThat(wert(befehl, "select quelle from konfiguration_version where version = 2"))
                        .isEqualTo("MIGRATION");
                assertThat(wert(befehl, "select inhalt #>> '{arten,0,belegnummerMuster,0}' "
                        + "from konfiguration_version where version = 2"))
                        .isEqualTo("^(?<jahr>\\d{4})-(?<nr>\\d{3})$");
                assertThat(wert(befehl, "select count(*) from regel where code = 'ausgangsrechnung_folge'"))
                        .isEqualTo("1");
                assertThat(wert(befehl, "select count(*) from konfiguration_version v, "
                        + "jsonb_array_elements(v.inhalt -> 'regeln') r where v.version = 2 "
                        + "and r ->> 'code' = 'ausgangsrechnung_folge'")).isEqualTo("1");
                assertThat(wert(befehl, """
                        select r -> 'parameter' ->> 'nurMonat'
                        from konfiguration_version v, jsonb_array_elements(v.inhalt -> 'regeln') r
                        where v.version = 2 and r ->> 'code' = 'lohnabrechnung_erwartet'
                        """)).isEqualTo("zusatz.periode");
                assertThat(wert(befehl, """
                        select string_agg(r ->> 'code' || '=' || (r -> 'parameter' ->> 'gruppe'), ',' order by r ->> 'code')
                        from konfiguration_version v, jsonb_array_elements(v.inhalt -> 'regeln') r
                        where v.version = 2 and r ->> 'code' in (
                            'lohnabrechnung_erwartet', 'bankbeleg_erwartet')
                        """)).isEqualTo("bankbeleg_erwartet=PARTNER,lohnabrechnung_erwartet=zusatz.person");
                assertThat(wert(befehl, "select (sha256 = encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')) "
                        + "from konfiguration_version where version = 2")).isEqualTo("t");

                // 048: Abbild ohne kiTexte erhält die Freigabe aus ki_texte (Req 6.6, 8.4)
                liquibase(verbindung, "m_urinet", "048-ki-texte.xml");
                verbindung.commit();

                assertThat(wert(befehl, "select max(version) from konfiguration_version")).isEqualTo("3");
                assertThat(wert(befehl, "select quelle from konfiguration_version where version = 3"))
                        .isEqualTo("MIGRATION");
                assertThat(wert(befehl, "select inhalt #>> '{kiTexte,kiFreigegeben}' "
                        + "from konfiguration_version where version = 3")).isEqualTo("true");
                assertThat(wert(befehl, "select (inhalt - 'version' - 'kiTexte') = (select inhalt - 'version' "
                        + "from konfiguration_version where version = 2) from konfiguration_version where version = 3"))
                        .isEqualTo("t");
                assertThat(wert(befehl, "select (sha256 = encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')) "
                        + "from konfiguration_version where version = 3")).isEqualTo("t");

                // 054 setzt in m_urinet die KI-Texte und Arten-Hinweise; sie müssen dem Seed entsprechen
                liquibase(verbindung, "m_urinet", "054-ki-texte-aus-seed.xml");
                verbindung.commit();

                JsonNode seed;
                try (var in = KonfigurationSeedVersionTest.class.getClassLoader()
                        .getResourceAsStream("seed/urinet/konfiguration.json")) {
                    seed = new ObjectMapper().readTree(in);
                }
                assertThat(wert(befehl, "select feldregeln from ki_texte where id = 1"))
                        .isEqualTo(seed.at("/kiTexte/feldregeln").asText());
                for (JsonNode art : seed.get("arten")) {
                    String code = art.get("code").asText();
                    if (code.equals("WISSENSQUELLE")) {
                        continue;
                    }
                    assertThat(wert(befehl, "select reihenfolge from dokumentart where code = '" + code + "'"))
                            .as("reihenfolge " + code).isEqualTo(art.get("reihenfolge").asText());
                    assertThat(wert(befehl, "select coalesce(ki_hinweise, '') from dokumentart where code = '"
                            + code + "'")).as("kiHinweise " + code).isEqualTo(art.path("kiHinweise").asText(""));
                }
                assertThat(wert(befehl, "select max(version) from konfiguration_version")).isEqualTo("4");
                assertThat(wert(befehl, "select quelle from konfiguration_version where version = 4"))
                        .isEqualTo("MIGRATION");
                assertThat(wert(befehl, "select inhalt #>> '{kiTexte,feldregeln}' from konfiguration_version "
                        + "where version = 4")).isEqualTo(seed.at("/kiTexte/feldregeln").asText());
                assertThat(wert(befehl, "select a ->> 'reihenfolge' from konfiguration_version, "
                        + "jsonb_array_elements(inhalt -> 'arten') a where version = 4 and a ->> 'code' = 'BANK'"))
                        .isEqualTo("5");
                assertThat(wert(befehl, "select (sha256 = encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')) "
                        + "from konfiguration_version where version = 4")).isEqualTo("t");
            }
        } finally {
            try (Connection verwaltung = verbinden(null); Statement befehl = verwaltung.createStatement()) {
                befehl.execute("drop database if exists " + name + " with (force)");
            }
        }
    }

    @Test
    void changeset054ErgaenztVhfGskTexteUndErhaeltEigeneArten() throws Exception {
        String name = "probe_vhf_migration_" + Long.toHexString(System.nanoTime());
        try (Connection verwaltung = verbinden(null); Statement befehl = verwaltung.createStatement()) {
            befehl.execute("create database " + name);
        }
        try {
            try (Connection verbindung = verbinden(name); Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema m_vhf_gsk");
                befehl.execute("set search_path to m_vhf_gsk");
                befehl.execute("create table ki_texte (id integer primary key, rolle text, feldregeln text,"
                        + " ki_freigegeben boolean)");
                befehl.execute("insert into ki_texte values (1, '', '', false)");
                befehl.execute("create table dokumentart (code text primary key, ki_hinweise text, reihenfolge integer)");
                befehl.execute("insert into dokumentart values ('PROBE', 'Eigener Hinweis', 7)");
                befehl.execute("create table konfiguration_version (version integer primary key, wer text,"
                        + " grund text, quelle text, inhalt jsonb, sha256 text)");
                befehl.execute("""
                        insert into konfiguration_version values (1, 'system:migration', null, 'MIGRATION',
                            '{"version":1,"arten":[{"code":"PROBE","kiHinweise":"Eigener Hinweis","reihenfolge":7}],
                            "kiTexte":{"rolle":"","feldregeln":"","kiFreigegeben":false}}', 'alt')
                        """);
                liquibase(verbindung, "m_vhf_gsk", "054-ki-texte-aus-seed.xml");
                verbindung.commit();

                assertThat(wert(befehl, "select feldregeln from ki_texte where id = 1"))
                        .contains("Mandantenhinweise zur Einordnung:", "Reihe; Ausgabe-Nr.; Erscheinungsjahr; Fachgebiet");
                assertThat(wert(befehl, "select ki_freigegeben::text from ki_texte where id = 1")).isEqualTo("false");
                assertThat(wert(befehl, "select ki_hinweise || ':' || reihenfolge from dokumentart"))
                        .isEqualTo("Eigener Hinweis:7");
                assertThat(wert(befehl, "select quelle from konfiguration_version where version = 2"))
                        .isEqualTo("MIGRATION");
                assertThat(wert(befehl, "select inhalt #>> '{kiTexte,feldregeln}' from konfiguration_version "
                        + "where version = 2")).isEqualTo(wert(befehl, "select feldregeln from ki_texte where id = 1"));
                assertThat(wert(befehl, "select inhalt #>> '{arten,0,kiHinweise}' from konfiguration_version "
                        + "where version = 2")).isEqualTo("Eigener Hinweis");
                assertThat(wert(befehl, "select (sha256 = encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')) "
                        + "from konfiguration_version where version = 2")).isEqualTo("t");
            }
        } finally {
            try (Connection verwaltung = verbinden(null); Statement befehl = verwaltung.createStatement()) {
                befehl.execute("drop database if exists " + name + " with (force)");
            }
        }
    }

    @Test
    void changeset040OhneMandantenzeileBehaeltDefaultsUndFuehrtWissensquelleOhneKi() throws Exception {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
            befehl.execute("create schema " + MIGRATION_SCHEMA);
            befehl.execute("set search_path to " + MIGRATION_SCHEMA);
            befehl.execute("""
                    create table dokumentart (
                        code text primary key, name text not null, beschreibung text, gruppen text[],
                        pflichtfelder text[], zusatz_schema jsonb, vertraulichkeit text not null,
                        aufbewahrung_jahre integer, aufbewahrung_ab text,
                        gesetzliche_pflicht boolean not null, aktiv boolean not null)
                    """);
            befehl.execute("""
                    create table konfiguration_version (
                        version integer primary key, wer text not null, grund text, quelle text not null,
                        inhalt jsonb not null, sha256 text not null)
                    """);
            befehl.execute("""
                    insert into dokumentart values ('WISSENSQUELLE', 'W', null, '{}', '{}', '{"type":"object"}',
                        'NORMAL', 10, 'GESCHAEFTSJAHR', false, true)
                    """);
            liquibase040(verbindung, MIGRATION_SCHEMA);
            verbindung.commit();
            // fehlt die Zeile in public.mandant, wirkt das UPDATE trotzdem (left join)
            assertThat(wert(befehl, "select ki_vorschlag::text from dokumentart")).isEqualTo("false");
        } finally {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
            }
        }
    }

    @Test
    void fristmigrationBehaeltArtenDokumentfristenUndSperrenBeiderMandanten() throws Exception {
        for (String schema : List.of("m_probe_frist_urinet", "m_probe_frist_vhf_gsk")) {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set search_path to " + schema);
                liquibase(verbindung, schema, "001-dokumentart.xml");
                befehl.execute("""
                        insert into dokumentart (code, name, vertraulichkeit, aufbewahrung_jahre,
                            aufbewahrung_ab, gesetzliche_pflicht)
                        values ('GJ', 'Geschäftsjahr', 'NORMAL', 10, 'GESCHAEFTSJAHR', true),
                            ('VERTRAG', 'Vertrag', 'INTERN', 5, 'GUELTIG_BIS', false),
                            ('UNBEFRISTET', 'Unbefristet', 'NORMAL', null, null, true)
                        """);
                befehl.execute("create table dokument (id integer primary key, aufbewahren_bis date, sperre_bis date)");
                befehl.execute("""
                        insert into dokument values (1, '2036-12-31', '2036-12-31'), (2, null, '2036-11-17')
                        """);
                String artenVorher = wert(befehl, "select jsonb_agg(to_jsonb(a) order by code) from dokumentart a");
                String fristenVorher = wert(befehl, "select jsonb_agg(to_jsonb(d) order by id) from dokument d");
                verbindung.commit();

                liquibase(verbindung, schema, "042-fristbezuege.xml");
                verbindung.commit();
                assertThat(wert(befehl, "select jsonb_agg(to_jsonb(a) - 'aufbewahrung_feld' order by code)"
                        + " from dokumentart a")).isEqualTo(artenVorher);
                assertThat(wert(befehl, "select jsonb_agg(to_jsonb(d) order by id) from dokument d"))
                        .isEqualTo(fristenVorher);
                assertThat(wert(befehl, "select count(*) from dokumentart where aufbewahrung_feld is not null"))
                        .isEqualTo("0");
            } finally {
                try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                    befehl.execute("drop schema if exists " + schema + " cascade");
                }
            }
        }
    }

    private static void liquibase040(Connection verbindung, String schema) throws Exception {
        liquibase(verbindung, schema, "040-dokumentart-konfiguration.xml");
    }

    private static void liquibase(Connection verbindung, String schema, String datei) throws Exception {
        Database datenbank = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(schema);
        datenbank.setLiquibaseSchemaName(schema);
        ClassLoaderResourceAccessor ressource = new ClassLoaderResourceAccessor(
                KonfigurationSeedVersionTest.class.getClassLoader());
        Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), ressource,
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope(UpdateCommandStep.COMMAND_NAME)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG,
                                "db/mandant/changesets/" + datei)
                        .execute());
    }

    private static Connection verbinden() throws SQLException {
        return verbinden(null);
    }

    /** Verbindung zur Test-Datenbank oder, mit {@code datenbankName}, zu einer anderen Datenbank desselben Servers. */
    private static Connection verbinden(String datenbankName) throws SQLException {
        var config = ConfigProvider.getConfig();
        String url = config.getValue("quarkus.datasource.jdbc.url", String.class);
        if (datenbankName != null) {
            url = url.replaceFirst("/[^/?]+(\\?|$)", "/" + datenbankName + "$1");
        }
        return DriverManager.getConnection(url,
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
