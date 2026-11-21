package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.adapter.json.ChatJson;
import ch.urinet.archiv.ki.application.chat.ChatSpeicher;
import ch.urinet.archiv.ki.application.chat.ChatAnalyseDaten;
import ch.urinet.archiv.ki.application.chat.ChatAnalyseLesen;
import ch.urinet.archiv.ki.application.chat.ChatWerkzeugDokument;
import ch.urinet.archiv.ki.domain.chat.Aufgeraeumt;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatNachricht;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.chat.Gespraech;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.ki.domain.chat.Quelle;
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
        aendern("insert into " + schema() + ".chat_gespraech "
                + "(id, benutzer, titel, erstellt, geaendert, verlauf_vollstaendig) "
                + "values (?, ?, ?, ?, ?, true)", id, benutzer, titel, Timestamp.from(zeit), Timestamp.from(zeit));
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
    public List<ChatWerkzeugDokument> werkzeugDokumente(UUID gespraech) {
        String s = schema();
        return abfragen("select aufruf.wert ->> 'name', dokument.id::uuid from " + s
                        + ".chat_nachricht nachricht "
                        + "cross join lateral jsonb_array_elements(nachricht.werkzeuge) with ordinality "
                        + "as aufruf(wert, nr_aufruf) "
                        + "cross join lateral jsonb_array_elements_text(aufruf.wert -> 'dokumentIds') "
                        + "with ordinality as dokument(id, nr_dokument) "
                        + "where nachricht.gespraech_id = ? and nachricht.rolle = 'ASSISTENT' "
                        + "order by nachricht.id, aufruf.nr_aufruf, dokument.nr_dokument",
                z -> new ChatWerkzeugDokument(z.getString(1), z.getObject(2, UUID.class)), gespraech);
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
    public long antwortSpeichern(UUID gespraech, ChatAntwort antwort, Instant zeit) {
        String s = schema();
        long id = abfragen("insert into " + s + ".chat_nachricht (gespraech_id, rolle, inhalt, werkzeuge, quellen, tokens_ein, "
                        + "tokens_aus, status, zeit, kontext_vollstaendig) "
                        + "values (?, 'ASSISTENT', ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?) returning id",
                z -> z.getLong(1),
                gespraech, antwort.inhalt(), ChatJson.werkzeuge(antwort.werkzeuge()), ChatJson.quellen(antwort.quellen()),
                antwort.tokens().map(t -> (Object) t.tokensEin()).orElse(Leer.INT),
                antwort.tokens().map(t -> (Object) t.tokensAus()).orElse(Leer.INT), antwort.status().name(),
                Timestamp.from(zeit), antwort.kontextVollstaendig()).getFirst();
        geaendert(s, gespraech, zeit);
        return id;
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
        return kopf.map(k -> {
            record Zeile(long id, ChatRolle rolle, String inhalt, String quellen, String status, Instant zeit,
                         boolean nachweis, String werkzeuge, boolean verlauf) { }
            List<Zeile> zeilen = abfragen("select n.id, n.rolle, n.inhalt, n.quellen::text, n.status, n.zeit, "
                            + "n.kontext_vollstaendig, n.werkzeuge::text, g.verlauf_vollstaendig from " + s
                            + ".chat_nachricht n join " + s + ".chat_gespraech g on g.id = n.gespraech_id "
                            + "where n.gespraech_id = ? and g.benutzer = ? order by n.id",
                    z -> new Zeile(z.getLong(1), ChatRolle.valueOf(z.getString(2)), z.getString(3), z.getString(4),
                            z.getString(5), z.getTimestamp(6).toInstant(), z.getBoolean(7), z.getString(8),
                            z.getBoolean(9)), id, benutzer);
            List<ChatNachricht> nachrichten = new ArrayList<>();
            boolean erlaubt = true;
            for (Zeile z : zeilen) {
                erlaubt &= z.verlauf();
                if (z.rolle() == ChatRolle.ASSISTENT) {
                    erlaubt &= z.nachweis();
                    if (erlaubt) {
                        erlaubt = ChatJson.analyseWerkzeugeLesen(z.werkzeuge()).stream()
                                .allMatch(ChatAnalyseLesen::werkzeugZulaessig);
                    }
                }
                nachrichten.add(new ChatNachricht(z.id(), z.rolle(), z.inhalt(), ChatJson.quellenLesen(z.quellen()),
                        Optional.ofNullable(z.status()).map(ChatStatus::valueOf), z.zeit(),
                        erlaubt && z.rolle() == ChatRolle.ASSISTENT && "OK".equals(z.status())));
            }
            return new Gespraech(k, nachrichten);
        });
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<ChatAnalyseDaten> analyseDaten(long nachrichtId, String benutzer) {
        String s = schema();
        record Ziel(UUID gespraech, String inhalt, String status, boolean vollstaendig, String quellen) { }
        Optional<Ziel> ziel = abfragen("select g.id, n.inhalt, n.status, g.verlauf_vollstaendig, n.quellen::text "
                        + "from " + s + ".chat_nachricht n join " + s
                        + ".chat_gespraech g on g.id = n.gespraech_id "
                        + "where n.id = ? and n.rolle = 'ASSISTENT' and g.benutzer = ? for update of g",
                z -> new Ziel(z.getObject(1, UUID.class), z.getString(2), z.getString(3), z.getBoolean(4),
                        z.getString(5)), nachrichtId, benutzer).stream().findFirst();
        if (ziel.isEmpty()) {
            return Optional.empty();
        }
        record Antwort(boolean vollstaendig, String werkzeuge) { }
        List<Antwort> antworten = abfragen("select kontext_vollstaendig, werkzeuge::text from " + s
                        + ".chat_nachricht where gespraech_id = ? and rolle = 'ASSISTENT' and id <= ? order by id",
                z -> new Antwort(z.getBoolean(1), z.getString(2)), ziel.get().gespraech(), nachrichtId);
        boolean vollstaendig = ziel.get().vollstaendig() && antworten.stream().allMatch(Antwort::vollstaendig);
        List<ChatAnalyseDaten.Werkzeug> werkzeuge = vollstaendig
                ? antworten.stream().flatMap(a -> ChatJson.analyseWerkzeugeLesen(a.werkzeuge()).stream()).toList()
                : List.of();
        List<ChatAnalyseDaten.Quelle> quellen = new ArrayList<>();
        if (vollstaendig) {
            for (Quelle quelle : ChatJson.quellenLesen(ziel.get().quellen())) {
                switch (quelle) {
                    case Quelle.Dokument d -> quellen.add(new ChatAnalyseDaten.Quelle(d.id(), null));
                    case Quelle.Wissen w -> {
                        quellen.add(new ChatAnalyseDaten.Quelle(null, w.pfad()));
                        w.dokumente().forEach(d -> quellen.add(new ChatAnalyseDaten.Quelle(d.id(), null)));
                    }
                    case Quelle.Stand ignored -> { }
                }
            }
        }
        return Optional.of(new ChatAnalyseDaten(ziel.get().inhalt(), ziel.get().status(), vollstaendig,
                werkzeuge, quellen));
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
        aendern("update " + s + ".chat_gespraech g set verlauf_vollstaendig = false "
                + "where g.verlauf_vollstaendig and exists (select 1 from " + s
                + ".chat_nachricht n where n.gespraech_id = g.id and n.zeit < ?)", Timestamp.from(grenze));
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
