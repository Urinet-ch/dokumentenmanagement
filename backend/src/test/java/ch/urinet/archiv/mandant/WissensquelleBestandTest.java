package ch.urinet.archiv.mandant;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class WissensquelleBestandTest {
    private static final MandantCode MANDANT = new MandantCode("wq-bestand");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject MandantProvisionierung provisionierung;
    @Inject MandantDatenbank mandantDatenbank;
    @Inject Instance<AgroalDataSource> datenbank;

    @Test
    void migrationBrichtBeiVerweisAbUndUebernimmtDanachZweiDokumenteAtomar() throws SQLException {
        anlegenWennFehlt(MANDANT);
        String s = MANDANT.schema();
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('PROJEKTDOKUMENT', 'Projektdokument', 'NORMAL', false) on conflict do nothing");
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('WISSENSQUELLE', 'Wissensquelle', 'NORMAL', false)");
        ausfuehren("insert into " + s + ".konfiguration_version (version, wer, grund, quelle, inhalt, sha256) "
                + "select k.version + 1, 'probe', 'Altbestand', 'KOMMANDO', i.inhalt, "
                + "encode(sha256(convert_to(i.inhalt::text, 'UTF8')), 'hex') from " + s + ".konfiguration_version k, "
                + "lateral (select jsonb_set(jsonb_set(k.inhalt, '{version}', to_jsonb(k.version + 1)), "
                + "'{arten}', k.inhalt->'arten' || '[{\"code\":\"WISSENSQUELLE\"}]'::jsonb) as inhalt) i "
                + "order by k.version desc limit 1");
        UUID erstes = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "WISSENSQUELLE", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        UUID zweites = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "WISSENSQUELLE", "INTERN", true, "probe", null, "ARCHIVIERT"));
        ausfuehren("drop table " + s + ".wissensquelle_markierung");
        ausfuehren("delete from " + s + ".databasechangelog where id = '070-wissensquelle-markierung'");
        String alteVersion = wert("select max(version)::text from " + s + ".konfiguration_version");
        ausfuehren("update " + s + ".dokumentart set fremdbeleg_art = 'WISSENSQUELLE' where code = 'PROBE_BELEG'");

        assertThatThrownBy(() -> mandantDatenbank.schemaMigrieren(s))
                .hasStackTraceContaining("Ein Verweis auf die Dokumentart WISSENSQUELLE besteht weiterhin");
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE'")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument_version where grund = 'Migration Wissensquelle'"))
                .isEqualTo("0");
        assertThat(wert("select max(version)::text from " + s + ".konfiguration_version")).isEqualTo(alteVersion);
        assertThat(wert("select to_regclass('" + s + ".wissensquelle_markierung') is null")).isEqualTo("t");

        ausfuehren("update " + s + ".dokumentart set fremdbeleg_art = null where code = 'PROBE_BELEG'");
        mandantDatenbank.schemaMigrieren(s);
        assertThat(wert("select count(*) from " + s
                + ".dokument where art = 'PROJEKTDOKUMENT' and version = 1")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung "
                + "where markiert_von = 'System' and beendet_am is null")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument_version "
                + "where grund = 'Migration Wissensquelle' and daten->'dokument'->>'art' = 'WISSENSQUELLE'"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo("0");
        assertThat(wert("select quelle from " + s + ".konfiguration_version order by version desc limit 1"))
                .isEqualTo("MIGRATION");
        assertThat(wert("select count(*) from " + s + ".konfiguration_version k, "
                + "jsonb_array_elements(k.inhalt->'arten') a where k.version = (select max(version) from " + s
                + ".konfiguration_version) and a->>'code' = 'WISSENSQUELLE'")).isEqualTo("0");
        Testdokumente.loeschen(datenbank.get(), MANDANT.wert(), List.of(erstes, zweites));
    }

    @Test
    void neuerMandantUndVhfHabenKeineDokumentartWissensquelle() throws SQLException {
        for (MandantCode mandant : List.of(MANDANT, VHF)) {
            anlegenWennFehlt(mandant);
            assertThat(wert("select exectype from " + mandant.schema()
                    + ".databasechangelog where id = '044-wissensquelle-bestand'")).isIn("MARK_RAN", "EXECUTED");
            assertThat(wert("select count(*) from " + mandant.schema()
                    + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo("0");
        }
    }

    private void anlegenWennFehlt(MandantCode mandant) throws SQLException {
        if (wert("select count(*) from public.mandant where code = '" + mandant.wert() + "'").equals("0")) {
            provisionierung.anlegen(mandant.wert(), null, null);
        }
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.executeUpdate(sql);
        }
    }

    private String wert(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery(sql)) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }
}
