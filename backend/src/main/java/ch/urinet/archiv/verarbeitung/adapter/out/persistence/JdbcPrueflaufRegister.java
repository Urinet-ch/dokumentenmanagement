package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link PrueflaufRegister} per JDBC wie {@code JdbcKontaktRegister}: Schema aus dem Mandanten-Kontext, immer
 * qualifiziert, nur in einer Transaktion (die Verbindung nimmt an ihr teil).
 */
@ApplicationScoped
public class JdbcPrueflaufRegister implements PrueflaufRegister {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String ZEITGRENZE = "select set_config('statement_timeout', '30s', true)";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcPrueflaufRegister(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Optional<UUID> anlegen(Optional<String> monat, Stufe stufe) {
        String schema = schema();
        UUID id = UUID.randomUUID();
        try (Connection v = verbindung(); PreparedStatement s = v.prepareStatement("insert into " + schema
                + ".prueflauf (id, monat, stufe) values (?, ?, ?) on conflict (monat) do nothing")) {
            s.setObject(1, id);
            s.setString(2, monat.orElse(null));
            s.setString(3, stufe.name());
            return s.executeUpdate() == 1 ? Optional.of(id) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Prüflauf nicht angelegt", e);
        }
    }

    @Override
    public Optional<Lauf> laden(UUID id) {
        String schema = schema();
        try (Connection v = verbindung(); PreparedStatement s = v.prepareStatement("select stufe, teil, phase, "
                + "nach_dokument, marke_schluessel, marke_version, dateien, versionen, manifeste, verlaengert, "
                + "nur_gezaehlt, abweichungen from " + schema + ".prueflauf where id = ?")) {
            s.setObject(1, id);
            try (ResultSet z = s.executeQuery()) {
                if (!z.next()) {
                    return Optional.empty();
                }
                Optional<Versionsseite.Marke> marke = z.getString("marke_schluessel") == null ? Optional.empty()
                        : Optional.of(new Versionsseite.Marke(z.getString("marke_schluessel"),
                        z.getString("marke_version")));
                Fortsetzung stand = new Fortsetzung(Phase.valueOf(z.getString("phase")),
                        Optional.ofNullable(z.getObject("nach_dokument", UUID.class)), marke);
                Zaehler zaehler = new Zaehler(z.getInt("dateien"), z.getInt("versionen"), z.getInt("manifeste"),
                        z.getInt("verlaengert"), z.getInt("nur_gezaehlt"), z.getInt("abweichungen"));
                return Optional.of(new Lauf(id, Stufe.valueOf(z.getString("stufe")), z.getInt("teil"), stand,
                        zaehler));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Prüflauf nicht lesbar", e);
        }
    }

    @Override
    public boolean fortschreiben(UUID id, int teil, Fortsetzung weiter, Zaehler zuwachs,
                                 List<Abweichung> abweichungen) {
        String schema = schema();
        try (Connection v = verbindung()) {
            try (PreparedStatement s = v.prepareStatement("update " + schema + ".prueflauf set teil = ?, phase = ?, "
                    + "nach_dokument = ?, marke_schluessel = ?, marke_version = ?, dateien = dateien + ?, "
                    + "versionen = versionen + ?, manifeste = manifeste + ?, verlaengert = verlaengert + ?, "
                    + "nur_gezaehlt = nur_gezaehlt + ?, abweichungen = abweichungen + ?, "
                    + "beendet = case when ? = 'ABGESCHLOSSEN' then clock_timestamp() end "
                    + "where id = ? and teil = ?")) {
                int i = 1;
                s.setInt(i++, teil);
                s.setString(i++, weiter.phase().name());
                s.setObject(i++, weiter.nachDokument().orElse(null), Types.OTHER);
                s.setString(i++, weiter.marke().map(Versionsseite.Marke::schluessel).orElse(null));
                s.setString(i++, weiter.marke().map(Versionsseite.Marke::versionId).orElse(null));
                s.setInt(i++, zuwachs.dateien());
                s.setInt(i++, zuwachs.versionen());
                s.setInt(i++, zuwachs.manifeste());
                s.setInt(i++, zuwachs.verlaengert());
                s.setInt(i++, zuwachs.nurGezaehlt());
                s.setInt(i++, zuwachs.abweichungen());
                s.setString(i++, weiter.phase().name());
                s.setObject(i++, id);
                s.setInt(i, teil - 1);
                if (s.executeUpdate() != 1) {
                    return false;
                }
            }
            if (!abweichungen.isEmpty()) {
                try (PreparedStatement s = v.prepareStatement("insert into " + schema + ".prueflauf_abweichung "
                        + "(lauf_id, art, dokument_id, s3_schluessel, s3_version) values (?, ?, ?, ?, ?)")) {
                    for (Abweichung a : abweichungen) {
                        s.setObject(1, id);
                        s.setString(2, a.art().name());
                        s.setObject(3, a.dokumentId().orElse(null), Types.OTHER);
                        s.setString(4, a.schluessel());
                        s.setString(5, a.versionId().orElse(null));
                        s.addBatch();
                    }
                    s.executeBatch();
                }
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Prüflauf nicht fortgeschrieben", e);
        }
    }

    @Override
    public List<Abweichung> abweichungen(UUID id, int anzahl) {
        String schema = schema();
        try (Connection v = verbindung(); PreparedStatement s = v.prepareStatement("select art, dokument_id, "
                + "s3_schluessel, s3_version from " + schema + ".prueflauf_abweichung where lauf_id = ? "
                + "order by id limit ?")) {
            s.setObject(1, id);
            s.setInt(2, anzahl);
            List<Abweichung> liste = new ArrayList<>();
            try (ResultSet z = s.executeQuery()) {
                while (z.next()) {
                    liste.add(new Abweichung(Art.valueOf(z.getString("art")),
                            Optional.ofNullable(z.getObject("dokument_id", UUID.class)), z.getString("s3_schluessel"),
                            Optional.ofNullable(z.getString("s3_version"))));
                }
            }
            return liste;
        } catch (SQLException e) {
            throw new IllegalStateException("Abweichungen nicht lesbar", e);
        }
    }

    private Connection verbindung() throws SQLException {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Prüflauf nur in einer Transaktion");
        }
        Connection v = datenquelle.get().getConnection();
        try (PreparedStatement s = v.prepareStatement(ZEITGRENZE)) {
            s.execute();
        } catch (SQLException e) {
            v.close();
            throw e;
        }
        return v;
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Prüflauf ohne Mandanten-Schema");
        }
        return schema;
    }
}
