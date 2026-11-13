package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import ch.urinet.archiv.protokoll.application.ManifestErzeugen;
import ch.urinet.archiv.protokoll.domain.Jcs;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.ProtokollHash;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.protokoll.domain.Vorher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manifest-Daten per JDBC im Schema des Mandanten aus dem Kontext (Schema immer qualifiziert, design.md «Mandant»).
 * Lesen und Schreiben nur in der JTA-Transaktion des Aufrufers, jede Transaktion mit {@code statement_timeout}.
 * <p>
 * Blatt je Protokollzeile (design.md «Manifest», Wahl «SHA-256 je Zeile»): {@code SHA-256(JCS(zeile))} über alle
 * Spalten der Zeile mit ihren Spaltennamen; {@code zeit} als UTC-Instant, UUID und {@code inet} als Text, JSONB als
 * Baum.
 */
@ApplicationScoped
public class JdbcManifestDaten implements ManifestDaten {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String ZEITGRENZE = "select set_config('statement_timeout', '60s', true)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> BAUM = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, String>>> PRUEFDATEN = new TypeReference<>() {
    };

    /** Spalten je Tabelle; {@code id} und {@code zeit} sind Pflicht. */
    private static final Map<String, String> SPALTEN = Map.of(
            ProtokollHash.ZUGRIFF_PROTOKOLL,
            "id, zeit, benutzer, rolle, aktion, dokument_id::text as dokument_id, ip::text as ip, details::text as details",
            ProtokollHash.DOKUMENT_VERSION,
            "id, zeit, dokument_id::text as dokument_id, version, daten::text as daten, geaendert_von, grund");
    private static final java.util.Set<String> JSONB = java.util.Set.of("details", "daten");

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcManifestDaten(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Optional<ManifestStand> letztes() {
        return lesenStand("order by datum desc limit 1", null);
    }

    @Override
    public Optional<ManifestStand> stand(LocalDate tag) {
        return lesenStand("where datum = ?", java.util.Objects.requireNonNull(tag, "datum"));
    }

    private Optional<ManifestStand> lesenStand(String auswahl, LocalDate tag) {
        String s = schema();
        try (Connection v = verbindung()) {
            LocalDate datum;
            Instant bis;
            String sha256;
            String sig;
            try (PreparedStatement p = v.prepareStatement("select datum, bis, sha256, sig_sha256 from " + s
                    + ".manifest " + auswahl)) {
                if (tag != null) {
                    p.setObject(1, tag);
                }
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) {
                        return Optional.empty();
                    }
                    datum = r.getObject(1, LocalDate.class);
                    bis = r.getObject(2, OffsetDateTime.class).toInstant();
                    sha256 = r.getString(3);
                    sig = r.getString(4);
                }
            }
            List<Zeitstempel> stempel = new ArrayList<>();
            try (PreparedStatement p = v.prepareStatement("select dienst, gen_time, tsr_sha256, s3_schluessel, "
                    + "s3_version, pruefdaten::text from " + s + ".manifest_zeitstempel where datum = ? order by dienst")) {
                p.setObject(1, datum);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        List<Pruefdatei> pruefdaten = JSON.readValue(r.getString(6), PRUEFDATEN).stream()
                                .map(m -> new Pruefdatei(m.get("art"), m.get("sha256"), m.get("s3_version")))
                                .toList();
                        stempel.add(new Zeitstempel(r.getString(1), r.getObject(2, OffsetDateTime.class).toInstant(),
                                new ch.urinet.archiv.ablage.domain.AbgelegteDatei(r.getString(4), r.getString(5),
                                        r.getString(3), 0, Optional.empty()),
                                pruefdaten));
                    }
                }
            }
            Vorher verweis = ManifestErzeugen.verweis(sig, stempel);
            return Optional.of(new ManifestStand(datum, bis, sha256, verweis));
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Manifest nicht gelesen", e);
        }
    }

    @Override
    public List<ManifestEintrag> eintraege(Instant von, Instant bis) {
        String s = schema();
        String sql = "select d.id, lower(f.rolle), f.nr, f.sha256, f.s3_version from " + s + ".dokument d join " + s
                + ".dokument_datei f on f.dokument_id = d.id and f.status = 'AKTIV' where " + fenster("d.archiviert")
                + " union all select d.id, 'pdfa', 1, d.sha256_pdfa, d.s3_version_pdfa from " + s + ".dokument d "
                + "where d.sha256_pdfa is not null and " + fenster("d.archiviert");
        try (Connection v = verbindung(); PreparedStatement p = v.prepareStatement(sql)) {
            int i = fensterSetzen(p, 1, von, bis);
            fensterSetzen(p, i, von, bis);
            List<ManifestEintrag> liste = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    liste.add(new ManifestEintrag(r.getObject(1, UUID.class), r.getString(2), r.getInt(3),
                            r.getString(4), r.getString(5)));
                }
            }
            return liste;
        } catch (SQLException e) {
            throw new IllegalStateException("Einträge des Manifests nicht gelesen", e);
        }
    }

    @Override
    public Fristen fristen(Instant von, Instant bis) {
        String s = schema();
        String sql = "select count(*), bool_or(aufbewahren_bis is null), max(aufbewahren_bis) from " + s
                + ".dokument where " + fenster("archiviert");
        try (Connection v = verbindung(); PreparedStatement p = v.prepareStatement(sql)) {
            fensterSetzen(p, 1, von, bis);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                long anzahl = r.getLong(1);
                boolean unbefristet = r.getBoolean(2);
                Date spaetestens = r.getDate(3);
                return new Fristen(anzahl, unbefristet,
                        Optional.ofNullable(spaetestens).map(Date::toLocalDate));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Fristen des Manifests nicht gelesen", e);
        }
    }

    @Override
    public List<Blatt> blaetter(String tabelle, Instant von, Instant bis) {
        String spalten = SPALTEN.get(tabelle);
        if (spalten == null) {
            throw new IllegalArgumentException("Unbekannte Protokolltabelle");
        }
        String sql = "select " + spalten + " from " + schema() + "." + tabelle + " where " + fenster("zeit")
                + " order by id";
        try (Connection v = verbindung(); PreparedStatement p = v.prepareStatement(sql)) {
            fensterSetzen(p, 1, von, bis);
            List<Blatt> liste = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                int spaltenzahl = r.getMetaData().getColumnCount();
                while (r.next()) {
                    Map<String, Object> zeile = new LinkedHashMap<>();
                    for (int i = 1; i <= spaltenzahl; i++) {
                        String name = r.getMetaData().getColumnLabel(i);
                        zeile.put(name, wert(name, r, i));
                    }
                    liste.add(new Blatt(r.getLong("id"), Sha256.hexVon(Jcs.utf8(zeile))));
                }
            }
            return liste;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Protokollzeilen nicht gelesen", e);
        }
    }

    private static Object wert(String name, ResultSet r, int i) throws SQLException, JsonProcessingException {
        if (name.equals("zeit")) {
            return r.getObject(i, OffsetDateTime.class).toInstant().toString();
        }
        if (name.equals("id") || name.equals("version")) {
            long zahl = r.getLong(i);
            return r.wasNull() ? null : zahl;
        }
        String text = r.getString(i);
        if (text != null && JSONB.contains(name)) {
            return JSON.readValue(text, BAUM);
        }
        return text;
    }

    @Override
    public void festhalten(NeuesManifest manifest) {
        String s = schema();
        ManifestInhalt inhalt = manifest.inhalt();
        try (Connection v = verbindung()) {
            Map<String, Object> protokolle = new LinkedHashMap<>();
            inhalt.protokolle().forEach((t, h) -> protokolle.put(t, Map.of("anzahl", h.anzahl(), "sha256", h.sha256())));
            try (PreparedStatement p = v.prepareStatement("insert into " + s + ".manifest (datum, von, bis, sha256, "
                    + "vorher_sha256, anzahl, protokolle, s3_schluessel, s3_version, sig_sha256, sig_s3_version, "
                    + "sperre_bis) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)")) {
                p.setObject(1, inhalt.datum());
                p.setObject(2, zeit(inhalt.von()), Types.TIMESTAMP_WITH_TIMEZONE);
                p.setObject(3, zeit(inhalt.bis()), Types.TIMESTAMP_WITH_TIMEZONE);
                p.setString(4, manifest.json().sha256());
                p.setString(5, inhalt.vorherSha256());
                p.setInt(6, inhalt.eintraege().size());
                p.setString(7, JSON.writeValueAsString(protokolle));
                p.setString(8, manifest.json().schluessel());
                p.setString(9, manifest.json().versionId());
                p.setString(10, manifest.sig().sha256());
                p.setString(11, manifest.sig().versionId());
                p.setObject(12, manifest.json().sperreBis().map(JdbcManifestDaten::zeit).orElse(null),
                        Types.TIMESTAMP_WITH_TIMEZONE);
                p.executeUpdate();
            }
            try (PreparedStatement p = v.prepareStatement("insert into " + s + ".manifest_zeitstempel (datum, dienst, "
                    + "gen_time, tsr_sha256, s3_schluessel, s3_version, pruefdaten) values (?, ?, ?, ?, ?, ?, "
                    + "cast(? as jsonb))")) {
                for (Zeitstempel z : manifest.zeitstempel()) {
                    List<Map<String, String>> pruefdaten = new ArrayList<>();
                    for (Pruefdatei d : z.pruefdaten()) {
                        Map<String, String> eintrag = new LinkedHashMap<>();
                        eintrag.put("art", d.art());
                        eintrag.put("sha256", d.sha256());
                        eintrag.put("s3_version", d.s3Version());
                        pruefdaten.add(eintrag);
                    }
                    p.setObject(1, inhalt.datum());
                    p.setString(2, z.dienst());
                    p.setObject(3, zeit(z.genTime()), Types.TIMESTAMP_WITH_TIMEZONE);
                    p.setString(4, z.tsr().sha256());
                    p.setString(5, z.tsr().schluessel());
                    p.setString(6, z.tsr().versionId());
                    p.setString(7, JSON.writeValueAsString(pruefdaten));
                    p.addBatch();
                }
                p.executeBatch();
            }
            try (PreparedStatement p = v.prepareStatement("insert into " + s + ".protokoll_blatt (tabelle, zeile_id, "
                    + "manifest_datum, sha256) values (?, ?, ?, ?)")) {
                int offen = 0;
                for (Map.Entry<String, List<Blatt>> tabelle : manifest.blaetter().entrySet()) {
                    for (Blatt b : tabelle.getValue()) {
                        p.setString(1, tabelle.getKey());
                        p.setLong(2, b.zeileId());
                        p.setObject(3, inhalt.datum());
                        p.setString(4, b.sha256());
                        p.addBatch();
                        if (++offen == 1000) {
                            p.executeBatch();
                            offen = 0;
                        }
                    }
                }
                if (offen > 0) {
                    p.executeBatch();
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Manifest nicht festgehalten", e);
        }
    }

    @Override
    public Optional<Sperre> sperren() {
        String schluessel = "manifest:" + schema();
        Connection v = null;
        try {
            v = datenquelle.get().getConnection();
            boolean erhalten;
            try (PreparedStatement p = v.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))")) {
                p.setString(1, schluessel);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    erhalten = r.getBoolean(1);
                }
            }
            if (!erhalten) {
                v.close();
                return Optional.empty();
            }
            Connection gehalten = v;
            return Optional.of(() -> freigeben(gehalten, schluessel));
        } catch (SQLException e) {
            schliessen(v);
            throw new IllegalStateException("Sperre der Manifest-Erzeugung nicht gesetzt", e);
        }
    }

    private static void freigeben(Connection v, String schluessel) {
        try (v; PreparedStatement p = v.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
            p.setString(1, schluessel);
            p.execute();
        } catch (SQLException e) {
            // die Sperre endet spätestens mit der Verbindung
            throw new IllegalStateException("Sperre der Manifest-Erzeugung nicht freigegeben", e);
        }
    }

    private static void schliessen(Connection v) {
        if (v != null) {
            try {
                v.close();
            } catch (SQLException ignoriert) {
                // schon im Fehlerfall
            }
        }
    }

    private Connection verbindung() throws SQLException {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Manifest-Daten nur in einer Transaktion");
        }
        Connection v = datenquelle.get().getConnection();
        try (PreparedStatement p = v.prepareStatement(ZEITGRENZE)) {
            p.execute();
        } catch (SQLException e) {
            v.close();
            throw e;
        }
        return v;
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Manifest ohne Mandanten-Schema");
        }
        return schema;
    }

    private static String fenster(String spalte) {
        return "(cast(? as timestamptz) is null or " + spalte + " >= ?) and " + spalte + " < ?";
    }

    private static int fensterSetzen(PreparedStatement p, int start, Instant von, Instant bis) throws SQLException {
        p.setObject(start, zeit(von), Types.TIMESTAMP_WITH_TIMEZONE);
        p.setObject(start + 1, zeit(von), Types.TIMESTAMP_WITH_TIMEZONE);
        p.setObject(start + 2, zeit(bis), Types.TIMESTAMP_WITH_TIMEZONE);
        return start + 3;
    }

    private static OffsetDateTime zeit(Instant zeitpunkt) {
        return zeitpunkt == null ? null : zeitpunkt.atOffset(ZoneOffset.UTC);
    }
}
