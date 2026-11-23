package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.DienstkontoAlarm;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JdbcDienstkontoAlarm implements DienstkontoAlarm {
    private final AgroalDataSource datenquelle;
    private final MandantAufloesung aufloesung;
    private final MandantRegister register;

    public JdbcDienstkontoAlarm(AgroalDataSource datenquelle, MandantAufloesung aufloesung, MandantRegister register) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.register = register;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void vormerken(int status) {
        String ereignis = switch (status) {
            case 401 -> "DIENSTKONTO_UNAUTORISIERT";
            case 403 -> "DIENSTKONTO_VERBOTEN";
            default -> throw new IllegalArgumentException("Kein Alarm für diesen Dienstkonto-Status");
        };
        String schema = aufloesung.schema();
        List<String> schemata = schema.equals(MandantAufloesung.OHNE_MANDANT)
                ? register.aktive().stream().map(m -> m.code().schema()).toList()
                : List.of(schema);
        UUID id = UUID.randomUUID();
        try (Connection verbindung = datenquelle.getConnection()) {
            for (String ziel : schemata) {
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "insert into " + ziel + ".alarm (schluessel) values (?)")) {
                    einfuegen.setString(1, "verwaltungsalarm:" + id + ":" + ereignis);
                    einfuegen.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dienstkonto-Alarm nicht vorgemerkt", e);
        }
    }
}
