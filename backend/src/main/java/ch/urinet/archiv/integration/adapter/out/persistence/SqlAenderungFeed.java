package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort;
import ch.urinet.archiv.integration.domain.webseite.FeedCursor;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code webseite_aenderung} und {@code webseite_feed_stand} im Schema des Mandanten aus dem Kontext (Changeset
 * {@code mandant/032}, Schema immer qualifiziert; Spec 03, Aufgabe 11.9). Die Lesung ist eine einzige Anweisung: Der
 * Horizont {@code pg_snapshot_xmin} und die Einträge stammen aus demselben Schnappschuss, und nur Einträge unterhalb
 * des Horizonts kommen heraus (Req 14.3); sonst übersähe ein später abgeschlossener Schreiber seinen Eintrag. Werte der
 * Anfrage (Cursor, Stufen, Webseite, Grenze) werden nur gebunden. Die Transaktions-ID ({@code xid8}) läuft als
 * {@code bigint}-Text hin und her.
 */
@ApplicationScoped
public class SqlAenderungFeed implements AenderungFeedPort {

    static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String SPALTEN = "e.xid, e.id, e.dokument_id, e.zeit, e.status, e.version, e.stufe_vorher, "
            + "e.stufe_nachher, e.fehlercode";
    private static final String AUSSEN = "select h.xmin::text as horizont, h.xmax::text as naechste, a.xid::text as xid, "
            + "a.id, a.dokument_id, a.zeit, a.status, a.version, a.stufe_vorher, a.stufe_nachher, a.fehlercode, "
            + "a.referenz from h left join lateral (";
    private static final String ENDE = " order by e.xid, e.id limit ?) a on true order by a.xid, a.id";
    private static final String HORIZONT = "with h as (select pg_snapshot_xmin(pg_current_snapshot()) as xmin, "
            + "pg_snapshot_xmax(pg_current_snapshot()) as xmax) ";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public SqlAenderungFeed(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Lesung lesen(FeedCursor ab, int max, List<String> stufen) {
        String sql = HORIZONT + AUSSEN + "select " + SPALTEN + ", null::text as referenz from " + schema()
                + ".webseite_aenderung e where (e.xid, e.id) > (?::xid8, ?) and e.xid < h.xmin "
                + "and (e.stufe_vorher = any (?) or e.stufe_nachher = any (?))" + ENDE;
        return lesung(sql, (verbindung, befehl) -> {
            Array feld = verbindung.createArrayOf("text", stufen.toArray());
            befehl.setString(1, Long.toString(ab.xid()));
            befehl.setLong(2, ab.id());
            befehl.setArray(3, feld);
            befehl.setArray(4, feld);
            befehl.setInt(5, max);
        });
    }

    @Override
    public Lesung lesenErfassung(FeedCursor ab, int max, String webseite) {
        String schema = schema();
        String sql = HORIZONT + AUSSEN + "select " + SPALTEN + ", r.referenz from " + schema + ".webseite_aenderung e "
                + "left join lateral (select w.referenz from " + schema + ".webseite_referenz w "
                + "where w.dokument_id = e.dokument_id and w.webseite = ? order by w.erstellt, w.referenz limit 1) r "
                + "on true where (e.xid, e.id) > (?::xid8, ?) and e.xid < h.xmin" + ENDE;
        return lesung(sql, (verbindung, befehl) -> {
            befehl.setString(1, webseite);
            befehl.setString(2, Long.toString(ab.xid()));
            befehl.setLong(3, ab.id());
            befehl.setInt(4, max);
        });
    }

    @Override
    public Optional<Long> bereinigtBis() {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("select bereinigt_bis::text from " + schema()
                     + ".webseite_feed_stand where id = 1")) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            try (ResultSet ergebnis = befehl.executeQuery()) {
                if (ergebnis.next() && ergebnis.getString(1) != null) {
                    return Optional.of(Long.parseLong(ergebnis.getString(1)));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Stand des Feeds nicht gelesen", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int bereinigen(Instant grenze) {
        String schema = schema();
        String sql = "with g as (delete from " + schema + ".webseite_aenderung where zeit < ? "
                + "returning xid::text::bigint as x), "
                + "u as (update " + schema + ".webseite_feed_stand f set bereinigt_bis = "
                + "greatest(coalesce(f.bereinigt_bis::text::bigint, 0), q.m)::text::xid8 "
                + "from (select max(x) as m from g) q where q.m is not null and f.id = 1 returning 1) "
                + "select (select count(*) from g) as geloescht, (select count(*) from u) as gesetzt";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            befehl.setTimestamp(1, Timestamp.from(grenze));
            try (ResultSet ergebnis = befehl.executeQuery()) {
                ergebnis.next();
                return ergebnis.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Feed nicht bereinigt", e);
        }
    }

    @Override
    public Horizont horizont() {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("select pg_snapshot_xmin(pg_current_snapshot())::text, "
                     + "pg_snapshot_xmax(pg_current_snapshot())::text")) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            try (ResultSet ergebnis = befehl.executeQuery()) {
                ergebnis.next();
                return new Horizont(Long.parseLong(ergebnis.getString(1)), Long.parseLong(ergebnis.getString(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Horizont nicht gelesen", e);
        }
    }

    private interface Binden {
        void binden(Connection verbindung, PreparedStatement befehl) throws SQLException;
    }

    private Lesung lesung(String sql, Binden binden) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            binden.binden(verbindung, befehl);
            List<Zeile> zeilen = new ArrayList<>();
            long horizont = 0;
            long naechste = 0;
            try (ResultSet ergebnis = befehl.executeQuery()) {
                while (ergebnis.next()) {
                    horizont = Long.parseLong(ergebnis.getString("horizont"));
                    naechste = Long.parseLong(ergebnis.getString("naechste"));
                    if (ergebnis.getString("xid") == null) {
                        continue;
                    }
                    zeilen.add(new Zeile(Long.parseLong(ergebnis.getString("xid")), ergebnis.getLong("id"),
                            ergebnis.getObject("dokument_id", UUID.class),
                            ergebnis.getTimestamp("zeit").toInstant(), ergebnis.getString("status"),
                            ergebnis.getObject("version", Integer.class), ergebnis.getString("stufe_vorher"),
                            ergebnis.getString("stufe_nachher"), ergebnis.getString("fehlercode"),
                            ergebnis.getString("referenz")));
                }
            }
            return new Lesung(List.copyOf(zeilen), horizont, naechste);
        } catch (SQLException e) {
            throw new IllegalStateException("Feed nicht gelesen", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Feed ohne Mandanten-Schema");
        }
        return schema;
    }
}
