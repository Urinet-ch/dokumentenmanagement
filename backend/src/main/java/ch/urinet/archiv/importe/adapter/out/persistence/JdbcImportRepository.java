package ch.urinet.archiv.importe.adapter.out.persistence;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.application.ImportRepository;
import ch.urinet.archiv.importe.domain.DublettenEntscheid;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Läufe, Zeilen und Zuordnungen per JDBC im Schema {@code m_<code>} des Mandanten der Anfrage (Schema immer qualifiziert,
 * design.md «Mandant»). Nur innerhalb einer Transaktion, damit ein Lauf nie ohne seine Zeilen festgeschrieben wird;
 * Zeilen kommen in Blöcken (ein Batch je Aufruf).
 * {@code import_zeile.daten} hält die Rohwerte als {@code {"werte": [...]}}; ein offenes Ergebnis (wartet auf den
 * Abgleich, Aufgabe 4.2) ist {@code null}.
 */
@ApplicationScoped
public class JdbcImportRepository implements ImportRepository {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ZEILE = "nr, daten, ergebnis, meldung, kontakt_id, kandidaten";
    private static final String EINSTELLEN = "select set_config(?, ?, true)";
    /** lock_not_available: {@code lock_timeout} abgelaufen. */
    private static final String SPERRE_BELEGT = "55P03";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final String anweisungZeitgrenze;
    private final String sperreWartezeit;

    public JdbcImportRepository(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                @ConfigProperty(name = "archiv.import.anweisung-zeitgrenze") Duration anweisung,
                                @ConfigProperty(name = "archiv.import.sperre-wartezeit") Duration sperre) {
        if (anweisung.toMillis() < 1 || sperre.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "archiv.import.anweisung-zeitgrenze und archiv.import.sperre-wartezeit müssen mindestens 1 ms sein");
        }
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.anweisungZeitgrenze = anweisung.toMillis() + "ms";
        this.sperreWartezeit = sperre.toMillis() + "ms";
    }

    @Override
    public void laufAnlegen(ImportLauf lauf) {
        String sql = "insert into " + schema() + ".import_lauf (id, art, vollexport, dateiname, sha256, zeichensatz, "
                + "trennzeichen, kopfzeile, zeilen, fehler, status, benutzer, erstellt) "
                + "values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setObject(1, lauf.id(), Types.OTHER);
            einfuegen.setString(2, lauf.art().code());
            einfuegen.setBoolean(3, lauf.vollexport());
            einfuegen.setString(4, lauf.dateiname());
            einfuegen.setString(5, lauf.sha256());
            einfuegen.setString(6, lauf.zeichensatz());
            einfuegen.setString(7, String.valueOf(lauf.trennzeichen()));
            einfuegen.setString(8, json(liste(lauf.kopf().namen())));
            einfuegen.setInt(9, lauf.zeilen());
            einfuegen.setInt(10, lauf.fehler());
            einfuegen.setString(11, lauf.status().name());
            einfuegen.setString(12, lauf.benutzer());
            einfuegen.setTimestamp(13, Timestamp.from(lauf.erstellt()));
            einfuegen.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Lauf nicht gespeichert", e);
        }
    }

    @Override
    public void zeilenAnlegen(UUID laufId, List<ImportZeile> zeilen) {
        String sql = "insert into " + schema() + ".import_zeile (lauf_id, nr, daten, ergebnis, meldung) "
                + "values (?, ?, cast(? as jsonb), ?, ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            for (ImportZeile zeile : zeilen) {
                einfuegen.setObject(1, laufId, Types.OTHER);
                einfuegen.setInt(2, zeile.nr());
                einfuegen.setString(3, daten(zeile.werte()));
                einfuegen.setString(4, ergebnis(zeile.ergebnis()));
                einfuegen.setString(5, zeile.meldung());
                einfuegen.addBatch();
            }
            einfuegen.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Zeilen nicht gespeichert", e);
        }
    }

    @Override
    public void laufAktualisieren(ImportLauf lauf) {
        String sql = "update " + schema() + ".import_lauf set status = ?, zeilen = ?, fehler = ?, "
                + "zuordnung = cast(? as jsonb), neu = ?, geaendert = ?, unveraendert = ?, dubletten = ? where id = ?";
        ImportLauf.Zaehler vorschau = lauf.vorschau();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            aendern.setString(1, lauf.status().name());
            aendern.setInt(2, lauf.zeilen());
            aendern.setInt(3, lauf.fehler());
            aendern.setString(4, lauf.zuordnung() == null ? null : json(zuordnungJson(lauf.zuordnung())));
            aendern.setObject(5, vorschau == null ? null : vorschau.neu(), Types.INTEGER);
            aendern.setObject(6, vorschau == null ? null : vorschau.geaendert(), Types.INTEGER);
            aendern.setObject(7, vorschau == null ? null : vorschau.unveraendert(), Types.INTEGER);
            aendern.setObject(8, vorschau == null ? null : vorschau.dubletten(), Types.INTEGER);
            aendern.setObject(9, lauf.id(), Types.OTHER);
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Lauf nicht gespeichert", e);
        }
    }

    @Override
    public Optional<ImportLauf> lauf(UUID id) {
        return lauf(id, false);
    }

    @Override
    public Optional<ImportLauf> laufSperren(UUID id) {
        return lauf(id, true);
    }

    @Override
    public Optional<ImportLauf> laufSperrenOderKonflikt(UUID id) {
        schema();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            String vorher;
            try (PreparedStatement abfrage = verbindung.prepareStatement("select current_setting('lock_timeout')");
                 ResultSet ergebnis = abfrage.executeQuery()) {
                ergebnis.next();
                vorher = ergebnis.getString(1);
            }
            einstellen(verbindung, "lock_timeout", sperreWartezeit);
            Optional<ImportLauf> lauf = lauf(id, true);
            einstellen(verbindung, "lock_timeout", vorher);
            return lauf;
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof SQLException s && SPERRE_BELEGT.equals(s.getSQLState())) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Lauf nicht gesperrt", e);
        }
    }

    @Override
    public void zeitgrenzenSetzen() {
        schema();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            einstellen(verbindung, "statement_timeout", anweisungZeitgrenze);
            einstellen(verbindung, "lock_timeout", sperreWartezeit);
        } catch (SQLException e) {
            throw new IllegalStateException("Zeitgrenzen nicht gesetzt", e);
        }
    }

    /** Nur für die laufende Transaktion ({@code is_local}); die Verbindung gehört ihr. */
    private static void einstellen(Connection verbindung, String name, String wert) throws SQLException {
        try (PreparedStatement einstellen = verbindung.prepareStatement(EINSTELLEN)) {
            einstellen.setString(1, name);
            einstellen.setString(2, wert);
            einstellen.executeQuery().close();
        }
    }

    private Optional<ImportLauf> lauf(UUID id, boolean sperren) {
        String sql = laufAbfrage(schema()) + " where l.id = ?" + (sperren ? " for update of l" : "");
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id, Types.OTHER);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                return ergebnis.next() ? Optional.of(lauf(ergebnis)) : Optional.empty();
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Lauf nicht gelesen", e);
        }
    }

    @Override
    public List<ImportLauf> laeufe(int seite, int groesse) {
        String sql = laufAbfrage(schema()) + " order by l.erstellt desc, l.id limit ? offset ?";
        List<ImportLauf> laeufe = new ArrayList<>();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setInt(1, groesse);
            abfrage.setLong(2, (long) seite * groesse);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    laeufe.add(lauf(ergebnis));
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Läufe nicht gelesen", e);
        }
        return laeufe;
    }

    @Override
    public long anzahlLaeufe() {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select count(*) from " + schema()
                     + ".import_lauf");
             ResultSet ergebnis = abfrage.executeQuery()) {
            ergebnis.next();
            return ergebnis.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Läufe nicht gezählt", e);
        }
    }

    private static String laufAbfrage(String schema) {
        return "select l.id, l.art, l.vollexport, l.dateiname, l.sha256, l.zeichensatz, l.trennzeichen, "
                + "l.kopfzeile::text, l.zeilen, l.fehler, l.status, l.benutzer, l.erstellt, l.zuordnung::text, "
                + "l.neu, l.geaendert, l.unveraendert, l.dubletten, l.uebernommen, l.uebernommen_von, l.angelegt, "
                + "l.aktualisiert, l.ausgelassen, l.gekennzeichnet, "
                + "(select count(*) from " + schema + ".import_zeile z where z.lauf_id = l.id "
                + "and z.ergebnis = 'UEBERSPRUNGEN') as uebersprungen "
                + "from " + schema + ".import_lauf l";
    }

    private static ImportLauf lauf(ResultSet ergebnis) throws SQLException, JsonProcessingException {
        ImportArt art = ImportArt.aus(ergebnis.getString("art"))
                .orElseThrow(() -> new IllegalStateException("unbekannte Importart in import_lauf"));
        String zuordnung = ergebnis.getString("zuordnung");
        Integer neu = ergebnis.getObject("neu", Integer.class);
        int fehler = ergebnis.getInt("fehler");
        int uebersprungen = ergebnis.getInt("uebersprungen");
        ImportLauf.Zaehler vorschau = neu == null ? null : new ImportLauf.Zaehler(neu,
                ergebnis.getInt("geaendert"), ergebnis.getInt("unveraendert"), ergebnis.getInt("dubletten"),
                fehler, uebersprungen);
        String von = ergebnis.getString("uebernommen_von");
        Timestamp zeit = ergebnis.getTimestamp("uebernommen");
        Integer angelegt = ergebnis.getObject("angelegt", Integer.class);
        ImportLauf.Uebernahme uebernahme = von == null && zeit == null ? null : new ImportLauf.Uebernahme(von,
                zeit == null ? null : zeit.toInstant(), angelegt == null ? null : new ImportLauf.Bilanz(angelegt,
                ergebnis.getInt("aktualisiert"), ergebnis.getInt("ausgelassen"), ergebnis.getInt("gekennzeichnet")));
        return new ImportLauf(ergebnis.getObject("id", UUID.class), art,
                ergebnis.getBoolean("vollexport"), ergebnis.getString("dateiname"),
                ergebnis.getString("sha256"), ergebnis.getString("zeichensatz"),
                ergebnis.getString("trennzeichen").charAt(0),
                new Kopfzeile(JSON.readValue(ergebnis.getString("kopfzeile"), new TypeReference<>() {
                })), ergebnis.getInt("zeilen"), fehler, uebersprungen,
                LaufStatus.valueOf(ergebnis.getString("status")), ergebnis.getString("benutzer"),
                ergebnis.getTimestamp("erstellt").toInstant(),
                zuordnung == null ? null : zuordnungAus(JSON.readTree(zuordnung)), vorschau, uebernahme);
    }

    @Override
    public List<ImportZeile> zeilenMitNr(UUID laufId, Collection<Integer> nrs) {
        List<ImportZeile> zeilen = new ArrayList<>();
        if (nrs.isEmpty()) {
            return zeilen;
        }
        String sql = "select nr, null::text as daten, ergebnis, meldung, kontakt_id, kandidaten from " + schema()
                + ".import_zeile where lauf_id = ? and nr = any(?) order by nr";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, laufId, Types.OTHER);
            abfrage.setArray(2, verbindung.createArrayOf("integer", nrs.toArray()));
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    zeilen.add(zeile(ergebnis));
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Zeilen nicht gelesen", e);
        }
        return zeilen;
    }

    @Override
    public void entscheideSpeichern(UUID laufId, Map<Integer, DublettenEntscheid> entscheide) {
        String schema = schema();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement("update " + schema + ".import_zeile "
                     + "set entscheid = null, entscheid_kontakt = null where lauf_id = ? and entscheid is not null");
             PreparedStatement setzen = verbindung.prepareStatement("update " + schema + ".import_zeile "
                     + "set entscheid = ?, entscheid_kontakt = ? where lauf_id = ? and nr = ?")) {
            loeschen.setObject(1, laufId, Types.OTHER);
            loeschen.executeUpdate();
            for (Map.Entry<Integer, DublettenEntscheid> eintrag : entscheide.entrySet()) {
                setzen.setString(1, eintrag.getValue().art().name());
                setzen.setObject(2, eintrag.getValue().kontaktId(), Types.OTHER);
                setzen.setObject(3, laufId, Types.OTHER);
                setzen.setInt(4, eintrag.getKey());
                setzen.addBatch();
            }
            setzen.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Dubletten-Entscheide nicht gespeichert", e);
        }
    }

    @Override
    public Map<Integer, DublettenEntscheid> entscheide(UUID laufId) {
        String sql = "select nr, entscheid, entscheid_kontakt from " + schema()
                + ".import_zeile where lauf_id = ? and entscheid is not null";
        Map<Integer, DublettenEntscheid> entscheide = new HashMap<>();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, laufId, Types.OTHER);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    entscheide.put(ergebnis.getInt("nr"), new DublettenEntscheid(
                            DublettenEntscheid.Art.valueOf(ergebnis.getString("entscheid")),
                            ergebnis.getObject("entscheid_kontakt", UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dubletten-Entscheide nicht gelesen", e);
        }
        return entscheide;
    }

    @Override
    public void uebernahmeVormerken(UUID laufId, String benutzer) {
        String sql = "update " + schema() + ".import_lauf set uebernommen_von = ? where id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            aendern.setString(1, benutzer);
            aendern.setObject(2, laufId, Types.OTHER);
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Übernahme nicht vorgemerkt", e);
        }
    }

    @Override
    public void uebernahmeAbschliessen(UUID laufId, ImportLauf.Bilanz bilanz) {
        // clock_timestamp(): Ende der Übernahme; now() wäre der Beginn der (langen) Transaktion
        String sql = "update " + schema() + ".import_lauf set status = 'UEBERNOMMEN', "
                + "uebernommen = clock_timestamp(), angelegt = ?, aktualisiert = ?, ausgelassen = ?, gekennzeichnet = ? "
                + "where id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            aendern.setInt(1, bilanz.angelegt());
            aendern.setInt(2, bilanz.aktualisiert());
            aendern.setInt(3, bilanz.ausgelassen());
            aendern.setInt(4, bilanz.gekennzeichnet());
            aendern.setObject(5, laufId, Types.OTHER);
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Übernahme nicht abgeschlossen", e);
        }
    }

    @Override
    public int breite(UUID laufId) {
        String sql = "select coalesce(max(jsonb_array_length(daten -> 'werte')), 0) from " + schema()
                + ".import_zeile where lauf_id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, laufId, Types.OTHER);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                ergebnis.next();
                return ergebnis.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Zeilen nicht gelesen", e);
        }
    }

    @Override
    public List<ImportZeile> zeilen(UUID laufId, int nachNr, int anzahl, int zeichen) {
        String sql = "select " + ZEILE + " from (select " + ZEILE + ", "
                + "sum(coalesce(length(daten), 0)) over (order by nr) - coalesce(length(daten), 0) as davor "
                + "from (select nr, daten::text as daten, ergebnis, meldung, kontakt_id, kandidaten from " + schema()
                + ".import_zeile where lauf_id = ? and nr > ? order by nr limit ?) z) b where davor < ? order by nr";
        List<ImportZeile> zeilen = new ArrayList<>();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, laufId, Types.OTHER);
            abfrage.setInt(2, nachNr);
            abfrage.setInt(3, anzahl);
            abfrage.setLong(4, zeichen);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    zeilen.add(zeile(ergebnis));
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Zeilen nicht gelesen", e);
        }
        return zeilen;
    }

    @Override
    public List<ImportZeile> zeilenSeite(UUID laufId, int seite, int groesse) {
        String sql = "select nr, daten::text as daten, ergebnis, meldung, kontakt_id, kandidaten from " + schema()
                + ".import_zeile where lauf_id = ? order by nr limit ? offset ?";
        List<ImportZeile> zeilen = new ArrayList<>();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, laufId, Types.OTHER);
            abfrage.setInt(2, groesse);
            abfrage.setLong(3, (long) seite * groesse);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    zeilen.add(zeile(ergebnis));
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Zeilen nicht gelesen", e);
        }
        return zeilen;
    }

    private static ImportZeile zeile(ResultSet ergebnis) throws SQLException, JsonProcessingException {
        String text = ergebnis.getString("daten");
        List<String> werte = null;
        if (text != null) {
            werte = new ArrayList<>();
            for (JsonNode wert : JSON.readTree(text).path("werte")) {
                werte.add(wert.isNull() ? null : wert.asText());
            }
        }
        String code = ergebnis.getString("ergebnis");
        Array kandidaten = ergebnis.getArray("kandidaten");
        List<UUID> ids = kandidaten == null ? List.of() : List.of((UUID[]) kandidaten.getArray());
        return new ImportZeile(ergebnis.getInt("nr"), werte, code == null ? Ergebnis.OFFEN : Ergebnis.valueOf(code),
                ergebnis.getString("meldung"), ergebnis.getObject("kontakt_id", UUID.class), ids);
    }

    @Override
    public void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen) {
        schreiben(laufId, zeilen.stream().map(z -> new Abgeglichen(z, null)).toList());
    }

    @Override
    public void abgleichSchreiben(UUID laufId, List<Abgeglichen> zeilen) {
        schreiben(laufId, zeilen);
    }

    private void schreiben(UUID laufId, List<Abgeglichen> zeilen) {
        String sql = "update " + schema() + ".import_zeile set ergebnis = ?, meldung = ?, kontakt_id = ?, "
                + "kandidaten = ?, schluessel = ? where lauf_id = ? and nr = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            for (Abgeglichen abgeglichen : zeilen) {
                ImportZeile zeile = abgeglichen.zeile();
                aendern.setString(1, ergebnis(zeile.ergebnis()));
                aendern.setString(2, zeile.meldung());
                aendern.setObject(3, zeile.kontaktId(), Types.OTHER);
                aendern.setArray(4, zeile.kandidaten().isEmpty() ? null
                        : verbindung.createArrayOf("uuid", zeile.kandidaten().toArray()));
                aendern.setArray(5, abgeglichen.schluessel() == null || abgeglichen.schluessel().length == 0 ? null
                        : verbindung.createArrayOf("bigint",
                        Arrays.stream(abgeglichen.schluessel()).boxed().toArray()));
                aendern.setObject(6, laufId, Types.OTHER);
                aendern.setInt(7, zeile.nr());
                aendern.addBatch();
            }
            aendern.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Prüfung der Import-Zeilen nicht gespeichert", e);
        }
    }

    @Override
    public int doppelteMarkieren(UUID laufId, String meldung) {
        String schema = schema();
        String sql = "update " + schema + ".import_zeile z set ergebnis = 'DUBLETTE', "
                + "meldung = replace(?, '{}', d.erste::text), "
                + "kandidaten = case when z.kontakt_id is null then z.kandidaten else array[z.kontakt_id] end, "
                + "kontakt_id = null "
                + "from (select nr, min(erste) as erste from (select y.nr, min(y.nr) over (partition by k) as erste "
                + "from " + schema + ".import_zeile y cross join lateral unnest(y.schluessel) k where y.lauf_id = ?) x "
                + "where erste < nr group by nr) d "
                + "where z.lauf_id = ? and z.nr = d.nr and z.ergebnis is distinct from 'DUBLETTE'";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            aendern.setString(1, meldung);
            aendern.setObject(2, laufId, Types.OTHER);
            aendern.setObject(3, laufId, Types.OTHER);
            return aendern.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Doppelte Import-Zeilen nicht markiert", e);
        }
    }

    @Override
    public ImportLauf.Zaehler zaehlen(UUID laufId) {
        String sql = "select ergebnis, count(*) from " + schema() + ".import_zeile where lauf_id = ? group by ergebnis";
        Map<String, Integer> anzahl = new HashMap<>();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, laufId, Types.OTHER);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    anzahl.put(String.valueOf(ergebnis.getString(1)), ergebnis.getInt(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Zeilen nicht gezählt", e);
        }
        return new ImportLauf.Zaehler(anzahl.getOrDefault("NEU", 0), anzahl.getOrDefault("GEAENDERT", 0),
                anzahl.getOrDefault("UNVERAENDERT", 0), anzahl.getOrDefault("DUBLETTE", 0),
                anzahl.getOrDefault("FEHLER", 0), anzahl.getOrDefault("UEBERSPRUNGEN", 0));
    }

    @Override
    public Optional<GespeicherteZuordnung> zuordnung(ImportArt art) {
        String sql = "select spalten::text, kopf_hash, geaendert from " + schema() + ".import_zuordnung where art = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, art.code());
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                if (!ergebnis.next()) {
                    return Optional.empty();
                }
                return Optional.of(new GespeicherteZuordnung(art, zuordnungAus(JSON.readTree(ergebnis.getString("spalten"))),
                        ergebnis.getString("kopf_hash"), ergebnis.getTimestamp("geaendert").toInstant()));
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Zuordnung nicht gelesen", e);
        }
    }

    @Override
    public void zuordnungSpeichern(GespeicherteZuordnung zuordnung) {
        String sql = "insert into " + schema() + ".import_zuordnung (art, spalten, kopf_hash, geaendert) "
                + "values (?, cast(? as jsonb), ?, ?) on conflict (art) do update set spalten = excluded.spalten, "
                + "kopf_hash = excluded.kopf_hash, geaendert = excluded.geaendert";
        ObjectNode spalten = zuordnungJson(zuordnung.zuordnung());
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement speichern = verbindung.prepareStatement(sql)) {
            speichern.setString(1, zuordnung.art().code());
            speichern.setString(2, json(spalten));
            speichern.setString(3, zuordnung.kopfHash());
            speichern.setTimestamp(4, Timestamp.from(zuordnung.geaendert()));
            speichern.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Import-Zuordnung nicht gespeichert", e);
        }
    }

    /** {@code {"profil": …, "felder": {feld: spalte, …}}}, Felder sortiert (gleiche Zuordnung, gleicher Text). */
    private static ObjectNode zuordnungJson(Zuordnung zuordnung) {
        ObjectNode spalten = JSON.createObjectNode();
        if (zuordnung.profil() == null) {
            spalten.putNull("profil");
        } else {
            spalten.put("profil", zuordnung.profil());
        }
        ObjectNode felder = spalten.putObject("felder");
        zuordnung.felder().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> felder.put(e.getKey(), e.getValue()));
        return spalten;
    }

    private static Zuordnung zuordnungAus(JsonNode spalten) {
        Map<String, Integer> felder = new LinkedHashMap<>();
        spalten.path("felder").properties().forEach(e -> felder.put(e.getKey(), e.getValue().asInt()));
        String profil = spalten.path("profil").isTextual() ? spalten.path("profil").asText() : null;
        return new Zuordnung(profil, felder);
    }

    private String schema() {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Import-Daten nur in einer Transaktion");
        }
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Import-Daten ohne Mandanten-Schema");
        }
        return schema;
    }

    private static String ergebnis(Ergebnis ergebnis) {
        return ergebnis == Ergebnis.OFFEN ? null : ergebnis.name();
    }

    private static String daten(List<String> werte) {
        if (werte == null) {
            return null;
        }
        ObjectNode daten = JSON.createObjectNode();
        daten.set("werte", liste(werte));
        return json(daten);
    }

    private static ArrayNode liste(List<String> werte) {
        ArrayNode liste = JSON.createArrayNode();
        werte.forEach(liste::add);
        return liste;
    }

    private static String json(JsonNode knoten) {
        try {
            return JSON.writeValueAsString(knoten);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON nicht serialisierbar", e);
        }
    }
}
