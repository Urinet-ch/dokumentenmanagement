package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragenSpeicher;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code m_<code>.oeffentliche_frage} des Mandanten aus dem Kontext (Changeset {@code mandant/030}, Schema immer
 * qualifiziert): im Archivchat bereinigte Frage und erweiterte Quellen; im Wissenschat feste Ereignisbezeichnung und
 * Quellen-IDs. Jeweils mit Tokens und Zeit; nie Antwort, Client, IP, Schlüssel oder Webseite (Spec 03, Req 6.5).
 * Jede Operation in eigener Transaktion mit Zeitgrenze.
 */
@ApplicationScoped
public class JdbcOeffentlicheFragen implements OeffentlicheFragenSpeicher {

    static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final ObjectMapper json;

    public JdbcOeffentlicheFragen(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                  ObjectMapper json) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.json = json;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void speichern(String frage, List<OeffentlicheQuelle> quellen, int tokens, Instant zeit) {
        ArrayNode knoten = json.createArrayNode();
        for (OeffentlicheQuelle q : quellen) {
            var quelle = knoten.addObject().put("dokumentId", q.dokumentId().toString()).put("titel", q.titel())
                    .put("reihe", q.reiheCode()).put("ausgabe", q.ausgabe());
            if (q.jahr() == null) {
                quelle.putNull("jahr");
            } else {
                quelle.put("jahr", q.jahr());
            }
            if (q.seite() == null) {
                quelle.putNull("fundstelle");
            } else {
                quelle.putObject("fundstelle").put("seite", q.seite());
            }
        }
        String quellenJson;
        try {
            quellenJson = json.writeValueAsString(knoten);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Quellen nicht gespeichert", e);
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema()
                     + ".oeffentliche_frage (frage, quellen, tokens, zeit) values (?, ?::jsonb, ?, ?)")) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            befehl.setString(1, frage);
            befehl.setString(2, quellenJson);
            befehl.setInt(3, Math.max(0, tokens));
            befehl.setTimestamp(4, Timestamp.from(zeit));
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Öffentliche Frage nicht geschrieben", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int loeschenVor(Instant grenze) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("delete from " + schema()
                     + ".oeffentliche_frage where zeit < ?")) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            befehl.setTimestamp(1, Timestamp.from(grenze));
            return befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Öffentliche Fragen nicht gelöscht", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Öffentliche Fragen ohne Mandanten-Schema");
        }
        return schema;
    }
}
