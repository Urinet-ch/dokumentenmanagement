package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.WebseitenErfassungPort;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** JDBC-Zugriff auf {@code webseite_referenz} im Schema des verifizierten Mandanten. */
@ApplicationScoped
public class SqlWebseitenErfassung implements WebseitenErfassungPort {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public SqlWebseitenErfassung(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public void sperren(String webseite, String referenz) {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Referenzsperre nur in einer Transaktion");
        }
        String sql = "select pg_advisory_xact_lock(hashtext(?), hashtext(?))";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, schema());
            befehl.setString(2, webseite + ":" + referenz);
            befehl.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Webseiten-Referenz nicht gesperrt", e);
        }
    }

    @Override
    public Optional<Referenz> referenzFinden(String webseite, String referenz) {
        String sql = "select w.dokument_id, w.sha256, d.status from " + schema()
                + ".webseite_referenz w join " + schema()
                + ".dokument d on d.id = w.dokument_id where w.webseite = ? and w.referenz = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, webseite);
            befehl.setString(2, referenz);
            try (ResultSet zeile = befehl.executeQuery()) {
                return zeile.next() ? Optional.of(new Referenz(zeile.getObject(1, UUID.class), zeile.getString(2),
                        zeile.getString(3))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Webseiten-Referenz nicht gelesen", e);
        }
    }

    @Override
    public void referenzSetzen(String webseite, String referenz, UUID dokumentId, String sha256) {
        String sql = "insert into " + schema()
                + ".webseite_referenz (webseite, referenz, dokument_id, sha256) values (?, ?, ?, ?) "
                + "on conflict (webseite, referenz) do update set dokument_id = excluded.dokument_id, "
                + "sha256 = excluded.sha256, erstellt = clock_timestamp()";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, webseite);
            befehl.setString(2, referenz);
            befehl.setObject(3, dokumentId);
            befehl.setString(4, sha256);
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Webseiten-Referenz nicht gespeichert", e);
        }
    }

    @Override
    public void referenzLoeschen(String webseite, String referenz) {
        String sql = "delete from " + schema() + ".webseite_referenz where webseite = ? and referenz = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, webseite);
            befehl.setString(2, referenz);
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Verworfene Webseiten-Referenz nicht freigegeben", e);
        }
    }

    @Override
    public Map<UUID, String> referenzen(String webseite, List<UUID> dokumente) {
        if (dokumente.isEmpty()) {
            return Map.of();
        }
        String sql = "select dokument_id, referenz from " + schema()
                + ".webseite_referenz where webseite = ? and dokument_id = any (?) order by erstellt, referenz";
        try (Connection verbindung = datenquelle.get().getConnection()) {
            Array ids = verbindung.createArrayOf("uuid", dokumente.toArray(UUID[]::new));
            try {
                try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
                    befehl.setString(1, webseite);
                    befehl.setArray(2, ids);
                    try (ResultSet zeilen = befehl.executeQuery()) {
                        Map<UUID, String> result = new LinkedHashMap<>();
                        while (zeilen.next()) {
                            result.putIfAbsent(zeilen.getObject(1, UUID.class), zeilen.getString(2));
                        }
                        return Map.copyOf(result);
                    }
                }
            } finally {
                ids.free();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Webseiten-Referenzen nicht gelesen", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Webseiten-Erfassung ohne Mandanten-Schema");
        }
        return schema;
    }
}
