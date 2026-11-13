package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.Fehlgeschlagen;
import ch.urinet.archiv.verarbeitung.application.Verwaist;
import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nachbearbeitung;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * {@code public.auftrag} per JDBC (ADR-003): Holen mit {@code FOR UPDATE SKIP LOCKED} in einer Anweisung, Tabelle immer
 * qualifiziert, weil eine Verbindung in einer Transaktion auf dem Schema des Mandanten stehen kann. Jede Änderung an
 * einem geholten Auftrag prüft den Anspruch ({@code id}, {@code versuche}, {@code LAEUFT}). Fehler tragen nur den
 * SQLState weiter, nie die Meldung von PostgreSQL, die Werte enthalten kann. Gelesene Zeilen werden einzeln umgewandelt:
 * Eine unlesbare Zeile (Nutzlast, Mandant oder Typ ungültig) geht mit Fehler-ID auf {@code FEHLER} und wird
 * übersprungen, damit sie Poller und Aufräumer nicht für alle Mandanten aufhält.
 */
@ApplicationScoped
public class JdbcAuftragSchlange implements AuftragSchlange {

    static final String UNLESBAR = "unlesbar";

    private static final Logger LOG = Logger.getLogger(JdbcAuftragSchlange.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> KARTE = new TypeReference<>() {
    };
    private static final String SPALTEN = "a.id, a.mandant, a.typ, a.nutzlast::text as nutzlast, a.versuche";
    private static final String ANSPRUCH = " where id = ? and versuche = ? and status = 'LAEUFT'";
    /**
     * Höchste Wartezeit auf eine Zeilensperre für Lebenszeichen und Freigabe verwaister Aufträge: Hält eine andere
     * Transaktion die Zeile, wirkt das wie «Datenbank kurz weg», statt Überwachung oder Aufräumer festzuhalten.
     */
    static final int SPERR_WARTEZEIT_SEKUNDEN = 5;

    /**
     * Höchste Wartezeit von {@link #anspruchSperren}: Lebenszeichen und Aufräumer halten die Zeile nur kurz; danach
     * gilt es als Fehler des Handlers (Pflicht (1) der Handler, Aufgabe 2.6).
     */
    static final int ANSPRUCH_WARTEZEIT_SEKUNDEN = 30;

    // lazy: ohne Datasource (z. B. Bereitschaftsprüfung) ist die Bean inaktiv
    private final Instance<AgroalDataSource> datenquelle;

    public JdbcAuftragSchlange(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public long einfuegen(MandantCode mandant, AuftragTyp typ, Nutzlast nutzlast) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into public.auftrag (mandant, typ, nutzlast) "
                        + "values (?, ?, cast(? as jsonb)) returning id")) {
            einfuegen.setString(1, mandant.wert());
            einfuegen.setString(2, typ.wert());
            einfuegen.setString(3, json(nutzlast));
            try (ResultSet zeile = einfuegen.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        } catch (SQLException e) {
            throw fehler("Auftrag nicht eingereiht", e);
        }
    }

    @Override
    public List<Auftrag> holen(Set<AuftragTyp> typen, int anzahl) {
        if (typen.isEmpty() || anzahl < 1) {
            return List.of();
        }
        String sql = "with frei as (select a.id from public.auftrag a "
                + "join public.mandant m on m.code = a.mandant and m.aktiv "
                + "where a.status = 'OFFEN' and (a.nicht_vor is null or a.nicht_vor <= now()) and a.typ = any(?) "
                + "order by a.id limit ? for update of a skip locked) "
                + "update public.auftrag a set status = 'LAEUFT', gestartet = now(), lebenszeichen = now() "
                + "from frei where a.id = frei.id returning " + SPALTEN;
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement holen = verbindung.prepareStatement(sql)) {
            Array namen = verbindung.createArrayOf("text", typen.stream().map(AuftragTyp::wert).toArray());
            holen.setArray(1, namen);
            holen.setInt(2, anzahl);
            List<Auftrag> geholt = new ArrayList<>();
            try (ResultSet zeile = holen.executeQuery()) {
                while (zeile.next()) {
                    lesen(verbindung, zeile, "LAEUFT").ifPresent(geholt::add);
                }
            }
            return geholt;
        } catch (SQLException e) {
            throw fehler("Aufträge nicht geholt", e);
        }
    }

    @Override
    public boolean lebenszeichen(Auftrag auftrag) {
        return aendern("update public.auftrag set lebenszeichen = now()" + ANSPRUCH, auftrag,
                SPERR_WARTEZEIT_SEKUNDEN);
    }

    @Override
    public boolean abschliessen(Auftrag auftrag) {
        return aendern("update public.auftrag set status = 'FERTIG', beendet = now(), lebenszeichen = now()"
                + ANSPRUCH, auftrag);
    }

    @Override
    public boolean fehlversuch(Auftrag auftrag, String fehler, Optional<Duration> wartezeit) {
        return fehlversuch(auftrag, fehler, wartezeit, null);
    }

    @Override
    public List<Verwaist> verwaiste(Map<AuftragTyp, Duration> zeitgrenzen) {
        if (zeitgrenzen.isEmpty()) {
            return List.of();
        }
        // left join: verwaiste Aufträge aller Mandanten freigeben, auch unbekannter (Zeile unlesbar → FEHLER)
        String sql = "select " + SPALTEN + ", coalesce(m.aktiv, false) as mandant_aktiv from public.auftrag a "
                + "join unnest(?::text[], ?::bigint[]) as g(typ, ms) on g.typ = a.typ "
                + "left join public.mandant m on m.code = a.mandant "
                + "where a.status = 'LAEUFT' "
                + "and coalesce(a.lebenszeichen, a.gestartet, a.erstellt) < now() - g.ms * interval '1 millisecond' "
                + "order by a.id";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            List<String> typen = new ArrayList<>();
            List<Long> grenzen = new ArrayList<>();
            zeitgrenzen.forEach((typ, grenze) -> {
                typen.add(typ.wert());
                grenzen.add(grenze.toMillis());
            });
            abfrage.setArray(1, verbindung.createArrayOf("text", typen.toArray()));
            abfrage.setArray(2, verbindung.createArrayOf("bigint", grenzen.toArray()));
            List<Verwaist> verwaist = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    boolean aktiv = zeile.getBoolean("mandant_aktiv");
                    lesen(verbindung, zeile, "LAEUFT").ifPresent(auftrag -> verwaist.add(new Verwaist(auftrag, aktiv)));
                }
            }
            return verwaist;
        } catch (SQLException e) {
            throw fehler("Verwaiste Aufträge nicht lesbar", e);
        }
    }

    @Override
    public boolean verwaistFreigeben(Auftrag auftrag, Duration zeitgrenze, String fehler, Optional<Duration> wartezeit) {
        return fehlversuch(auftrag, fehler, wartezeit, zeitgrenze);
    }

    @Override
    public boolean freigeben(Auftrag auftrag) {
        return aendern("update public.auftrag set status = 'OFFEN', nicht_vor = null, gestartet = null, "
                + "lebenszeichen = null" + ANSPRUCH, auftrag);
    }

    @Override
    public List<Fehlgeschlagen> ohneNachbearbeitung(Map<AuftragTyp, Duration> zeitgrenzen) {
        if (zeitgrenzen.isEmpty()) {
            return List.of();
        }
        String sql = "select " + SPALTEN + ", a.fehler, a.nachbearbeitung_versuche from public.auftrag a "
                + "join public.mandant m on m.code = a.mandant and m.aktiv "
                + "join unnest(?::text[], ?::bigint[]) as g(typ, ms) on g.typ = a.typ "
                + "where a.status = 'FEHLER' and a.nachbearbeitet is null "
                + "and a.beendet < now() - g.ms * interval '1 millisecond' "
                + "and a.nachbearbeitung_versuche < ? "
                + "and (a.nachbearbeitung_nicht_vor is null or a.nachbearbeitung_nicht_vor <= now()) "
                + "order by a.id";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            List<String> typen = new ArrayList<>();
            List<Long> grenzen = new ArrayList<>();
            zeitgrenzen.forEach((typ, grenze) -> {
                typen.add(typ.wert());
                grenzen.add(grenze.toMillis());
            });
            abfrage.setArray(1, verbindung.createArrayOf("text", typen.toArray()));
            abfrage.setArray(2, verbindung.createArrayOf("bigint", grenzen.toArray()));
            abfrage.setInt(3, Nachbearbeitung.MAX_VERSUCHE);
            List<Fehlgeschlagen> offen = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    Optional<Auftrag> auftrag = lesen(verbindung, zeile, "FEHLER");
                    if (auftrag.isPresent()) {
                        AuftragFehler fehler = AuftragFehler.lesen(zeile.getString("fehler"))
                                .orElseGet(() -> AuftragFehler.neu("unbekannt"));
                        offen.add(new Fehlgeschlagen(auftrag.get(), fehler,
                                zeile.getInt("nachbearbeitung_versuche")));
                    }
                }
            }
            return offen;
        } catch (SQLException e) {
            throw fehler("Fehlgeschlagene Aufträge nicht lesbar", e);
        }
    }

    @Override
    public boolean nachbearbeitet(Auftrag auftrag) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update public.auftrag set nachbearbeitet = now() "
                        + "where id = ? and status = 'FEHLER' and nachbearbeitet is null")) {
            aendern.setLong(1, auftrag.id());
            return aendern.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fehler("Nachbearbeitung nicht gespeichert", e);
        }
    }

    @Override
    public boolean nachbearbeitungGescheitert(Auftrag auftrag, int bisher, Optional<Duration> wartezeit) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update public.auftrag set "
                        + "nachbearbeitung_versuche = nachbearbeitung_versuche + 1, nachbearbeitung_nicht_vor = "
                        + "case when ?::bigint is null then null else now() + ?::bigint * interval '1 millisecond' end "
                        + "where id = ? and status = 'FEHLER' and nachbearbeitet is null "
                        + "and nachbearbeitung_versuche = ?")) {
            Long ms = wartezeit.map(Duration::toMillis).orElse(null);
            aendern.setObject(1, ms, Types.BIGINT);
            aendern.setObject(2, ms, Types.BIGINT);
            aendern.setLong(3, auftrag.id());
            aendern.setInt(4, bisher);
            return aendern.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fehler("Gescheiterte Nachbearbeitung nicht gespeichert", e);
        }
    }

    @Override
    public boolean anspruchSperren(Auftrag auftrag) {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Anspruch nur in einer laufenden Transaktion sperren");
        }
        // in der Transaktion liefert Agroal dieselbe Verbindung wie für Hibernate; close gibt sie erst am Ende frei
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement sperren =
                verbindung.prepareStatement("select 1 from public.auftrag" + ANSPRUCH + " for update")) {
            sperren.setLong(1, auftrag.id());
            sperren.setInt(2, auftrag.versuche());
            sperren.setQueryTimeout(ANSPRUCH_WARTEZEIT_SEKUNDEN);
            try (ResultSet zeile = sperren.executeQuery()) {
                return zeile.next();
            }
        } catch (SQLException e) {
            throw fehler("Anspruch nicht geprüft", e);
        }
    }

    @Override
    public boolean nachfolgerVorhanden(Auftrag vorgaenger, String schluessel) {
        // nach id geordnet: Index-Scan ab dem Vorgänger, die Folgeaufträge stehen direkt dahinter
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.auftrag where id > ? and mandant = ? "
                        + "and nutzlast ->> ? = ? order by id limit 1")) {
            abfrage.setLong(1, vorgaenger.id());
            abfrage.setString(2, vorgaenger.mandant().wert());
            abfrage.setString(3, schluessel);
            abfrage.setString(4, Long.toString(vorgaenger.id()));
            abfrage.setQueryTimeout(ANSPRUCH_WARTEZEIT_SEKUNDEN);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        } catch (SQLException e) {
            throw fehler("Folgeaufträge nicht geprüft", e);
        }
    }

    @Override
    public void typSperren(MandantCode mandant, AuftragTyp typ) {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Typ nur in einer laufenden Transaktion sperren");
        }
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement sperren =
                verbindung.prepareStatement("select 1 from pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            sperren.setString(1, "auftrag:" + mandant.wert() + ":" + typ.wert());
            sperren.setQueryTimeout(ANSPRUCH_WARTEZEIT_SEKUNDEN);
            sperren.executeQuery().close();
        } catch (SQLException e) {
            throw fehler("Typ nicht gesperrt", e);
        }
    }

    @Override
    public OptionalLong offen(MandantCode mandant, AuftragTyp typ, Optional<String> schluessel) {
        String sql = "select min(id) from public.auftrag where mandant = ? and typ = ? "
                + "and status in ('OFFEN', 'LAEUFT')" + (schluessel.isPresent() ? " and nutzlast ->> ? is not null" : "");
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, mandant.wert());
            abfrage.setString(2, typ.wert());
            if (schluessel.isPresent()) {
                abfrage.setString(3, schluessel.get());
            }
            abfrage.setQueryTimeout(ANSPRUCH_WARTEZEIT_SEKUNDEN);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                long id = zeile.getLong(1);
                return zeile.wasNull() ? OptionalLong.empty() : OptionalLong.of(id);
            }
        } catch (SQLException e) {
            throw fehler("Offene Aufträge nicht gelesen", e);
        }
    }

    @Override
    public boolean offenMit(MandantCode mandant, AuftragTyp typ, String schluessel, String wert) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.auftrag where status = 'OFFEN' and mandant = ? "
                        + "and typ = ? and nutzlast ->> ? = ? limit 1")) {
            abfrage.setString(1, mandant.wert());
            abfrage.setString(2, typ.wert());
            abfrage.setString(3, schluessel);
            abfrage.setString(4, wert);
            abfrage.setQueryTimeout(ANSPRUCH_WARTEZEIT_SEKUNDEN);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        } catch (SQLException e) {
            throw fehler("Offene Aufträge nicht gelesen", e);
        }
    }

    /**
     * Wandelt eine Zeile um. Ist sie unlesbar, setzt sie ihren Auftrag (sofern noch im erwarteten Status) mit Fehler-ID
     * auf {@code FEHLER} und gleich auf {@code nachbearbeitet}: Ohne lesbare Nutzlast kann kein Handler nachbearbeiten.
     */
    private static Optional<Auftrag> lesen(Connection verbindung, ResultSet zeile, String status) throws SQLException {
        long id = zeile.getLong("id");
        try {
            return Optional.of(auftrag(zeile));
        } catch (IllegalArgumentException | IllegalStateException e) {
            AuftragFehler fehler = AuftragFehler.neu(UNLESBAR);
            String ergebnis;
            try (PreparedStatement aendern = verbindung.prepareStatement("update public.auftrag set "
                    + "status = 'FEHLER', versuche = versuche + 1, fehler = ?, beendet = coalesce(beendet, now()), "
                    + "nachbearbeitet = now() where id = ? and versuche = ? and status = ?")) {
                aendern.setString(1, fehler.text());
                aendern.setLong(2, id);
                aendern.setInt(3, zeile.getInt("versuche"));
                aendern.setString(4, status);
                ergebnis = aendern.executeUpdate() == 1 ? ", Status FEHLER" : "";
            } catch (SQLException sql) {
                // die übrigen Zeilen laufen weiter; die Zeile bleibt, wie sie ist, und kommt beim Aufräumen wieder
                ergebnis = ", Status FEHLER nicht gespeichert (SQLState " + sql.getSQLState() + ")";
            }
            // ohne Ursache und Meldung: beide können Werte der Zeile enthalten
            LOG.errorf("Auftrag %d: Fehler %s, Zeile nicht lesbar (%s)%s, übersprungen", id, fehler.fehlerId(),
                    e.getClass().getName(), ergebnis);
            return Optional.empty();
        }
    }

    private boolean fehlversuch(Auftrag auftrag, String fehler, Optional<Duration> wartezeit, Duration stillSeit) {
        String sql = "update public.auftrag set versuche = versuche + 1, fehler = ?, "
                + (wartezeit.isPresent()
                ? "status = 'OFFEN', nicht_vor = now() + ? * interval '1 millisecond'"
                : "status = 'FEHLER', beendet = now(), nachbearbeitung_versuche = 0, nachbearbeitung_nicht_vor = null")
                + ANSPRUCH
                + (stillSeit == null ? ""
                : " and coalesce(lebenszeichen, gestartet, erstellt) < now() - ? * interval '1 millisecond'");
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            if (stillSeit != null) {
                aendern.setQueryTimeout(SPERR_WARTEZEIT_SEKUNDEN);
            }
            int i = 1;
            aendern.setString(i++, fehler);
            if (wartezeit.isPresent()) {
                aendern.setLong(i++, wartezeit.get().toMillis());
            }
            aendern.setLong(i++, auftrag.id());
            aendern.setInt(i++, auftrag.versuche());
            if (stillSeit != null) {
                aendern.setLong(i, stillSeit.toMillis());
            }
            return aendern.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fehler("Fehlversuch nicht gespeichert", e);
        }
    }

    private boolean aendern(String sql, Auftrag auftrag) {
        return aendern(sql, auftrag, 0);
    }

    /** @param sperrWartezeit Sekunden bis zum Abbruch, {@code 0} ohne Grenze */
    private boolean aendern(String sql, Auftrag auftrag, int sperrWartezeit) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            aendern.setQueryTimeout(sperrWartezeit);
            aendern.setLong(1, auftrag.id());
            aendern.setInt(2, auftrag.versuche());
            return aendern.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fehler("Auftrag nicht geändert", e);
        }
    }

    private static Auftrag auftrag(ResultSet zeile) throws SQLException {
        return new Auftrag(zeile.getLong("id"), new MandantCode(zeile.getString("mandant")),
                new AuftragTyp(zeile.getString("typ")), nutzlast(zeile.getString("nutzlast")), zeile.getInt("versuche"));
    }

    static String json(Nutzlast nutzlast) {
        Map<String, Object> werte = new LinkedHashMap<>();
        nutzlast.werte().forEach((schluessel, wert) -> werte.put(schluessel, wert instanceof UUID id ? id.toString() : wert));
        try {
            return JSON.writeValueAsString(werte);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Nutzlast nicht serialisierbar");
        }
    }

    /** JSON-Text gilt nur als UUID: Die Nutzlast kennt keinen freien Text. */
    static Nutzlast nutzlast(String json) {
        if (json == null) {
            return Nutzlast.leer();
        }
        Map<String, Object> roh;
        try {
            roh = JSON.readValue(json, KARTE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Nutzlast nicht lesbar");
        }
        Map<String, Object> werte = new LinkedHashMap<>();
        try {
            roh.forEach((schluessel, wert) -> werte.put(schluessel,
                    wert instanceof String text ? UUID.fromString(text) : wert));
            return new Nutzlast(werte);
        } catch (IllegalArgumentException e) {
            // ohne Ursache: die Meldung von UUID.fromString enthält den Wert
            throw new IllegalStateException("Nutzlast ungültig");
        }
    }

    static IllegalStateException fehler(String was, SQLException e) {
        // Ursache nur mit SQLState: Die Meldung von PostgreSQL kann Werte der Zeile enthalten. So zeigt das Log
        // (Ursachen.kette) den SQLState wie in 1.5.
        return new IllegalStateException(was + " (SQLState " + e.getSQLState() + ")",
                new SQLException((String) null, e.getSQLState()));
    }
}
