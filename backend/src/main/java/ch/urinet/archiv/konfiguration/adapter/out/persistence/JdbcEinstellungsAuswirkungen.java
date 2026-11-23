package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.EinstellungsAuswirkungen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcEinstellungsAuswirkungen implements EinstellungsAuswirkungen {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final int ZEITGRENZE_S = 30;

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcEinstellungsAuswirkungen(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public long chatNachrichtenVor(Instant grenze) {
        return zaehlen("chat_nachricht", grenze);
    }

    @Override
    public long oeffentlicheFragenVor(Instant grenze) {
        return zaehlen("oeffentliche_frage", grenze);
    }

    private long zaehlen(String tabelle, Instant grenze) {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Auswirkung ohne Mandanten-Schema");
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from " + schema + "." + tabelle + " where zeit < ?")) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            abfrage.setTimestamp(1, Timestamp.from(grenze));
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Auswirkung nicht lesbar");
                }
                return zeile.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Auswirkung nicht lesbar", e);
        }
    }
}
