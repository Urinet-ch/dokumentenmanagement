package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.adapter.json.ChatJson;
import ch.urinet.archiv.ki.application.chat.ChatSpeicher;
import ch.urinet.archiv.ki.domain.chat.Aufgeraeumt;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatNachricht;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.chat.Gespraech;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code m_<code>.chat_gespraech} und {@code chat_nachricht} des Mandanten aus Anmeldung oder Kontext (Changeset 027,
 * Schema immer qualifiziert). Jede Anweisung mit eigener Zeitgrenze ({@value #ZEITGRENZE_S} s). Verbindungen nur je
 * Anweisung bzw. bis zum Ende der Transaktion, nie über den Stream.
 */
@ApplicationScoped
public class JdbcChatSpeicher implements ChatSpeicher {

    static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcChatSpeicher(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void benutzerSperren(String benutzer) {
        String s = schema();
        abfragen("select pg_advisory_xact_lock(hashtextextended(?, 0))", z -> null, "chat:" + s + ":" + benutzer);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean gehoertSperren(UUID gespraech, String benutzer) {
        return !abfragen("select 1 from " + schema() + ".chat_gespraech where id = ? and benutzer = ? for update",
                z -> 1, gespraech, benutzer).isEmpty();
    }

    @Override
    public Optional<Instant> grenzFrage(String benutzer, Instant seit, int grenze) {
        String s = schema();
        return abfragen("select n.zeit from " + s + ".chat_nachricht n join " + s + ".chat_gespraech g "
                        + "on g.id = n.gespraech_id where g.benutzer = ? and n.rolle = 'BENUTZER' and n.zeit > ? "
                        + "order by n.zeit desc offset ? limit 1",
                z -> z.getTimestamp(1).toInstant(), benutzer, Timestamp.from(seit), grenze - 1)
                .stream().findFirst();
    }

    @Override
    public void gespraechAnlegen(UUID id, String benutzer, String titel, Instant zeit) {
        aendern("insert into " + schema() + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) "
                + "values (?, ?, ?, ?, ?)", id, benutzer, titel, Timestamp.from(zeit), Timestamp.from(zeit));
    }

    @Override
    public List<VerlaufEintrag> verlauf(UUID gespraech, int anzahl) {
        List<VerlaufEintrag> neueste = abfragen("select rolle, inhalt from " + schema() + ".chat_nachricht "
                        + "where gespraech_id = ? and inhalt <> '' order by id desc limit ?",
                z -> new VerlaufEintrag(ChatRolle.valueOf(z.getString(1)), z.getString(2)), gespraech, anzahl);
        List<VerlaufEintrag> verlauf = new ArrayList<>(neueste);
        Collections.reverse(verlauf);
        return verlauf;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void frageSpeichern(UUID gespraech, String frage, Instant zeit) {
        String s = schema();
        aendern("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', ?, ?)",
                gespraech, frage, Timestamp.from(zeit));
        geaendert(s, gespraech, zeit);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void antwortSpeichern(UUID gespraech, ChatAntwort antwort, Instant zeit) {
        String s = schema();
        aendern("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, werkzeuge, quellen, tokens_ein, "
                        + "tokens_aus, status, zeit) values (?, 'ASSISTENT', ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                gespraech, antwort.inhalt(), ChatJson.werkzeuge(antwort.werkzeuge()), ChatJson.quellen(antwort.quellen()),
                antwort.tokens().map(t -> (Object) t.tokensEin()).orElse(Leer.INT),
                antwort.tokens().map(t -> (Object) t.tokensAus()).orElse(Leer.INT), antwort.status().name(),
                Timestamp.from(zeit));
        geaendert(s, gespraech, zeit);
    }

    @Override
    public List<GespraechKopf> gespraeche(String benutzer, int anzahl) {
        return abfragen("select id, titel, geaendert from " + schema() + ".chat_gespraech where benutzer = ? "
                        + "order by geaendert desc, id limit ?",
                z -> new GespraechKopf(z.getObject(1, UUID.class), z.getString(2), z.getTimestamp(3).toInstant()),
                benutzer, anzahl);
    }

    @Override
    public Optional<Gespraech> gespraech(UUID id, String benutzer) {
        String s = schema();
        Optional<GespraechKopf> kopf = abfragen("select id, titel, geaendert from " + s + ".chat_gespraech "
                        + "where id = ? and benutzer = ?",
                z -> new GespraechKopf(z.getObject(1, UUID.class), z.getString(2), z.getTimestamp(3).toInstant()),
                id, benutzer).stream().findFirst();
        return kopf.map(k -> new Gespraech(k, abfragen("select rolle, inhalt, quellen::text, status, zeit from " + s
                        + ".chat_nachricht where gespraech_id = ? order by id",
                z -> new ChatNachricht(ChatRolle.valueOf(z.getString(1)), z.getString(2),
                        ChatJson.quellenLesen(z.getString(3)),
                        Optional.ofNullable(z.getString(4)).map(ChatStatus::valueOf), z.getTimestamp(5).toInstant()),
                id)));
    }

    /**
     * In einer Transaktion: Nachrichten vor der Grenze, danach Gespräche ohne Nachrichten, deren letzte Änderung vor der
     * Grenze liegt. Die Bedingung auf {@code geaendert} schützt ein Gespräch, in das gerade eine neue Frage kommt
     * ({@link #gehoertSperren} sperrt die Zeile, {@link #frageSpeichern} setzt {@code geaendert}).
     */
    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Aufgeraeumt aufraeumen(Instant grenze) {
        String s = schema();
        int nachrichten = aendern("delete from " + s + ".chat_nachricht where zeit < ?", Timestamp.from(grenze));
        int gespraeche = aendern("delete from " + s + ".chat_gespraech g where g.geaendert < ? and not exists "
                + "(select 1 from " + s + ".chat_nachricht n where n.gespraech_id = g.id)", Timestamp.from(grenze));
        return new Aufgeraeumt(nachrichten, gespraeche);
    }

    private void geaendert(String s, UUID gespraech, Instant zeit) {
        aendern("update " + s + ".chat_gespraech set geaendert = greatest(geaendert, ?) where id = ?",
                Timestamp.from(zeit), gespraech);
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Chat ohne Mandanten-Schema");
        }
        return schema;
    }

    /** SQL-NULL für eine Ganzzahl-Spalte. */
    private enum Leer {
        INT
    }

    @FunctionalInterface
    private interface Zeile<T> {
        T lesen(ResultSet zeile) throws SQLException;
    }

    private int aendern(String sql, Object... werte) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            setzen(befehl, werte);
            return befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Chat nicht geschrieben", e);
        }
    }

    private <T> List<T> abfragen(String sql, Zeile<T> zeile, Object... werte) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            setzen(abfrage, werte);
            List<T> liste = new ArrayList<>();
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    liste.add(zeile.lesen(z));
                }
            }
            return liste;
        } catch (SQLException e) {
            throw new IllegalStateException("Chat nicht gelesen", e);
        }
    }

    private static void setzen(PreparedStatement befehl, Object... werte) throws SQLException {
        for (int i = 0; i < werte.length; i++) {
            switch (werte[i]) {
                case UUID uuid -> befehl.setObject(i + 1, uuid, Types.OTHER);
                case Leer leer -> befehl.setNull(i + 1, Types.INTEGER);
                case null -> befehl.setNull(i + 1, Types.NULL);
                default -> befehl.setObject(i + 1, werte[i]);
            }
        }
    }
}
