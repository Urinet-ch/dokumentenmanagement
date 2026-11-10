package ch.urinet.archiv.sicherheit.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.ApiClientRegister;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code public.api_client} per JDBC wie {@code JdbcMandantVerzeichnis}: Die Anmeldung läuft vor dem Tenant-Resolver,
 * und eine Verbindung aus dem Pool kann noch auf einem Mandanten-Schema stehen; darum ist die Tabelle immer
 * qualifiziert. Ohne Cache, damit ein Widerruf sofort greift.
 */
@ApplicationScoped
public class JdbcApiClientRegister implements ApiClientRegister {

    private static final String SPALTEN = "id, mandant, name, scope, aktiv, erstellt, zuletzt";

    // lazy: ohne Datasource (z. B. Bereitschaftsprüfung) ist die Bean inaktiv
    private final Instance<AgroalDataSource> datenquelle;

    public JdbcApiClientRegister(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<Zugang> finden(String id) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select " + SPALTEN + ", geheimnis_hash from public.api_client where id = ?")) {
            abfrage.setString(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(new Zugang(client(zeile), zeile.getString("geheimnis_hash")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("API-Tokens nicht lesbar", e);
        }
    }

    @Override
    public void anlegen(ApiClient client, String hash) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into public.api_client (id, mandant, name, scope, geheimnis_hash, "
                        + "aktiv, erstellt) values (?, ?, ?, ?, ?, ?, ?)")) {
            einfuegen.setString(1, client.id());
            einfuegen.setString(2, client.mandant());
            einfuegen.setString(3, client.name());
            einfuegen.setString(4, client.scope().code());
            einfuegen.setString(5, hash);
            einfuegen.setBoolean(6, client.aktiv());
            einfuegen.setObject(7, utc(client.erstellt()));
            einfuegen.executeUpdate();
        } catch (SQLException e) {
            // ohne Ursache: die Meldung von PostgreSQL kann die ganze Zeile samt Hash enthalten
            throw new IllegalStateException("API-Token nicht gespeichert (SQLState " + e.getSQLState() + ")");
        }
    }

    @Override
    public List<ApiClient> liste(String mandant) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select " + SPALTEN + " from public.api_client where mandant = ? "
                        + "order by erstellt, id")) {
            abfrage.setString(1, mandant);
            List<ApiClient> liste = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    liste.add(client(zeile));
                }
            }
            return liste;
        } catch (SQLException e) {
            throw new IllegalStateException("API-Tokens nicht lesbar", e);
        }
    }

    @Override
    public boolean widerrufen(String mandant, String id) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update public.api_client set aktiv = false where id = ? and mandant = ?")) {
            aendern.setString(1, id);
            aendern.setString(2, mandant);
            return aendern.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("API-Token nicht widerrufen", e);
        }
    }

    @Override
    public void zuletztSetzen(String id, Instant zeit) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update public.api_client set zuletzt = ? where id = ?")) {
            aendern.setObject(1, utc(zeit));
            aendern.setString(2, id);
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("API-Token nicht nachgeführt", e);
        }
    }

    private static ApiClient client(ResultSet zeile) throws SQLException {
        String scope = zeile.getString("scope");
        OffsetDateTime zuletzt = zeile.getObject("zuletzt", OffsetDateTime.class);
        return new ApiClient(zeile.getString("id"), zeile.getString("mandant"), zeile.getString("name"),
                ApiTokenScope.ausCode(scope).orElseThrow(() -> new IllegalStateException("Scope unbekannt")),
                zeile.getBoolean("aktiv"), zeile.getObject("erstellt", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(zuletzt).map(OffsetDateTime::toInstant));
    }

    private static OffsetDateTime utc(Instant zeit) {
        return OffsetDateTime.ofInstant(zeit, ZoneOffset.UTC);
    }
}
