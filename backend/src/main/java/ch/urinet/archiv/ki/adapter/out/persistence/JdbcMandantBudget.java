package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.oeffentlich.MandantBudget;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@code public.mandant_budget} per JDBC (Spec 03, Req 6.4; Changeset {@code public/012}): ein Zeile je Mandant und Monat
 * (erster Tag, Monat in {@code Europe/Zurich}). Verbrauchen ist ein einziger {@code insert … on conflict do update}, damit
 * gleichzeitige Fragen nichts verlieren; jede Anweisung in eigener, kurzer Transaktion. Ohne Zeile gilt der Standard
 * {@code archiv.webseite.chat.monatsbudget-tokens}.
 */
@ApplicationScoped
public class JdbcMandantBudget implements MandantBudget {

    static final int ZEITGRENZE_S = 10;
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final Instance<AgroalDataSource> datenquelle;
    private final long standard;
    private final Clock uhr;

    @Inject
    public JdbcMandantBudget(Instance<AgroalDataSource> datenquelle,
                             @ConfigProperty(name = "archiv.webseite.chat.monatsbudget-tokens") long standard) {
        this(datenquelle, standard, Clock.systemUTC());
    }

    JdbcMandantBudget(Instance<AgroalDataSource> datenquelle, long standard, Clock uhr) {
        if (standard < 0) {
            throw new IllegalArgumentException("archiv.webseite.chat.monatsbudget-tokens darf nicht negativ sein");
        }
        this.datenquelle = datenquelle;
        this.standard = standard;
        this.uhr = uhr;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean erschoepft(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select tokens_max, tokens_verbraucht from public.mandant_budget where mandant = ? and monat = ?")) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            abfrage.setString(1, mandant.wert());
            abfrage.setDate(2, Date.valueOf(monat()));
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return standard <= 0;
                }
                return zeile.getLong(2) >= zeile.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Budget des Chatbots nicht gelesen", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void verbrauchen(MandantCode mandant, long tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Tokens negativ");
        }
        if (tokens == 0) {
            return;
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "insert into public.mandant_budget (mandant, monat, tokens_max, tokens_verbraucht) "
                             + "values (?, ?, ?, ?) on conflict (mandant, monat) do update set "
                             + "tokens_verbraucht = public.mandant_budget.tokens_verbraucht + excluded.tokens_verbraucht")) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            befehl.setString(1, mandant.wert());
            befehl.setDate(2, Date.valueOf(monat()));
            befehl.setLong(3, standard);
            befehl.setLong(4, tokens);
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Budget des Chatbots nicht geschrieben", e);
        }
    }

    private LocalDate monat() {
        return LocalDate.now(uhr.withZone(ZUERICH)).withDayOfMonth(1);
    }
}
