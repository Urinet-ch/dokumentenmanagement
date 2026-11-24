package ch.urinet.archiv.wissen;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class WissensquelleBestandSchritte {
    private static final MandantCode MANDANT = new MandantCode("wq-bestand");

    @Inject MandantDatenbank mandantDatenbank;
    @Inject MandantProvisionierung provisionierung;
    @Inject Instance<AgroalDataSource> datenbank;

    private final List<UUID> dokumente = new ArrayList<>();

    @Angenommen("ein bestehender Bestand enthält zwei Dokumente der alten Wissensquellenart")
    public void bestandVorbereiten() throws SQLException {
        if (wert("select count(*) from public.mandant where code = '" + MANDANT.wert() + "'").equals("0")) {
            provisionierung.anlegen(MANDANT.wert(), null, null);
        }
        String schema = MANDANT.schema();
        ausfuehren("insert into " + schema
                + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('WISSENSQUELLE', 'Wissensquelle', 'NORMAL', false) on conflict do nothing");
        ausfuehren("insert into " + schema
                + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('PROJEKTDOKUMENT', 'Projektdokument', 'NORMAL', false) on conflict do nothing");
        ausfuehren("insert into " + schema + ".konfiguration_version (version, wer, grund, quelle, inhalt, sha256) "
                + "select k.version + 1, 'probe', 'Altbestand', 'KOMMANDO', "
                + "jsonb_set(jsonb_set(k.inhalt, '{version}', to_jsonb(k.version + 1)), "
                + "'{arten}', k.inhalt->'arten' || '[{\"code\":\"WISSENSQUELLE\"}]'::jsonb), "
                + "encode(sha256(convert_to(k.inhalt::text, 'UTF8')), 'hex') from " + schema
                + ".konfiguration_version k order by k.version desc limit 1");
        dokumente.add(Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "WISSENSQUELLE", "NORMAL", true,
                        "probe", null, "ARCHIVIERT")));
        dokumente.add(Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "WISSENSQUELLE", "INTERN", true,
                        "probe", null, "ARCHIVIERT")));
        ausfuehren("drop table " + schema + ".wissensquelle_markierung");
        ausfuehren("delete from " + schema + ".databasechangelog where id = '070-wissensquelle-markierung'");
    }

    @Wenn("die Wissensquellenmigration für den bestehenden Bestand läuft")
    public void migrationAusfuehren() throws Exception {
        mandantDatenbank.schemaMigrieren(MANDANT.schema());
    }

    @Dann("sind die alten Wissensquellendokumente als Projektdokumente versioniert und markiert")
    public void migrationPruefen() throws SQLException {
        String schema = MANDANT.schema();
        assertThat(wert("select count(*) from " + schema
                + ".dokument where art = 'PROJEKTDOKUMENT' and version = 1")).isEqualTo("2");
        assertThat(wert("select count(*) from " + schema
                + ".dokument_version where grund = 'Migration Wissensquelle'")).isEqualTo("2");
        assertThat(wert("select count(*) from " + schema
                + ".wissensquelle_markierung where markiert_von = 'System' and beendet_am is null"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + schema
                + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo("0");
        assertThat(wert("select count(*) from " + schema
                + ".konfiguration_version k, jsonb_array_elements(k.inhalt->'arten') a "
                + "where k.version = (select max(version) from " + schema
                + ".konfiguration_version) and a->>'code' = 'WISSENSQUELLE'")).isEqualTo("0");
    }

    @After("@wissensquelle-bestand")
    public void aufraeumen() throws SQLException {
        if (!dokumente.isEmpty()) {
            Testdokumente.loeschen(datenbank.get(), MANDANT.wert(), dokumente);
        }
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             Statement befehl = verbindung.createStatement()) {
            befehl.executeUpdate(sql);
        }
    }

    private String wert(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery(sql)) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }
}
