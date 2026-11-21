package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.MandantenRollen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class JdbcMandantenRollen implements MandantenRollen {
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcMandantenRollen(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Set<Rolle> lesen() {
        String schema = aufloesung.schema();
        if (MandantAufloesung.OHNE_MANDANT.equals(schema)) {
            throw new MandantVerweigert("Rollenmenge ohne gültigen Mandanten nicht lesbar");
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select unnest(verwaltung_rollen) from public.mandant where schema_name = ? and aktiv")) {
            abfrage.setString(1, schema);
            try (ResultSet zeilen = abfrage.executeQuery()) {
                Set<Rolle> rollen = EnumSet.noneOf(Rolle.class);
                while (zeilen.next()) {
                    String code = zeilen.getString(1);
                    if (code == null) {
                        throw new IllegalStateException("Rollenmenge des Mandanten ist ungültig");
                    }
                    rollen.add(Rolle.ausRealmRollen(List.of(code))
                            .orElseThrow(() -> new IllegalStateException("Rollenmenge des Mandanten ist ungültig")));
                }
                if (rollen.isEmpty()) {
                    throw new IllegalStateException("Rollenmenge für aktiven Mandanten fehlt");
                }
                return Set.copyOf(rollen);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Rollenmenge des Mandanten nicht lesbar", e);
        }
    }
}
