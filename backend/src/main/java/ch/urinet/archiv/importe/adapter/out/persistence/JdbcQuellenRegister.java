package ch.urinet.archiv.importe.adapter.out.persistence;

import ch.urinet.archiv.importe.application.quelle.QuellenRegister;
import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.Quelle;
import ch.urinet.archiv.importe.domain.quelle.QuellenStatus;
import ch.urinet.archiv.importe.domain.quelle.QuellenTyp;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tabellen {@code quelle} und {@code quelle_lauf} im Schema des aktuellen Mandanten (Spec 09, design.md «Data
 * Models»). Das Geheimnis wird nie gelesen, nur ob es gesetzt ist.
 */
@ApplicationScoped
public class JdbcQuellenRegister implements QuellenRegister {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> MELDUNGEN = new TypeReference<>() { };
    private static final String LAUF_SPALTEN = "l.id, l.quelle_id, q.code, l.art, l.status, l.gefunden, l.neu, "
            + "l.dublette_pruefsumme, l.dublette_fachlich, l.uebersprungen, l.fehler, l.meldungen::text, l.benutzer, "
            + "l.gestartet, l.beendet";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcQuellenRegister(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public List<Quelle> liste() {
        return quellen(quelleAbfrage(schema()) + " order by q.code", List.of());
    }

    @Override
    public Optional<Quelle> finde(String code, boolean sperren) {
        return quellen(quelleAbfrage(schema()) + " where q.code = ?" + (sperren ? " for update of q" : ""),
                List.of(code)).stream().findFirst();
    }

    @Override
    public boolean anlegen(UUID id, String code, String name, QuellenTyp typ, String konfiguration,
                           Optional<String> bemerkung) {
        String sql = "insert into " + schema() + ".quelle (id, code, name, typ, konfiguration, bemerkung, geaendert) "
                + "values (?, ?, ?, ?, ?::jsonb, ?, clock_timestamp()) on conflict (code) do nothing";
        try (Connection verbindung = verbindung(); PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, id);
            befehl.setString(2, code);
            befehl.setString(3, name);
            befehl.setString(4, typ.name());
            befehl.setString(5, konfiguration);
            befehl.setString(6, bemerkung.orElse(null));
            return befehl.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fehler("Quelle nicht angelegt", e);
        }
    }

    @Override
    public Optional<Quelle> aendern(String code, int version, QuellenStatus status, String bemerkung,
                                    String konfiguration) {
        String sql = "update " + schema() + ".quelle set status = coalesce(?, status), "
                + "bemerkung = case when ?::text is null then bemerkung else nullif(?, '') end, "
                + "konfiguration = coalesce(?::jsonb, konfiguration), version = version + 1, "
                + "geaendert = clock_timestamp() where code = ? and version = ?";
        try (Connection verbindung = verbindung(); PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, status == null ? null : status.name());
            befehl.setString(2, bemerkung);
            befehl.setString(3, bemerkung);
            befehl.setString(4, konfiguration);
            befehl.setString(5, code);
            befehl.setInt(6, version);
            if (befehl.executeUpdate() != 1) {
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw fehler("Quelle nicht geändert", e);
        }
        return finde(code, false);
    }

    @Override
    public void geheimnisSetzen(String code, byte[] chiffrat) {
        ausfuehren("update " + schema() + ".quelle set geheimnis = ?, version = version + 1, "
                + "geaendert = clock_timestamp() where code = ?", List.of(chiffrat, code));
    }

    @Override
    public Optional<QuellLauf> laufenderLauf(UUID quelleId) {
        return laeufe(laufAbfrage(schema()) + " where l.quelle_id = ? and l.status = 'LAEUFT'", List.of(quelleId))
                .stream().findFirst();
    }

    @Override
    public QuellLauf laufAnlegen(UUID quelleId, LaufArt art, Optional<String> benutzer) {
        UUID id = UUID.randomUUID();
        String schema = schema();
        ausfuehren("insert into " + schema + ".quelle_lauf (id, quelle_id, art, status, benutzer, gestartet) "
                + "values (?, ?, ?, 'LAEUFT', ?, clock_timestamp())", List.of(id, quelleId, art.name(),
                benutzer.map(Object.class::cast).orElse(Leer.TEXT)));
        ausfuehren("update " + schema + ".quelle set letzter_lauf = ? where id = ?", List.of(id, quelleId));
        return lauf(id, false).orElseThrow();
    }

    @Override
    public Optional<QuellLauf> lauf(UUID id, boolean sperren) {
        return laeufe(laufAbfrage(schema()) + " where l.id = ?" + (sperren ? " for update of l" : ""), List.of(id))
                .stream().findFirst();
    }

    @Override
    public List<QuellLauf> laeufe(UUID quelleId, int hoechstens) {
        return laeufe(laufAbfrage(schema()) + " where l.quelle_id = ? order by l.gestartet desc, l.id limit ?",
                List.of(quelleId, hoechstens));
    }

    @Override
    public QuellLauf laufBeenden(UUID id, QuellLaufStatus status, Optional<Zaehler> zaehler,
                                 int zusaetzlichUebersprungen, List<String> meldungen) {
        String setzen = zaehler.isPresent()
                ? "gefunden = ?, neu = ?, dublette_pruefsumme = ?, dublette_fachlich = ?, uebersprungen = ?, fehler = ?"
                : "gefunden = gefunden + ?, uebersprungen = uebersprungen + ?";
        List<Object> werte = new ArrayList<>();
        zaehler.ifPresentOrElse(z -> werte.addAll(List.of(z.gefunden(), z.neu(), z.dublettePruefsumme(),
                z.dubletteFachlich(), z.uebersprungen(), z.fehler())), () -> {
                    werte.add(zusaetzlichUebersprungen);
                    werte.add(zusaetzlichUebersprungen);
                });
        werte.add(status.name());
        werte.add(json(meldungen));
        werte.add(id);
        ausfuehren("update " + schema() + ".quelle_lauf set " + setzen + ", status = ?, "
                + "meldungen = meldungen || ?::jsonb, beendet = clock_timestamp() where id = ? and status = 'LAEUFT'",
                werte);
        return lauf(id, false).orElseThrow();
    }

    @Override
    public void verbuchen(UUID laufId, int neu, int duplettePruefsumme, int fehler) {
        ausfuehren("update " + schema() + ".quelle_lauf set gefunden = gefunden + ?, neu = neu + ?, "
                + "dublette_pruefsumme = dublette_pruefsumme + ?, fehler = fehler + ? where id = ?",
                List.of(neu + duplettePruefsumme + fehler, neu, duplettePruefsumme, fehler, laufId));
    }

    @Override
    public void dokumentZuordnen(UUID dokumentId, UUID laufId, Optional<String> quelle) {
        ausfuehren("update " + schema() + ".dokument set quelle_lauf_id = ?, quelle = coalesce(?, quelle) "
                + "where id = ?", List.of(laufId, quelle.map(Object.class::cast).orElse(Leer.TEXT), dokumentId));
    }

    @Override
    public Set<String> vorhandenePruefsummen(Collection<String> sha256) {
        String sql = "select distinct f.sha256 from " + schema() + ".dokument_datei f join " + schema()
                + ".dokument d on d.id = f.dokument_id where f.rolle = 'ORIGINAL' and f.status <> 'VERWORFEN' "
                + "and d.status <> 'VERWORFEN' and f.sha256 = any (?)";
        Set<String> vorhanden = new HashSet<>();
        try (Connection verbindung = verbindung(); PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setArray(1, verbindung.createArrayOf("text", sha256.toArray()));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    vorhanden.add(zeile.getString(1));
                }
            }
        } catch (SQLException e) {
            throw fehler("Prüfsummen nicht gelesen", e);
        }
        return vorhanden;
    }

    /** Platzhalter für einen SQL-Wert {@code null} vom Typ Text in einer Werteliste. */
    private enum Leer { TEXT }

    private static String quelleAbfrage(String schema) {
        return "select q.id, q.code, q.name, q.typ, q.konfiguration::text, q.status, q.bemerkung, "
                + "q.geheimnis is not null, q.version, q.geaendert, " + LAUF_SPALTEN
                + " from " + schema + ".quelle q left join " + schema + ".quelle_lauf l on l.id = q.letzter_lauf";
    }

    private static String laufAbfrage(String schema) {
        return "select " + LAUF_SPALTEN + " from " + schema + ".quelle_lauf l join " + schema
                + ".quelle q on q.id = l.quelle_id";
    }

    private List<Quelle> quellen(String sql, List<Object> werte) {
        List<Quelle> quellen = new ArrayList<>();
        try (Connection verbindung = verbindung(); PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            setzen(abfrage, werte);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    Optional<QuellLauf> letzter = z.getObject(11) == null ? Optional.empty() : Optional.of(lauf(z, 11));
                    Timestamp geaendert = z.getTimestamp(10);
                    quellen.add(new Quelle(z.getObject(1, UUID.class), z.getString(2), z.getString(3),
                            QuellenTyp.valueOf(z.getString(4)), z.getString(5), QuellenStatus.valueOf(z.getString(6)),
                            Optional.ofNullable(z.getString(7)), z.getBoolean(8), letzter, z.getInt(9),
                            Optional.ofNullable(geaendert).map(Timestamp::toInstant)));
                }
            }
        } catch (SQLException e) {
            throw fehler("Quellen nicht gelesen", e);
        }
        return quellen;
    }

    private List<QuellLauf> laeufe(String sql, List<Object> werte) {
        List<QuellLauf> laeufe = new ArrayList<>();
        try (Connection verbindung = verbindung(); PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            setzen(abfrage, werte);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    laeufe.add(lauf(z, 1));
                }
            }
        } catch (SQLException e) {
            throw fehler("Quellläufe nicht gelesen", e);
        }
        return laeufe;
    }

    private static QuellLauf lauf(ResultSet z, int ab) throws SQLException {
        Timestamp beendet = z.getTimestamp(ab + 14);
        return new QuellLauf(z.getObject(ab, UUID.class), z.getObject(ab + 1, UUID.class), z.getString(ab + 2),
                LaufArt.valueOf(z.getString(ab + 3)), QuellLaufStatus.valueOf(z.getString(ab + 4)),
                new Zaehler(z.getInt(ab + 5), z.getInt(ab + 6), z.getInt(ab + 7), z.getInt(ab + 8), z.getInt(ab + 9),
                        z.getInt(ab + 10)), meldungen(z.getString(ab + 11)), Optional.ofNullable(z.getString(ab + 12)),
                z.getTimestamp(ab + 13).toInstant(), Optional.ofNullable(beendet).map(Timestamp::toInstant));
    }

    private void ausfuehren(String sql, List<Object> werte) {
        try (Connection verbindung = verbindung(); PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            setzen(befehl, werte);
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw fehler("Quellenregister nicht geschrieben", e);
        }
    }

    private static void setzen(PreparedStatement befehl, List<Object> werte) throws SQLException {
        for (int i = 0; i < werte.size(); i++) {
            Object wert = werte.get(i);
            if (wert == Leer.TEXT) {
                befehl.setNull(i + 1, Types.VARCHAR);
            } else if (wert instanceof byte[] bytes) {
                befehl.setBytes(i + 1, bytes);
            } else {
                befehl.setObject(i + 1, wert);
            }
        }
    }

    private static List<String> meldungen(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, MELDUNGEN);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String json(List<String> meldungen) {
        try {
            return JSON.writeValueAsString(meldungen);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Meldungen nicht als JSON", e);
        }
    }

    private Connection verbindung() throws SQLException {
        return datenquelle.get().getConnection();
    }

    private String schema() {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Quellenregister nur in einer Transaktion");
        }
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Quellenregister ohne Mandanten-Schema");
        }
        return schema;
    }

    private static IllegalStateException fehler(String meldung, SQLException e) {
        return new IllegalStateException(meldung + " (SQLState " + e.getSQLState() + ")", e);
    }
}
