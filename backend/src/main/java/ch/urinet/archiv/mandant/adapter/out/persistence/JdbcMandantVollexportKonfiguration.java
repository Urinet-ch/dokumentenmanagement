package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVollexportKonfiguration;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

/** Liest die unveränderlichen Konfigurationsabbilder des aktuellen Mandantenschemas. */
@ApplicationScoped
public class JdbcMandantVollexportKonfiguration implements MandantVollexportKonfiguration {

    private static final int BLOCK_ZEICHEN = 16_384;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final KonfigurationsdateiVerwaltung dateiVerwaltung;

    public JdbcMandantVollexportKonfiguration(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                              KonfigurationsdateiVerwaltung dateiVerwaltung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.dateiVerwaltung = dateiVerwaltung;
    }

    @Override
    public Version aktuell() {
        KonfigurationsdateiVerwaltung.Export export = dateiVerwaltung.exportieren();
        return new Version(export.version(), export.datei());
    }

    @Override
    public void versionen(Versionsausgabe ausgabe) throws IOException {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Vollexport ohne Mandanten-Schema");
        }
        String versionSql = "select version from " + schema + ".konfiguration_version "
                + "where version > ? order by version limit 1";
        String blockSql = "select substring(inhalt::text from ? for ?) from " + schema
                + ".konfiguration_version where version = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement versionen = verbindung.prepareStatement(versionSql);
             PreparedStatement block = verbindung.prepareStatement(blockSql)) {
            versionen.setQueryTimeout(300);
            block.setQueryTimeout(300);
            int nach = 0;
            while (true) {
                versionen.setInt(1, nach);
                int nummer;
                try (ResultSet zeile = versionen.executeQuery()) {
                    if (!zeile.next()) {
                        return;
                    }
                    nummer = zeile.getInt(1);
                }
                ausgabe.schreiben(nummer, ziel -> abbildSchreiben(block, nummer, ziel));
                nach = nummer;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konfigurationsabbilder für Vollexport nicht gelesen", e);
        }
    }

    private static void abbildSchreiben(PreparedStatement abfrage, int version, OutputStream ziel) throws IOException {
        try {
            int position = 1;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Konfigurationsexport unterbrochen");
                }
                abfrage.setInt(1, position);
                abfrage.setInt(2, BLOCK_ZEICHEN);
                abfrage.setInt(3, version);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Konfigurationsversion fehlt beim Export");
                    }
                    String text = zeile.getString(1);
                    int laenge = text.codePointCount(0, text.length());
                    ziel.write(text.getBytes(StandardCharsets.UTF_8));
                    if (laenge < BLOCK_ZEICHEN) {
                        return;
                    }
                    position += laenge;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konfigurationsversion nicht gelesen", e);
        }
    }
}
