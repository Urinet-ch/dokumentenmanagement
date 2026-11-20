package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import ch.urinet.archiv.wissen.application.WissenEinstellungenRepository;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcWissenEinstellungen implements WissenEinstellungenRepository {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenbank;
    private final MandantAufloesung aufloesung;

    public JdbcWissenEinstellungen(Instance<AgroalDataSource> datenbank, MandantAufloesung aufloesung) {
        this.datenbank = datenbank;
        this.aufloesung = aufloesung;
    }

    @Override
    public WissenEinstellungen.Werte lesen() {
        String sql = "select budget_tokens, lint_budget_tokens, lint_zeitplan, monatsbudget_tokens from "
                + schema() + ".wissens_einstellung where id = 1";
        try (Connection c = datenbank.get().getConnection(); PreparedStatement abfrage = c.prepareStatement(sql)) {
            abfrage.setQueryTimeout(30);
            try (ResultSet rs = abfrage.executeQuery()) {
                if (!rs.next()) {
                    return new WissenEinstellungen.Werte(null, null, null, null);
                }
                return new WissenEinstellungen.Werte(rs.getObject(1, Integer.class), rs.getObject(2, Integer.class),
                        rs.getString(3), rs.getObject(4, Integer.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Wissenseinstellungen nicht gelesen", e);
        }
    }

    @Override
    public void speichern(WissenEinstellungen.Werte werte) {
        String sql = "insert into " + schema()
                + ".wissens_einstellung (id, budget_tokens, lint_budget_tokens, lint_zeitplan, monatsbudget_tokens) "
                + "values (1, ?, ?, ?, ?) on conflict (id) do update set budget_tokens = excluded.budget_tokens, "
                + "lint_budget_tokens = excluded.lint_budget_tokens, lint_zeitplan = excluded.lint_zeitplan, "
                + "monatsbudget_tokens = excluded.monatsbudget_tokens";
        try (Connection c = datenbank.get().getConnection(); PreparedStatement befehl = c.prepareStatement(sql)) {
            befehl.setQueryTimeout(30);
            befehl.setObject(1, werte.budgetTokens());
            befehl.setObject(2, werte.lintBudgetTokens());
            befehl.setString(3, werte.lintZeitplan());
            befehl.setObject(4, werte.monatsbudgetTokens());
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Wissenseinstellungen nicht gespeichert", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Wissenseinstellungen ohne Mandanten-Schema");
        }
        return schema;
    }
}
