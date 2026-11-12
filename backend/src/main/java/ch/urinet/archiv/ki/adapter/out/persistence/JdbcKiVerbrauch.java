package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Schreibt je Aufruf des Sprachmodells eine Zeile in {@code m_<code>.ki_verbrauch} des Mandanten aus dem Kontext
 * (design.md «KI», {@code KiVerbrauch}; Schema immer qualifiziert). Eigene Transaktion, damit der Verbrauch auch dann
 * bleibt, wenn der Auftrag danach scheitert. Nur Zweck, Modell und Tokens, nie Inhalte.
 */
@ApplicationScoped
public class JdbcKiVerbrauch implements KiVerbrauchPort {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcKiVerbrauch(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void erfassen(String zweck, KiPort.Verbrauch verbrauch) {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("KI-Verbrauch ohne Mandanten-Schema");
        }
        String sql = "insert into " + schema + ".ki_verbrauch (zweck, modell, tokens_ein, tokens_aus) values (?, ?, ?, ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setString(1, zweck);
            einfuegen.setString(2, verbrauch.modell());
            einfuegen.setInt(3, (int) Math.min(Integer.MAX_VALUE, Math.max(0, verbrauch.tokensEin())));
            einfuegen.setInt(4, (int) Math.min(Integer.MAX_VALUE, Math.max(0, verbrauch.tokensAus())));
            einfuegen.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("KI-Verbrauch nicht geschrieben", e);
        }
    }
}
