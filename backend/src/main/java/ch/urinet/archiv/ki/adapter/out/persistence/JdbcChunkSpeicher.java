package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.domain.Chunk;
import ch.urinet.archiv.ki.domain.Chunker;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
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
 * {@code m_<code>.chunk} des Mandanten aus dem Kontext (Spec 02, Req 2.1, 2.4; Schema immer qualifiziert, Typ
 * {@code public.halfvec} aus pgvector). Schreibt in der laufenden Transaktion (Agroal bindet die Verbindung in die
 * JTA-Transaktion ein). {@link #ersetzen} sperrt zuerst die Zeile des Dokuments ({@code for no key update}), damit zwei
 * Aufträge für dasselbe Dokument nacheinander ersetzen. Vektoren gehen als Text ({@code [x,y,…]}) an PostgreSQL.
 * Fehlermeldungen ohne Inhalte.
 */
@ApplicationScoped
public class JdbcChunkSpeicher implements ChunkPort {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final int ZEITGRENZE_SEKUNDEN = 30;

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcChunkSpeicher(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Optional<Stand> laden(UUID dokumentId) {
        String s = schema();
        String sql = "select d.id, d.status, d.titel, a.name, d.partner_name, d.volltext, d.seiten, d.pdfa_fehlt from "
                + s + ".dokument d left join " + s + ".dokumentart a on a.code = d.art where d.id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_SEKUNDEN);
            abfrage.setObject(1, dokumentId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return Optional.empty();
                }
                int seiten = zeile.getInt(7);
                Integer seitenOderNull = zeile.wasNull() ? null : seiten;
                Chunker.Quelle quelle = new Chunker.Quelle(zeile.getString(3), zeile.getString(4), zeile.getString(5),
                        zeile.getString(6), seitenOderNull, zeile.getBoolean(8));
                return Optional.of(new Stand(dokumentId, "ARCHIVIERT".equals(zeile.getString(2)), quelle));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dokument " + dokumentId + " für die Abschnitte nicht gelesen", e);
        }
    }

    @Override
    public boolean ersetzen(UUID dokumentId, List<Eingebettet> chunks) {
        String s = schema();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            try (PreparedStatement sperren = verbindung.prepareStatement(
                    "select id from " + s + ".dokument where id = ? for no key update")) {
                sperren.setQueryTimeout(ZEITGRENZE_SEKUNDEN);
                sperren.setObject(1, dokumentId);
                try (ResultSet zeile = sperren.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Dokument " + dokumentId + " fehlt im Mandanten");
                    }
                }
            }
            if (chunks.stream().anyMatch(c -> c.vektor() == null)) {
                try (PreparedStatement mitVektor = verbindung.prepareStatement("select 1 from " + s
                        + ".chunk where dokument_id = ? and embedding is not null limit 1")) {
                    mitVektor.setQueryTimeout(ZEITGRENZE_SEKUNDEN);
                    mitVektor.setObject(1, dokumentId);
                    try (ResultSet zeile = mitVektor.executeQuery()) {
                        if (zeile.next()) {
                            return false;
                        }
                    }
                }
            }
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "delete from " + s + ".chunk where dokument_id = ?")) {
                loeschen.setQueryTimeout(ZEITGRENZE_SEKUNDEN);
                loeschen.setObject(1, dokumentId);
                loeschen.executeUpdate();
            }
            if (chunks.isEmpty()) {
                return true;
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + s
                    + ".chunk (dokument_id, nr, text, seite_von, seite_bis, embedding) "
                    + "values (?, ?, ?, ?, ?, ?::public.halfvec)")) {
                einfuegen.setQueryTimeout(ZEITGRENZE_SEKUNDEN);
                for (Eingebettet eingebettet : chunks) {
                    Chunk chunk = eingebettet.chunk();
                    einfuegen.setObject(1, dokumentId);
                    einfuegen.setInt(2, chunk.nr());
                    einfuegen.setString(3, chunk.text());
                    setzeZahl(einfuegen, 4, chunk.seiteVon());
                    setzeZahl(einfuegen, 5, chunk.seiteBis());
                    if (eingebettet.vektor() == null) {
                        einfuegen.setNull(6, Types.VARCHAR);
                    } else {
                        einfuegen.setString(6, text(eingebettet.vektor()));
                    }
                    einfuegen.addBatch();
                }
                einfuegen.executeBatch();
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Abschnitte von Dokument " + dokumentId + " nicht gespeichert", e);
        }
    }

    @Override
    public List<UUID> zurNachberechnung(Optional<UUID> nach, int anzahl, boolean nurFehlende) {
        String s = schema();
        String sql = "select d.id from " + s + ".dokument d where d.status = 'ARCHIVIERT'"
                + (nach.isPresent() ? " and d.id > ?" : "")
                + (nurFehlende ? " and (not exists (select 1 from " + s + ".chunk c where c.dokument_id = d.id)"
                + " or exists (select 1 from " + s + ".chunk c where c.dokument_id = d.id and c.embedding is null))"
                : "")
                + " order by d.id limit ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_SEKUNDEN);
            int i = 1;
            if (nach.isPresent()) {
                abfrage.setObject(i++, nach.get());
            }
            abfrage.setInt(i, anzahl);
            List<UUID> ids = new ArrayList<>();
            try (ResultSet zeilen = abfrage.executeQuery()) {
                while (zeilen.next()) {
                    ids.add(zeilen.getObject(1, UUID.class));
                }
            }
            return List.copyOf(ids);
        } catch (SQLException e) {
            throw new IllegalStateException("Dokumente zur Nachberechnung nicht gelesen", e);
        }
    }

    static String text(float[] vektor) {
        StringBuilder text = new StringBuilder(vektor.length * 12).append('[');
        for (int i = 0; i < vektor.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vektor[i]);
        }
        return text.append(']').toString();
    }

    private static void setzeZahl(PreparedStatement anweisung, int index, Integer wert) throws SQLException {
        if (wert == null) {
            anweisung.setNull(index, Types.INTEGER);
        } else {
            anweisung.setInt(index, wert);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Abschnitte ohne Mandanten-Schema");
        }
        return schema;
    }
}
