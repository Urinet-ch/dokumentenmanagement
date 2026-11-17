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
 * {@code public.mandant_budget} per JDBC (Spec 03, Req 6.4; Changesets {@code public/012} und {@code public/038}): eine
 * Zeile je Mandant und Monat
 * (erster Tag, Monat in {@code Europe/Zurich}). Verbrauchen ist ein einziger {@code insert … on conflict do update}, damit
 * gleichzeitige Fragen nichts verlieren. Eine bedingte Aktualisierung reserviert atomar den einzigen laufenden Platz des
 * Monats; jede Anweisung läuft in einer kurzen Transaktion. Ohne Zeile gilt der Standard
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
    public Reservierung reservieren(MandantCode mandant) {
        LocalDate monat = monat();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement anlegen = verbindung.prepareStatement(
                     "insert into public.mandant_budget (mandant, monat, tokens_max, tokens_verbraucht) "
                             + "values (?, ?, ?, 0) on conflict (mandant, monat) do nothing")) {
            anlegen.setQueryTimeout(ZEITGRENZE_S);
            anlegen.setString(1, mandant.wert());
            anlegen.setDate(2, Date.valueOf(monat));
            anlegen.setLong(3, standard);
            anlegen.executeUpdate();
            try (PreparedStatement reservieren = verbindung.prepareStatement(
                    "update public.mandant_budget set frage_laufend = true where mandant = ? and monat = ? "
                            + "and tokens_verbraucht < tokens_max and not frage_laufend");
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select frage_laufend, tokens_verbraucht >= tokens_max from public.mandant_budget "
                                 + "where mandant = ? and monat = ?")) {
                for (int versuch = 0; versuch < 2; versuch++) {
                    reservieren.setQueryTimeout(ZEITGRENZE_S);
                    reservieren.setString(1, mandant.wert());
                    reservieren.setDate(2, Date.valueOf(monat));
                    if (reservieren.executeUpdate() == 1) {
                        return new Reservierung(ReservierungsStatus.RESERVIERT, monat);
                    }
                    abfrage.setQueryTimeout(ZEITGRENZE_S);
                    abfrage.setString(1, mandant.wert());
                    abfrage.setDate(2, Date.valueOf(monat));
                    try (ResultSet zeile = abfrage.executeQuery()) {
                        if (!zeile.next()) {
                            throw new IllegalStateException("Budget des Chatbots nicht vorhanden");
                        }
                        if (zeile.getBoolean(1)) {
                            return new Reservierung(ReservierungsStatus.BEREITS_LAUFEND, monat);
                        }
                        if (zeile.getBoolean(2)) {
                            return new Reservierung(ReservierungsStatus.BUDGET_ERSCHOEPFT, monat);
                        }
                    }
                }
            }
            return new Reservierung(ReservierungsStatus.BEREITS_LAUFEND, monat);
        } catch (SQLException e) {
            throw new IllegalStateException("Budget des Chatbots nicht reserviert", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void freigeben(MandantCode mandant, LocalDate monat) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "update public.mandant_budget set frage_laufend = false "
                             + "where mandant = ? and monat = ? and frage_laufend")) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            befehl.setString(1, mandant.wert());
            befehl.setDate(2, Date.valueOf(monat));
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Budget-Reservierung des Chatbots nicht freigegeben", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void verbrauchen(MandantCode mandant, LocalDate monat, long tokens) {
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
            befehl.setDate(2, Date.valueOf(monat));
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
