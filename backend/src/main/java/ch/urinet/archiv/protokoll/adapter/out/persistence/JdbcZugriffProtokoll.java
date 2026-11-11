package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.regex.Pattern;

/**
 * Schreibt per JDBC in {@code m_<code>.zugriff_protokoll} des Mandanten der Anfrage (Schema immer qualifiziert,
 * design.md «Mandant»). Die Verbindung hängt an der laufenden JTA-Transaktion; ohne Transaktion bricht er ab, damit ein
 * Eintrag nie getrennt von der Aktion festgeschrieben wird (Req 10.2, 10.8).
 */
@ApplicationScoped
public class JdbcZugriffProtokoll implements ZugriffProtokoll {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcZugriffProtokoll(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public void schreiben(ZugriffEintrag eintrag) {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Zugriffsprotokoll nur in einer Transaktion");
        }
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Zugriffsprotokoll ohne Mandanten-Schema");
        }
        String sql = "insert into " + schema + ".zugriff_protokoll (benutzer, rolle, aktion, dokument_id, ip, details) "
                + "values (?, ?, ?, ?, cast(? as inet), cast(? as jsonb))";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setString(1, eintrag.benutzer().orElse(null));
            einfuegen.setString(2, eintrag.rolle().orElse(null));
            einfuegen.setString(3, eintrag.aktion().name());
            einfuegen.setObject(4, eintrag.dokumentId().orElse(null), Types.OTHER);
            einfuegen.setString(5, eintrag.ip().map(IpAdresse::text).orElse(null));
            einfuegen.setString(6, json(eintrag.details()));
            einfuegen.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Zugriffsprotokoll nicht geschrieben", e);
        }
    }

    private static String json(ProtokollDetails details) {
        if (details.leer()) {
            return null;
        }
        ObjectNode knoten = JSON.createObjectNode();
        if (details.grund() != null) {
            knoten.put("grund", details.grund());
        }
        if (details.sha256() != null) {
            knoten.put("sha256", details.sha256());
        }
        if (details.fehlercode() != null) {
            knoten.put("fehlercode", details.fehlercode());
        }
        if (details.regelAenderung() != null) {
            knoten.put("dokumentart", details.regelAenderung().dokumentart());
            regel(knoten.putObject("alt"), details.regelAenderung().alt());
            regel(knoten.putObject("neu"), details.regelAenderung().neu());
        }
        try {
            return JSON.writeValueAsString(knoten);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Details nicht serialisierbar", e);
        }
    }

    private static void regel(ObjectNode knoten, ProtokollDetails.Regel regel) {
        knoten.put("aufbewahrungJahre", regel.aufbewahrungJahre());
        knoten.put("aufbewahrungAb", regel.aufbewahrungAb());
        knoten.put("gesetzlichePflicht", regel.gesetzlichePflicht());
    }
}
