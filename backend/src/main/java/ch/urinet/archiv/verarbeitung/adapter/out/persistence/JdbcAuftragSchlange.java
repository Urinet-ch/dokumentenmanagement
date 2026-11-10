package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code public.auftrag} per JDBC (ADR-003): Holen mit {@code FOR UPDATE SKIP LOCKED} in einer Anweisung, Tabelle immer
 * qualifiziert, weil eine Verbindung in einer Transaktion auf dem Schema des Mandanten stehen kann. Jede Änderung an
 * einem geholten Auftrag prüft den Anspruch ({@code id}, {@code versuche}, {@code LAEUFT}). Fehler tragen nur den
 * SQLState weiter, nie die Meldung von PostgreSQL, die Werte enthalten kann.
 */
@ApplicationScoped
public class JdbcAuftragSchlange implements AuftragSchlange {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> KARTE = new TypeReference<>() {
    };
    private static final String SPALTEN = "a.id, a.mandant, a.typ, a.nutzlast::text as nutzlast, a.versuche";
    private static final String ANSPRUCH = " where id = ? and versuche = ? and status = 'LAEUFT'";

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
                    geholt.add(auftrag(zeile));
                }
            }
            return geholt;
        } catch (SQLException e) {
            throw fehler("Aufträge nicht geholt", e);
        }
    }

    @Override
    public boolean lebenszeichen(Auftrag auftrag) {
        return aendern("update public.auftrag set lebenszeichen = now()" + ANSPRUCH, auftrag);
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
    public List<Auftrag> verwaiste(Map<AuftragTyp, Duration> zeitgrenzen) {
        if (zeitgrenzen.isEmpty()) {
            return List.of();
        }
        String sql = "select " + SPALTEN + " from public.auftrag a "
                + "join unnest(?::text[], ?::bigint[]) as g(typ, ms) on g.typ = a.typ "
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
            List<Auftrag> verwaist = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    verwaist.add(auftrag(zeile));
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

    private boolean fehlversuch(Auftrag auftrag, String fehler, Optional<Duration> wartezeit, Duration stillSeit) {
        String sql = "update public.auftrag set versuche = versuche + 1, fehler = ?, "
                + (wartezeit.isPresent()
                ? "status = 'OFFEN', nicht_vor = now() + ? * interval '1 millisecond'"
                : "status = 'FEHLER', beendet = now()")
                + ANSPRUCH
                + (stillSeit == null ? ""
                : " and coalesce(lebenszeichen, gestartet, erstellt) < now() - ? * interval '1 millisecond'");
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
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
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
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

    private static IllegalStateException fehler(String was, SQLException e) {
        // ohne Ursache: die Meldung von PostgreSQL kann Werte der Zeile enthalten
        return new IllegalStateException(was + " (SQLState " + e.getSQLState() + ")");
    }
}
