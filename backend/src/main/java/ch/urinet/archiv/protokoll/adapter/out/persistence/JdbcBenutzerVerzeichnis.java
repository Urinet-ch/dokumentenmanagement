package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code m_<code>.benutzer_verzeichnis} per JDBC (Req 10.10), Schema immer qualifiziert wie
 * {@link JdbcZugriffProtokoll}. Nur {@code select} und {@code insert}: Die App-Rolle hat auf der Tabelle keine anderen
 * Rechte, und ein Trigger verweigert UPDATE, DELETE und TRUNCATE.
 */
@ApplicationScoped
public class JdbcBenutzerVerzeichnis implements BenutzerVerzeichnis {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String SPALTEN = "id, zeit, benutzer, name, rolle, gueltig_von, gueltig_bis, anlass, erfasst_von";
    /** Namensräume der Sperren (erster Schlüssel), getrennt von {@code LiquibaseSitzungssperre} (0x4C42, 0x4C54). */
    static final int NAMENSRAUM_ABSCHLUSS = 0x4256;
    static final int NAMENSRAUM_EINFUEGEN = 0x4245;

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final MandantVerzeichnis mandanten;

    public JdbcBenutzerVerzeichnis(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                   MandantVerzeichnis mandanten) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.mandanten = mandanten;
    }

    @Override
    public Optional<VerzeichnisEintrag> letzter(Optional<MandantCode> mandant, String benutzer) {
        String sql = "select " + SPALTEN + " from " + schema(mandant) + ".benutzer_verzeichnis where benutzer = ? "
                + "order by id desc limit 1";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, benutzer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(eintrag(zeile)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerverzeichnis nicht lesbar", e);
        }
    }

    @Override
    public void einfuegen(Optional<MandantCode> mandant, Neu neu) {
        String sql = "insert into " + schema(mandant) + ".benutzer_verzeichnis (benutzer, name, rolle, gueltig_von, "
                + "gueltig_bis, anlass, erfasst_von) values (?, ?, ?, coalesce(?, clock_timestamp()), "
                // Ende nie vor dem Beginn, auch wenn die Uhr der App vor der der Datenbank geht
                + "case when ? then greatest(clock_timestamp(), coalesce(?, clock_timestamp())) end, ?, ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setString(1, neu.benutzer());
            einfuegen.setString(2, neu.name().orElse(null));
            einfuegen.setString(3, neu.rolle().orElse(null));
            OffsetDateTime gueltigVon = neu.gueltigVon().map(z -> z.atOffset(ZoneOffset.UTC)).orElse(null);
            einfuegen.setObject(4, gueltigVon, Types.TIMESTAMP_WITH_TIMEZONE);
            einfuegen.setBoolean(5, neu.beenden());
            einfuegen.setObject(6, gueltigVon, Types.TIMESTAMP_WITH_TIMEZONE);
            einfuegen.setString(7, neu.anlass().name());
            einfuegen.setString(8, neu.erfasstVon().orElse(null));
            einfuegen.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerverzeichnis nicht geschrieben", e);
        }
    }

    @Override
    public List<VerzeichnisEintrag> liste() {
        String sql = "select " + SPALTEN + " from " + schema(Optional.empty()) + ".benutzer_verzeichnis "
                + "order by benutzer, id";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql);
             ResultSet zeile = abfrage.executeQuery()) {
            List<VerzeichnisEintrag> liste = new ArrayList<>();
            while (zeile.next()) {
                liste.add(eintrag(zeile));
            }
            return liste;
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerverzeichnis nicht lesbar", e);
        }
    }

    /**
     * {@code pg_advisory_xact_lock} mit zwei Schlüsseln: Namensraum je Art der Sperre, dazu Schema und Kennung. Der
     * Namensraum {@code AKTION}/{@code ABSCHLUSS} ist derselbe (geteilt gegen exklusiv), {@code EINFUEGEN} ein eigener,
     * damit eine Aktion mit geteilter Sperre einfügen kann, ohne eine Sperre zu verschärfen (keine Verklemmung zwischen
     * zwei ersten Aktionen). Eine Kollision von {@code hashtext} lässt höchstens unnötig warten.
     */
    @Override
    public void sperren(Optional<MandantCode> mandant, String benutzer, Sperre sperre) {
        String sql = switch (sperre) {
            case AKTION -> "select pg_advisory_xact_lock_shared(?, hashtext(?))";
            case EINFUEGEN, ABSCHLUSS -> "select pg_advisory_xact_lock(?, hashtext(?))";
        };
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setInt(1, sperre == Sperre.EINFUEGEN ? NAMENSRAUM_EINFUEGEN : NAMENSRAUM_ABSCHLUSS);
            abfrage.setString(2, schema(mandant) + ":" + benutzer);
            abfrage.executeQuery().close();
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerverzeichnis nicht sperrbar", e);
        }
    }

    private String schema(Optional<MandantCode> mandant) {
        String schema = mandant.isPresent()
                ? mandanten.aktivesSchema(mandant.get()).orElseThrow(
                        () -> new IllegalStateException("Benutzerverzeichnis ohne aktiven Mandanten"))
                : aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Benutzerverzeichnis ohne Mandanten-Schema");
        }
        return schema;
    }

    private static VerzeichnisEintrag eintrag(ResultSet zeile) throws SQLException {
        return new VerzeichnisEintrag(zeile.getLong("id"), zeile.getObject("zeit", OffsetDateTime.class).toInstant(),
                zeile.getString("benutzer"), Optional.ofNullable(zeile.getString("name")),
                Optional.ofNullable(zeile.getString("rolle")),
                zeile.getObject("gueltig_von", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(zeile.getObject("gueltig_bis", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                VerzeichnisEintrag.Anlass.valueOf(zeile.getString("anlass")),
                Optional.ofNullable(zeile.getString("erfasst_von")));
    }
}
