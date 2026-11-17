package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantBerichtDaten;
import ch.urinet.archiv.mandant.domain.MandantBericht;
import ch.urinet.archiv.mandant.domain.MandantBerichtFenster;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Erhebt nur Kennzahlen aus dem aktiven Mandantenschema und die Summe der Objektversionen seines Buckets. */
@ApplicationScoped
public class JdbcMandantBerichtDaten implements MandantBerichtDaten {

    private static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String ZEITFILTER = " where zeit >= ? and zeit < ?";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung mandantAufloesung;
    private final AblagePort ablage;

    public JdbcMandantBerichtDaten(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                   AblagePort ablage) {
        this.datenquelle = datenquelle;
        this.mandantAufloesung = aufloesung;
        this.ablage = ablage;
    }

    @Override
    public MandantBericht erheben(MandantCode mandant, MandantBerichtFenster fenster) {
        if (!mandant.equals(mandantAufloesung.ausKontext())) {
            throw new IllegalStateException("Mandantenbericht ausserhalb des Mandanten-Kontexts");
        }
        String schema = schema();
        long speicherBytes = ablage.speicherbedarf(mandant);
        Map<String, Long> dokumente = new TreeMap<>();
        Map<String, MandantBericht.Tokens> kiTokens = new TreeMap<>();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            try (PreparedStatement abfrage = verbindung.prepareStatement(
                    "select status, count(*) from " + schema + ".dokument group by status")) {
                abfrage.setQueryTimeout(ZEITGRENZE_S);
                try (ResultSet zeilen = abfrage.executeQuery()) {
                    while (zeilen.next()) {
                        dokumente.put(zeilen.getString(1), zeilen.getLong(2));
                    }
                }
            }
            try (PreparedStatement abfrage = verbindung.prepareStatement(
                    "select zweck, coalesce(sum(tokens_ein), 0), coalesce(sum(tokens_aus), 0) from "
                            + schema + ".ki_verbrauch" + ZEITFILTER + " group by zweck")) {
                zeitfenster(abfrage, fenster);
                try (ResultSet zeilen = abfrage.executeQuery()) {
                    while (zeilen.next()) {
                        kiTokens.put(zeilen.getString(1), new MandantBericht.Tokens(zeilen.getLong(2),
                                zeilen.getLong(3)));
                    }
                }
            }
            long chatFragen = chatFragen(verbindung, schema, fenster);
            return new MandantBericht(mandant, fenster.monat(), speicherBytes, dokumente, kiTokens, chatFragen);
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantenbericht nicht erhoben", e);
        }
    }

    private static long chatFragen(Connection verbindung, String schema, MandantBerichtFenster fenster)
            throws SQLException {
        String sql = "select (select count(*) from " + schema + ".chat_nachricht where rolle = 'BENUTZER' "
                + "and zeit >= ? and zeit < ?) + (select count(*) from " + schema + ".oeffentliche_frage "
                + "where zeit >= ? and zeit < ?)";
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            Timestamp von = Timestamp.from(fenster.von());
            Timestamp bis = Timestamp.from(fenster.bis());
            abfrage.setTimestamp(1, von);
            abfrage.setTimestamp(2, bis);
            abfrage.setTimestamp(3, von);
            abfrage.setTimestamp(4, bis);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    private static void zeitfenster(PreparedStatement abfrage, MandantBerichtFenster fenster) throws SQLException {
        abfrage.setQueryTimeout(ZEITGRENZE_S);
        abfrage.setTimestamp(1, Timestamp.from(fenster.von()));
        abfrage.setTimestamp(2, Timestamp.from(fenster.bis()));
    }

    private String schema() {
        String schema = mandantAufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Mandantenbericht ohne Mandanten-Schema");
        }
        return schema;
    }
}
