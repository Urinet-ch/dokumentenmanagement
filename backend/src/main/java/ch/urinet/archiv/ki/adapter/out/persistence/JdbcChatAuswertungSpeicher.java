package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.ChatAuswertungSpeicher;
import ch.urinet.archiv.ki.application.chat.ChatBewertungAuswertung;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Begrenzte, mandantenlokale JDBC-Abfragen für Chat-Bewertungen. */
@ApplicationScoped
public class JdbcChatAuswertungSpeicher implements ChatAuswertungSpeicher {

    private static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String FROM = " from %s.chat_bewertung b join %s.chat_nachricht a "
            + "on a.id = b.nachricht_id join %s.chat_gespraech g on g.id = a.gespraech_id "
            + "where b.erstellt >= ? and b.erstellt < ? and b.sterne between ? and ?";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcChatAuswertungSpeicher(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    @Transactional(TxType.NOT_SUPPORTED)
    public ExportSnapshot exportSnapshot(Filter filter) {
        String s = schema();
        Connection c = null;
        try {
            c = datenquelle.get().getConnection();
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            return new JdbcExportSnapshot(c, s, filter);
        } catch (SQLException e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException closeFehler) {
                    e.addSuppressed(closeFehler);
                }
            }
            throw new IllegalStateException("Export-Snapshot nicht eingerichtet", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Auswertung auswertung(Filter filter) {
        String s = schema();
        long anzahl;
        double durchschnitt;
        try (Connection c = datenquelle.get().getConnection();
             PreparedStatement sql = c.prepareStatement("select count(*), avg(b.sterne) " + from(s))) {
            vorbereiten(sql, filter);
            try (ResultSet rs = sql.executeQuery()) {
                rs.next();
                anzahl = rs.getLong(1);
                durchschnitt = rs.getDouble(2);
                if (rs.wasNull()) {
                    durchschnitt = 0;
                }
            }
            long[] verteilung = new long[5];
            try (PreparedStatement verteilungSql = c.prepareStatement("select b.sterne, count(*) " + from(s)
                    + " group by b.sterne order by b.sterne")) {
                vorbereiten(verteilungSql, filter);
                try (ResultSet rs = verteilungSql.executeQuery()) {
                    while (rs.next()) {
                        verteilung[rs.getInt(1) - 1] = rs.getLong(2);
                    }
                }
            }
            List<GrundAnzahl> gruende = new ArrayList<>();
            try (PreparedStatement grundSql = c.prepareStatement("select b.grund, count(*) " + from(s)
                    + " and b.grund is not null group by b.grund order by b.grund")) {
                vorbereiten(grundSql, filter);
                try (ResultSet rs = grundSql.executeQuery()) {
                    while (rs.next()) {
                        gruende.add(new GrundAnzahl(rs.getString(1), rs.getLong(2)));
                    }
                }
            }
            List<SchlechteAntwort> schlecht = schlechteAntworten(c, s, filter);
            return new Auswertung(anzahl, durchschnitt, verteilung, gruende, schlecht);
        } catch (SQLException e) {
            throw new IllegalStateException("Chat-Auswertung nicht gelesen", e);
        }
    }

    private static final class JdbcExportSnapshot implements ExportSnapshot {
        private final Connection verbindung;
        private final String schema;
        private final Filter filter;
        private final long anzahl;
        private final List<String> namen;
        private boolean abgeschlossen;

        private JdbcExportSnapshot(Connection verbindung, String schema, Filter filter) throws SQLException {
            this.verbindung = verbindung;
            this.schema = schema;
            this.filter = filter;
            try {
                anzahl = zaehlen();
                namen = namenLesen();
            } catch (SQLException | RuntimeException e) {
                try {
                    verbindung.rollback();
                } catch (SQLException rollbackFehler) {
                    e.addSuppressed(rollbackFehler);
                }
                try {
                    verbindung.close();
                } catch (SQLException closeFehler) {
                    e.addSuppressed(closeFehler);
                }
                throw e;
            }
        }

        @Override
        public long anzahl() {
            return anzahl;
        }

        @Override
        public List<String> kontaktUndPartnerNamen() {
            return namen;
        }

        @Override
        public void exportZeilen(Consumer<ExportZeile> verarbeiten) {
            String sqlText = "select b.erstellt, a.id, b.sterne, b.grund, b.freitext, "
                    + "(select q.inhalt from " + schema + ".chat_nachricht q where q.gespraech_id = a.gespraech_id "
                    + "and q.rolle = 'BENUTZER' and q.id < a.id order by q.id desc limit 1), "
                    + "a.inhalt, coalesce(a.werkzeuge, '[]'::jsonb)::text, "
                    + "coalesce(a.quellen, '[]'::jsonb)::text, a.tokens_ein, a.tokens_aus, "
                    + "a.zeit_bis_erster_text_ms, a.modellkennung, a.systemprompt_version "
                    + from(schema) + " order by b.erstellt, a.id, b.benutzer";
            try (PreparedStatement sql = verbindung.prepareStatement(sqlText)) {
                sql.setFetchSize(100);
                vorbereiten(sql, filter);
                try (ResultSet rs = sql.executeQuery()) {
                    while (rs.next()) {
                        verarbeiten.accept(new ExportZeile(rs.getTimestamp(1).toInstant(), rs.getLong(2),
                                rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                rs.getString(8), rs.getString(9), nullableInt(rs, 10), nullableInt(rs, 11),
                                nullableLong(rs, 12), rs.getString(13), rs.getString(14)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Chat-Bewertungen nicht exportiert", e);
            }
        }

        @Override
        public void commit() {
            try {
                verbindung.commit();
                abgeschlossen = true;
            } catch (SQLException e) {
                throw new IllegalStateException("Export-Snapshot nicht abgeschlossen", e);
            }
        }

        @Override
        public void close() {
            SQLException fehler = null;
            if (!abgeschlossen) {
                try {
                    verbindung.rollback();
                } catch (SQLException e) {
                    fehler = e;
                }
            }
            try {
                verbindung.close();
            } catch (SQLException e) {
                if (fehler == null) {
                    fehler = e;
                } else {
                    fehler.addSuppressed(e);
                }
            }
            if (fehler != null) {
                throw new IllegalStateException("Export-Snapshot nicht geschlossen", fehler);
            }
        }

        private long zaehlen() throws SQLException {
            try (PreparedStatement sql = verbindung.prepareStatement("select count(*) " + from(schema))) {
                vorbereiten(sql, filter);
                try (ResultSet rs = sql.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }

        private List<String> namenLesen() throws SQLException {
            try (PreparedStatement sql = verbindung.prepareStatement("select distinct name_normalisiert from "
                    + schema + ".kontakt where name_normalisiert is not null order by name_normalisiert")) {
                sql.setFetchSize(100);
                sql.setQueryTimeout(ZEITGRENZE_S);
                try (ResultSet rs = sql.executeQuery()) {
                    List<String> werte = new ArrayList<>();
                    while (rs.next()) {
                        werte.add(rs.getString(1));
                    }
                    return List.copyOf(werte);
                }
            }
        }
    }

    private List<SchlechteAntwort> schlechteAntworten(Connection c, String s, Filter filter) throws SQLException {
        String sqlText = "select b.erstellt, b.sterne, b.grund, "
                + "(select left(q.inhalt, 120) from " + s + ".chat_nachricht q "
                + "where q.gespraech_id = a.gespraech_id and q.rolle = 'BENUTZER' and q.id < a.id "
                + "order by q.id desc limit 1) " + from(s) + " and b.sterne <= 2 "
                + "order by b.erstellt desc, a.id desc limit " + ChatBewertungAuswertung.MAX_SCHLECHTE_ANTWORTEN;
        try (PreparedStatement sql = c.prepareStatement(sqlText)) {
            vorbereiten(sql, filter);
            try (ResultSet rs = sql.executeQuery()) {
                List<SchlechteAntwort> antworten = new ArrayList<>();
                while (rs.next()) {
                    antworten.add(new SchlechteAntwort(rs.getTimestamp(1).toInstant(), rs.getInt(2),
                            rs.getString(3), rs.getString(4)));
                }
                return List.copyOf(antworten);
            }
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Chat-Auswertung ohne Mandanten-Schema");
        }
        return schema;
    }

    private static String from(String schema) {
        return FROM.formatted(schema, schema, schema);
    }

    private static void vorbereiten(PreparedStatement sql, Filter filter) throws SQLException {
        sql.setQueryTimeout(ZEITGRENZE_S);
        sql.setTimestamp(1, Timestamp.from(filter.von()));
        sql.setTimestamp(2, Timestamp.from(filter.bis()));
        sql.setInt(3, filter.minSterne());
        sql.setInt(4, filter.maxSterne());
    }

    private static Integer nullableInt(ResultSet rs, int spalte) throws SQLException {
        int wert = rs.getInt(spalte);
        return rs.wasNull() ? null : wert;
    }

    private static Long nullableLong(ResultSet rs, int spalte) throws SQLException {
        long wert = rs.getLong(spalte);
        return rs.wasNull() ? null : wert;
    }
}
