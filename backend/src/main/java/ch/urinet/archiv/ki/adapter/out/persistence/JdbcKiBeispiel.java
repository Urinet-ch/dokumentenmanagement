package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.KiBeispielPort;
import ch.urinet.archiv.ki.domain.Beispiel;
import ch.urinet.archiv.ki.domain.BeispielSicht;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Merkmale;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code m_<code>.ki_beispiel} des Mandanten aus dem Kontext (Req 7.7, 7.9; Schema immer qualifiziert). Liest dazu aus
 * {@code m_<code>.dokument} Vorschlag, Volltext, Partner und Erfasser. Schreibt in der laufenden Transaktion (die
 * Verbindung von Agroal ist in die JTA-Transaktion eingebunden). Die Sicht filtert schon in SQL; die Domain prüft
 * erneut. Fehlermeldungen ohne Inhalte.
 */
@ApplicationScoped
public class JdbcKiBeispiel implements KiBeispielPort {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String SPALTEN = "id, art, korrektur::text, iban, uid, email, muster, vertraulichkeit, "
            + "person_benutzer, zeit";
    private static final String SICHT = "(vertraulichkeit = 'NORMAL' and ? or vertraulichkeit = 'INTERN' and ? "
            + "or vertraulichkeit = 'PERSONAL' and person_benutzer = ?)";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcKiBeispiel(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Optional<Lernstand> lernstand(UUID dokumentId) {
        String sql = "select ki_vorschlag::text, volltext, partner_id from " + schema() + ".dokument where id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, dokumentId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next() || zeile.getString(1) == null) {
                    return Optional.empty();
                }
                JsonNode vorschlag = JSON.readTree(zeile.getString(1));
                Map<Feld, String> werte = new EnumMap<>(Feld.class);
                for (Feld feld : Feld.values()) {
                    text(vorschlag.get(feld.schluessel())).ifPresent(w -> werte.put(feld, w));
                }
                return Optional.of(new Lernstand(werte, zeile.getString(2), text(vorschlag.get("partnerIban")),
                        Optional.ofNullable(zeile.getObject(3, UUID.class))));
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("KI-Vorschlag nicht gelesen", e);
        }
    }

    @Override
    public Optional<String> erfasser(UUID dokumentId) {
        String sql = "select erstellt_von from " + schema() + ".dokument where id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, dokumentId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.ofNullable(zeile.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Erfasser nicht gelesen", e);
        }
    }

    @Override
    public void speichern(Neu b) {
        String sql = "insert into " + schema() + ".ki_beispiel (partner_id, art, korrektur, iban, uid, email, muster, "
                + "vertraulichkeit, person_benutzer, dokument_id) values (?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?)";
        Map<String, String> korrektur = new LinkedHashMap<>();
        b.korrektur().forEach((feld, wert) -> korrektur.put(feld.schluessel(), wert));
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setObject(1, b.partnerId().orElse(null));
            einfuegen.setString(2, b.art());
            einfuegen.setString(3, JSON.writeValueAsString(korrektur));
            einfuegen.setArray(4, feld(verbindung, b.merkmale().ibans()));
            einfuegen.setArray(5, feld(verbindung, b.merkmale().uids()));
            einfuegen.setArray(6, feld(verbindung, b.merkmale().emails()));
            einfuegen.setArray(7, feld(verbindung, b.merkmale().muster()));
            einfuegen.setString(8, b.vertraulichkeit());
            einfuegen.setString(9, b.personBenutzer());
            einfuegen.setObject(10, b.dokumentId());
            einfuegen.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("KI-Beispiel nicht gespeichert", e);
        }
    }

    @Override
    public List<Beispiel> kandidaten(Merkmale merkmale, BeispielSicht sicht, int hoechstens) {
        String sql = "select " + SPALTEN + " from " + schema() + ".ki_beispiel where (iban && ? or uid && ? "
                + "or email && ? or muster && ?) and " + SICHT + " order by zeit desc, id desc limit ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setArray(1, feld(verbindung, merkmale.ibans()));
            abfrage.setArray(2, feld(verbindung, merkmale.uids()));
            abfrage.setArray(3, feld(verbindung, merkmale.emails()));
            abfrage.setArray(4, feld(verbindung, merkmale.muster()));
            sicht(abfrage, 5, sicht);
            abfrage.setInt(8, hoechstens);
            return lesen(abfrage);
        } catch (SQLException e) {
            throw new IllegalStateException("KI-Beispiele nicht gelesen", e);
        }
    }

    @Override
    public List<Beispiel> neueste(BeispielSicht sicht, int anzahl) {
        String sql = "select " + SPALTEN + " from " + schema() + ".ki_beispiel where " + SICHT
                + " order by zeit desc, id desc limit ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            sicht(abfrage, 1, sicht);
            abfrage.setInt(4, anzahl);
            return lesen(abfrage);
        } catch (SQLException e) {
            throw new IllegalStateException("KI-Beispiele nicht gelesen", e);
        }
    }

    private static void sicht(PreparedStatement abfrage, int ab, BeispielSicht sicht) throws SQLException {
        abfrage.setBoolean(ab, sicht.normal());
        abfrage.setBoolean(ab + 1, sicht.intern());
        abfrage.setString(ab + 2, sicht.erfasser());
    }

    private static List<Beispiel> lesen(PreparedStatement abfrage) throws SQLException {
        List<Beispiel> liste = new ArrayList<>();
        try (ResultSet zeile = abfrage.executeQuery()) {
            while (zeile.next()) {
                liste.add(new Beispiel(zeile.getLong(1), zeile.getString(2), korrektur(zeile.getString(3)),
                        new Merkmale(menge(zeile.getArray(4)), menge(zeile.getArray(5)), menge(zeile.getArray(6)),
                                menge(zeile.getArray(7))),
                        zeile.getString(8), zeile.getString(9), zeile.getObject(10, OffsetDateTime.class).toInstant()));
            }
        }
        return liste;
    }

    private static Map<Feld, String> korrektur(String json) {
        try {
            JsonNode knoten = JSON.readTree(json);
            Map<Feld, String> werte = new EnumMap<>(Feld.class);
            for (Feld feld : Feld.values()) {
                if (knoten.has(feld.schluessel())) {
                    werte.put(feld, text(knoten.get(feld.schluessel())).orElse(null));
                }
            }
            return werte;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("KI-Beispiel nicht lesbar", e);
        }
    }

    private static Optional<String> text(JsonNode knoten) {
        if (knoten == null || knoten.isNull()) {
            return Optional.empty();
        }
        return Optional.of(knoten.isBigDecimal() ? knoten.decimalValue().toPlainString() : knoten.asText());
    }

    private static Set<String> menge(Array feld) throws SQLException {
        return feld == null ? Set.of() : new LinkedHashSet<>(Arrays.asList((String[]) feld.getArray()));
    }

    private static Array feld(Connection verbindung, Set<String> werte) throws SQLException {
        return verbindung.createArrayOf("text", werte.toArray(String[]::new));
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("KI-Beispiel ohne Mandanten-Schema");
        }
        return schema;
    }
}
