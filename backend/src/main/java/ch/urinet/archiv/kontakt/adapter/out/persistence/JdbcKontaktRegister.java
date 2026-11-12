package ch.urinet.archiv.kontakt.adapter.out.persistence;

import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.application.KontaktTreffer;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.kontakt.domain.PartnerKandidat;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code m_<code>.kontakt} per JDBC wie {@code JdbcZugriffProtokoll}: Schema aus der Anmeldung bzw. dem
 * Mandanten-Kontext, immer qualifiziert. Der search_path der Verbindung enthält nur ein Schema, darum stehen die
 * {@code pg_trgm}-Objekte qualifiziert ({@code public.similarity}, {@code OPERATOR(public.%)}); der Operator {@code %}
 * nutzt den GIN-Index {@code kontakt_name_trgm_ix}.
 */
@ApplicationScoped
public class JdbcKontaktRegister implements KontaktRegister {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String SPALTEN = "id, crm_id, typ, name, vorname, strasse, plz, ort, email, telefon, uid, iban, "
            + "kategorie, quelle, nicht_im_export, erstellt, geaendert, version";
    /** Obergrenze je Abfrage des Abgleichs, nur für die laufende Transaktion. */
    private static final String ZEITGRENZE = "select set_config('statement_timeout', '30s', true)";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcKontaktRegister(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public List<Kontakt> suchen(String nameNormalisiert, int anzahl) {
        String tabelle = tabelle();
        boolean ohneText = nameNormalisiert.isEmpty();
        String sql = ohneText
                ? "select " + SPALTEN + " from " + tabelle + " order by name_normalisiert, id limit ?"
                : "select " + SPALTEN + " from " + tabelle + " where name_normalisiert like ? escape '\\' "
                + "or name_normalisiert operator(public.%) ? "
                + "order by public.similarity(name_normalisiert, ?) desc, name_normalisiert, id limit ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            int i = 1;
            if (!ohneText) {
                abfrage.setString(i++, "%" + nameNormalisiert.replaceAll("([\\\\%_])", "\\\\$1") + "%");
                abfrage.setString(i++, nameNormalisiert);
                abfrage.setString(i++, nameNormalisiert);
            }
            abfrage.setInt(i, anzahl);
            List<Kontakt> kontakte = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    kontakte.add(kontakt(zeile));
                }
            }
            return kontakte;
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakte nicht lesbar", e);
        }
    }

    @Override
    public Optional<Kontakt> finden(UUID id) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select " + SPALTEN + " from " + tabelle() + " where id = ?")) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(kontakt(zeile)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht lesbar", e);
        }
    }

    @Override
    public Optional<Kontakt> mitCrmId(String crmId) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select " + SPALTEN + " from " + tabelle() + " where crm_id = ?")) {
            abfrage.setString(1, crmId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(kontakt(zeile)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht lesbar", e);
        }
    }

    @Override
    public Kontakt anlegen(KontaktAngaben angaben, String nameNormalisiert, String quelle) {
        String sql = "insert into " + tabelle() + " (id, typ, name, vorname, strasse, plz, ort, email, telefon, uid, "
                + "iban, kategorie, name_normalisiert, quelle) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "returning " + SPALTEN;
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setObject(1, UUID.randomUUID());
            int i = felder(einfuegen, 2, angaben, nameNormalisiert);
            einfuegen.setString(i, quelle);
            try (ResultSet zeile = einfuegen.executeQuery()) {
                zeile.next();
                return kontakt(zeile);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht angelegt", e);
        }
    }

    @Override
    public Optional<Kontakt> aendern(UUID id, int version, KontaktAngaben angaben, String nameNormalisiert) {
        String sql = "update " + tabelle() + " set typ = ?, name = ?, vorname = ?, strasse = ?, plz = ?, ort = ?, "
                + "email = ?, telefon = ?, uid = ?, iban = ?, kategorie = ?, name_normalisiert = ?, geaendert = now(), "
                + "version = version + 1 where id = ? and version = ? returning " + SPALTEN;
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            int i = felder(aendern, 1, angaben, nameNormalisiert);
            aendern.setObject(i++, id);
            aendern.setInt(i, version);
            try (ResultSet zeile = aendern.executeQuery()) {
                return zeile.next() ? Optional.of(kontakt(zeile)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht geändert", e);
        }
    }

    @Override
    public Set<UUID> mitEmail(String email) {
        return ids("lower(email) = ?", email);
    }

    @Override
    public Set<UUID> mitUid(String uid) {
        return ids("uid = ?", uid);
    }

    @Override
    public Set<UUID> mitIban(String iban) {
        return ids("iban = ?", iban);
    }

    /**
     * Setzt {@code pg_trgm.similarity_threshold} nur für die laufende Transaktion ({@code set_config(…, true)}), damit
     * der Operator {@code %} die Untergrenze über den GIN-Index anwendet; ohne Transaktion bricht er darum ab.
     */
    @Override
    public List<PartnerKandidat> aehnlichste(String nameNormalisiert, BigDecimal untergrenze, int anzahl) {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Namensähnlichkeit nur in einer Transaktion");
        }
        // real (float4) auf 4 Stellen runden, sonst fällt z. B. 0.7 − 0.6 knapp unter den Abstand 0.1
        String sql = "select id, round(public.similarity(name_normalisiert, ?)::numeric, 4) as aehnlichkeit from "
                + tabelle()
                + " where name_normalisiert operator(public.%) ? order by aehnlichkeit desc, id limit ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement schwelle = verbindung.prepareStatement(
                     "select set_config('pg_trgm.similarity_threshold', ?, true)");
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            schwelle.setString(1, untergrenze.toPlainString());
            schwelle.execute();
            abfrage.setString(1, nameNormalisiert);
            abfrage.setString(2, nameNormalisiert);
            abfrage.setInt(3, anzahl);
            List<PartnerKandidat> kandidaten = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    kandidaten.add(new PartnerKandidat(zeile.getObject("id", UUID.class),
                            zeile.getBigDecimal("aehnlichkeit")));
                }
            }
            return kandidaten;
        } catch (SQLException e) {
            throw new IllegalStateException("Namensähnlichkeit nicht lesbar", e);
        }
    }

    @Override
    public List<KontaktTreffer> mitSchluesseln(Collection<String> crmIds, Collection<String> emails,
                                               List<String> namen, List<String> plz) {
        if (namen.size() != plz.size()) {
            throw new IllegalArgumentException("namen und plz verschieden lang");
        }
        if (crmIds.isEmpty() && emails.isEmpty() && namen.isEmpty()) {
            return List.of();
        }
        transaktionPruefen();
        String tabelle = tabelle();
        String sql = "select " + SPALTEN + ", name_normalisiert from " + tabelle + " where id in ("
                + "select id from " + tabelle + " where crm_id = any(?) "
                + "union select id from " + tabelle + " where lower(email) = any(?) "
                + "union select k.id from " + tabelle + " k join unnest(?::text[], ?::text[]) s(n, p) "
                + "on k.name_normalisiert = s.n and k.plz = s.p) order by id";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement zeitgrenze = verbindung.prepareStatement(ZEITGRENZE);
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            zeitgrenze.execute();
            abfrage.setArray(1, verbindung.createArrayOf("text", crmIds.toArray()));
            abfrage.setArray(2, verbindung.createArrayOf("text", emails.toArray()));
            abfrage.setArray(3, verbindung.createArrayOf("text", namen.toArray()));
            abfrage.setArray(4, verbindung.createArrayOf("text", plz.toArray()));
            List<KontaktTreffer> treffer = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    treffer.add(new KontaktTreffer(kontakt(zeile), zeile.getString("name_normalisiert")));
                }
            }
            return treffer;
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakte für den Abgleich nicht lesbar", e);
        }
    }

    @Override
    public Map<String, List<UUID>> aehnlichsteJe(Collection<String> namen, BigDecimal untergrenze, int anzahl) {
        if (namen.isEmpty()) {
            return Map.of();
        }
        transaktionPruefen();
        String sql = "select s.n, k.id from unnest(?::text[]) s(n) cross join lateral (select id, "
                + "round(public.similarity(name_normalisiert, s.n)::numeric, 4) as aehnlichkeit from " + tabelle()
                + " where name_normalisiert operator(public.%) s.n order by aehnlichkeit desc, id limit ?) k "
                + "order by s.n, k.aehnlichkeit desc, k.id";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement zeitgrenze = verbindung.prepareStatement(ZEITGRENZE);
             PreparedStatement schwelle = verbindung.prepareStatement(
                     "select set_config('pg_trgm.similarity_threshold', ?, true)");
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            zeitgrenze.execute();
            schwelle.setString(1, untergrenze.toPlainString());
            schwelle.execute();
            abfrage.setArray(1, verbindung.createArrayOf("text", namen.toArray()));
            abfrage.setInt(2, anzahl);
            Map<String, List<UUID>> ergebnis = new HashMap<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    ergebnis.computeIfAbsent(zeile.getString(1), n -> new ArrayList<>())
                            .add(zeile.getObject(2, UUID.class));
                }
            }
            return ergebnis;
        } catch (SQLException e) {
            throw new IllegalStateException("Namensähnlichkeit nicht lesbar", e);
        }
    }

    private static void transaktionPruefen() {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Abgleich nur in einer Transaktion");
        }
    }

    private Set<UUID> ids(String bedingung, String wert) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select id from " + tabelle() + " where " + bedingung + " order by id")) {
            abfrage.setString(1, wert);
            Set<UUID> ids = new LinkedHashSet<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    ids.add(zeile.getObject("id", UUID.class));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakte nicht lesbar", e);
        }
    }

    private static int felder(PreparedStatement anweisung, int start, KontaktAngaben a, String nameNormalisiert)
            throws SQLException {
        int i = start;
        anweisung.setString(i++, a.typ().name());
        anweisung.setString(i++, a.name());
        anweisung.setString(i++, a.vorname());
        anweisung.setString(i++, a.strasse());
        anweisung.setString(i++, a.plz());
        anweisung.setString(i++, a.ort());
        anweisung.setString(i++, a.email());
        anweisung.setString(i++, a.telefon());
        anweisung.setString(i++, a.uid());
        anweisung.setString(i++, a.iban());
        anweisung.setString(i++, a.kategorie());
        anweisung.setString(i++, nameNormalisiert);
        return i;
    }

    private String tabelle() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Kontakte ohne Mandanten-Schema");
        }
        return schema + ".kontakt";
    }

    private static Kontakt kontakt(ResultSet zeile) throws SQLException {
        return new Kontakt(zeile.getObject("id", UUID.class), zeile.getString("crm_id"),
                KontaktTyp.valueOf(zeile.getString("typ")), zeile.getString("name"), zeile.getString("vorname"),
                zeile.getString("strasse"), zeile.getString("plz"), zeile.getString("ort"), zeile.getString("email"),
                zeile.getString("telefon"), zeile.getString("uid"), zeile.getString("iban"),
                zeile.getString("kategorie"), zeile.getString("quelle"), zeile.getBoolean("nicht_im_export"),
                zeile.getObject("erstellt", OffsetDateTime.class).toInstant(),
                zeile.getObject("geaendert", OffsetDateTime.class).toInstant(), zeile.getInt("version"));
    }
}
