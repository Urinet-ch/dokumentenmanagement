package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.integration.application.mail.MailKonfigurationPort;
import ch.urinet.archiv.integration.domain.mail.Postfach;
import ch.urinet.archiv.integration.domain.mail.Zulassungsliste;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Tabelle {@code integration_mail} im Schema {@code m_<code>} per JDBC, Schema immer qualifiziert (läuft im Scheduler
 * und im Admin-Kommando ohne Mandant der Anfrage). Höchstens eine Zeile ({@code id = 1}).
 */
@ApplicationScoped
public class JdbcMailKonfiguration implements MailKonfigurationPort {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcMailKonfiguration(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<Einstellung> laden(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select host, port, benutzer, passwort_verschluesselt, "
                        + "zugelassene_absender, aktiv from " + tabelle(mandant) + " where id = 1")) {
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return Optional.empty();
                }
                Array absender = zeile.getArray(5);
                return Optional.of(new Einstellung(new Postfach(zeile.getString(1), zeile.getInt(2),
                        zeile.getString(3)), zeile.getBytes(4), Zulassungsliste.aus(List.of((String[]) absender
                        .getArray())), zeile.getBoolean(6)));
            }
        } catch (SQLException e) {
            throw fehler("Postfach nicht gelesen", e);
        }
    }

    @Override
    public void speichern(MandantCode mandant, Postfach postfach, byte[] passwortVerschluesselt,
                          Zulassungsliste zulassung) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("insert into " + tabelle(mandant) + " (id, host, port, benutzer, "
                        + "passwort_verschluesselt, zugelassene_absender, aktiv, fehler_in_folge, geaendert) "
                        + "values (1, ?, ?, ?, ?, ?, true, 0, clock_timestamp()) on conflict (id) do update set "
                        + "host = excluded.host, port = excluded.port, benutzer = excluded.benutzer, "
                        + "passwort_verschluesselt = excluded.passwort_verschluesselt, "
                        + "zugelassene_absender = excluded.zugelassene_absender, aktiv = true, fehler_in_folge = 0, "
                        + "geaendert = excluded.geaendert")) {
            aendern.setString(1, postfach.host());
            aendern.setInt(2, postfach.port());
            aendern.setString(3, postfach.benutzer());
            aendern.setBytes(4, passwortVerschluesselt);
            aendern.setArray(5, verbindung.createArrayOf("text", zulassung.adressen().stream().sorted()
                    .toArray(String[]::new)));
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw fehler("Postfach nicht gespeichert", e);
        }
    }

    @Override
    public boolean abschalten(MandantCode mandant) {
        return aendern(mandant, "aktiv = false, geaendert = clock_timestamp()") == 1;
    }

    @Override
    public int fehlschlagZaehlen(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + tabelle(mandant) + " set fehler_in_folge = fehler_in_folge "
                        + "+ 1 where id = 1 returning fehler_in_folge")) {
            try (ResultSet zeile = aendern.executeQuery()) {
                return zeile.next() ? zeile.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw fehler("Fehlschlag nicht gezählt", e);
        }
    }

    @Override
    public void erfolgreich(MandantCode mandant) {
        aendern(mandant, "fehler_in_folge = 0");
    }

    private int aendern(MandantCode mandant, String setzen) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + tabelle(mandant) + " set " + setzen + " where id = 1")) {
            return aendern.executeUpdate();
        } catch (SQLException e) {
            throw fehler("Postfach nicht geändert", e);
        }
    }

    private static String tabelle(MandantCode mandant) {
        return mandant.schema() + ".integration_mail";
    }

    /** Nur mit SQLState: Die Meldung von PostgreSQL kann Werte der Zeile enthalten (Req 16.7). */
    static IllegalStateException fehler(String was, SQLException e) {
        return new IllegalStateException(was + " (SQLState " + e.getSQLState() + ")",
                new SQLException((String) null, e.getSQLState()));
    }
}
