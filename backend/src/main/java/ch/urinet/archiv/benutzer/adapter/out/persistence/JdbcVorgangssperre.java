package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.Vorgangssperre;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class JdbcVorgangssperre implements Vorgangssperre {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcVorgangssperre(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean belegen(MandantCode mandant, UUID vorgangId) {
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(vorgangId);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement sperre = verbindung.prepareStatement(
                     "select pg_try_advisory_xact_lock(1906, hashtext(?))")) {
            sperre.setString(1, mandant.wert());
            try (ResultSet ergebnis = sperre.executeQuery()) {
                if (!ergebnis.next()) {
                    throw new IllegalStateException("Vorgangssperre nicht geprüft");
                }
                if (!ergebnis.getBoolean(1)) {
                    return false;
                }
            }
            try (PreparedStatement befehl = verbindung.prepareStatement(
                    "insert into public.verwaltung_vorgang (mandant, vorgang_id, bis) "
                            + "values (?, ?, clock_timestamp() + interval '15 seconds') "
                            + "on conflict (mandant) do update set vorgang_id = excluded.vorgang_id, "
                            + "bis = excluded.bis where public.verwaltung_vorgang.phase = 'VORBEREITET' "
                            + "and public.verwaltung_vorgang.bis <= clock_timestamp()")) {
                befehl.setString(1, mandant.wert());
                befehl.setObject(2, vorgangId);
                return befehl.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Vorgangssperre nicht belegt", e);
        }
    }

    @Override
    public boolean anfragen(MandantCode mandant, UUID vorgangId) {
        transaktionPruefen();
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(vorgangId);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "update public.verwaltung_vorgang set phase = 'ANGEFRAGT' "
                             + "where mandant = ? and vorgang_id = ? and phase = 'VORBEREITET' "
                             + "and bis > clock_timestamp()")) {
            befehl.setString(1, mandant.wert());
            befehl.setObject(2, vorgangId);
            return befehl.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Vorgangssperre nicht angefragt", e);
        }
    }

    @Override
    public boolean abschliessen(MandantCode mandant, UUID vorgangId, Abschluss abschluss) {
        transaktionPruefen();
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(vorgangId);
        Objects.requireNonNull(abschluss);
        String sql = abschluss == Abschluss.UNKLAR
                ? "update public.verwaltung_vorgang set phase = 'UNKLAR' "
                : "delete from public.verwaltung_vorgang ";
        sql += "where mandant = ? and vorgang_id = ? and phase = 'ANGEFRAGT'";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, mandant.wert());
            befehl.setObject(2, vorgangId);
            return befehl.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Vorgangssperre nicht abgeschlossen", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean gueltig(MandantCode mandant, UUID vorgangId) {
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(vorgangId);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "select exists (select 1 from public.verwaltung_vorgang "
                             + "where mandant = ? and vorgang_id = ? and bis > clock_timestamp())")) {
            befehl.setString(1, mandant.wert());
            befehl.setObject(2, vorgangId);
            try (ResultSet ergebnis = befehl.executeQuery()) {
                if (!ergebnis.next()) {
                    throw new IllegalStateException("Vorgangssperre nicht geprüft");
                }
                return ergebnis.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Vorgangssperre nicht geprüft", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean freigeben(MandantCode mandant, UUID vorgangId) {
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(vorgangId);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "delete from public.verwaltung_vorgang "
                             + "where mandant = ? and vorgang_id = ? and phase = 'VORBEREITET'")) {
            befehl.setString(1, mandant.wert());
            befehl.setObject(2, vorgangId);
            return befehl.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Vorgangssperre nicht freigegeben", e);
        }
    }

    private static void transaktionPruefen() {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Vorgangsphase nur in einer Transaktion ändern");
        }
    }
}
