package ch.urinet.archiv.importe.adapter.out.persistence;

import ch.urinet.archiv.importe.application.ImportRepository;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
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

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcImportRepository(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
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
        String sql = "update " + schema() + ".import_lauf set status = ?, zeilen = ?, fehler = ? where id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            aendern.setString(1, lauf.status().name());
            aendern.setInt(2, lauf.zeilen());
            aendern.setInt(3, lauf.fehler());
            aendern.setObject(4, lauf.id(), Types.OTHER);
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

    private Optional<ImportLauf> lauf(UUID id, boolean sperren) {
        String schema = schema();
        String sql = "select l.id, l.art, l.vollexport, l.dateiname, l.sha256, l.zeichensatz, l.trennzeichen, "
                + "l.kopfzeile::text, l.zeilen, l.fehler, l.status, l.benutzer, l.erstellt, "
                + "(select count(*) from " + schema + ".import_zeile z where z.lauf_id = l.id "
                + "and z.ergebnis = 'UEBERSPRUNGEN') as uebersprungen "
                + "from " + schema + ".import_lauf l where l.id = ?" + (sperren ? " for update of l" : "");
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id, Types.OTHER);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                if (!ergebnis.next()) {
                    return Optional.empty();
                }
                ImportArt art = ImportArt.aus(ergebnis.getString("art"))
                        .orElseThrow(() -> new IllegalStateException("unbekannte Importart in import_lauf"));
                return Optional.of(new ImportLauf(ergebnis.getObject("id", UUID.class), art,
                        ergebnis.getBoolean("vollexport"), ergebnis.getString("dateiname"),
                        ergebnis.getString("sha256"), ergebnis.getString("zeichensatz"),
                        ergebnis.getString("trennzeichen").charAt(0),
                        new Kopfzeile(JSON.readValue(ergebnis.getString("kopfzeile"), new TypeReference<>() {
                        })), ergebnis.getInt("zeilen"), ergebnis.getInt("fehler"),
                        ergebnis.getInt("uebersprungen"), LaufStatus.valueOf(ergebnis.getString("status")),
                        ergebnis.getString("benutzer"), ergebnis.getTimestamp("erstellt").toInstant()));
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Lauf nicht gelesen", e);
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
        String sql = "select nr, daten, ergebnis, meldung from (select nr, daten, ergebnis, meldung, "
                + "sum(coalesce(length(daten), 0)) over (order by nr) - coalesce(length(daten), 0) as davor "
                + "from (select nr, daten::text as daten, ergebnis, meldung from " + schema()
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
                    String text = ergebnis.getString("daten");
                    List<String> werte = null;
                    if (text != null) {
                        werte = new ArrayList<>();
                        for (JsonNode wert : JSON.readTree(text).path("werte")) {
                            werte.add(wert.isNull() ? null : wert.asText());
                        }
                    }
                    String code = ergebnis.getString("ergebnis");
                    zeilen.add(new ImportZeile(ergebnis.getInt("nr"), werte,
                            code == null ? Ergebnis.OFFEN : Ergebnis.valueOf(code), ergebnis.getString("meldung")));
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Import-Zeilen nicht gelesen", e);
        }
        return zeilen;
    }

    @Override
    public void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen) {
        String sql = "update " + schema() + ".import_zeile set ergebnis = ?, meldung = ? where lauf_id = ? and nr = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            for (ImportZeile zeile : zeilen) {
                aendern.setString(1, ergebnis(zeile.ergebnis()));
                aendern.setString(2, zeile.meldung());
                aendern.setObject(3, laufId, Types.OTHER);
                aendern.setInt(4, zeile.nr());
                aendern.addBatch();
            }
            aendern.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Prüfung der Import-Zeilen nicht gespeichert", e);
        }
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
                JsonNode spalten = JSON.readTree(ergebnis.getString("spalten"));
                Map<String, Integer> felder = new LinkedHashMap<>();
                spalten.path("felder").properties().forEach(e -> felder.put(e.getKey(), e.getValue().asInt()));
                String profil = spalten.path("profil").isTextual() ? spalten.path("profil").asText() : null;
                return Optional.of(new GespeicherteZuordnung(art, new Zuordnung(profil, felder),
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
        ObjectNode spalten = JSON.createObjectNode();
        if (zuordnung.zuordnung().profil() == null) {
            spalten.putNull("profil");
        } else {
            spalten.put("profil", zuordnung.zuordnung().profil());
        }
        ObjectNode felder = spalten.putObject("felder");
        zuordnung.zuordnung().felder().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> felder.put(e.getKey(), e.getValue()));
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
