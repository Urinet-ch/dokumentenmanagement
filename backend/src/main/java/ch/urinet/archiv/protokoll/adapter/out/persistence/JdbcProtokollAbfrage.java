package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.ProtokollAbfrage;
import ch.urinet.archiv.protokoll.domain.ProtokollFilter;
import ch.urinet.archiv.protokoll.domain.ProtokollZeile;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Einsicht ins Zugriffsprotokoll per JDBC (Req 10.5), Schema immer qualifiziert. Name und Rolle stammen aus der Zeile
 * des Benutzerverzeichnisses, die zur Zeit des Eintrags die letzte war (Req 10.10); für ältere Einträge ohne solche
 * Zeile aus der ersten Zeile der Kennung. Nur Lesen.
 */
@ApplicationScoped
public class JdbcProtokollAbfrage implements ProtokollAbfrage {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcProtokollAbfrage(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public ProtokollZeile.Seite suchen(ProtokollFilter filter) {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Zugriffsprotokoll ohne Mandanten-Schema");
        }
        List<Object> werte = new ArrayList<>();
        String bedingung = bedingung(filter, werte);
        String zaehlen = "select count(*) from " + schema + ".zugriff_protokoll z" + bedingung;
        String lesen = "select z.id, z.zeit, z.benutzer, v.name, coalesce(z.rolle, v.rolle) as rolle, z.aktion, "
                + "z.dokument_id, host(z.ip) as ip, z.details::text as details from " + schema + ".zugriff_protokoll z "
                + "left join lateral (select k.name, k.rolle from ("
                + "(select b.name, b.rolle, 0 as rang from " + schema + ".benutzer_verzeichnis b "
                + "where b.benutzer = z.benutzer and b.zeit <= z.zeit order by b.id desc limit 1) union all "
                + "(select b.name, b.rolle, 1 from " + schema + ".benutzer_verzeichnis b "
                + "where b.benutzer = z.benutzer order by b.id limit 1)) k order by k.rang limit 1) v on true"
                + bedingung + " order by z.zeit desc, z.id desc limit ? offset ?";
        try (Connection verbindung = datenquelle.get().getConnection()) {
            long gesamt;
            try (PreparedStatement abfrage = verbindung.prepareStatement(zaehlen)) {
                binden(abfrage, werte);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    zeile.next();
                    gesamt = zeile.getLong(1);
                }
            }
            List<ProtokollZeile> eintraege = new ArrayList<>();
            try (PreparedStatement abfrage = verbindung.prepareStatement(lesen)) {
                int index = binden(abfrage, werte);
                abfrage.setInt(index, filter.groesse());
                abfrage.setLong(index + 1, filter.versatz());
                try (ResultSet zeile = abfrage.executeQuery()) {
                    while (zeile.next()) {
                        eintraege.add(zeile(zeile));
                    }
                }
            }
            return new ProtokollZeile.Seite(eintraege, gesamt);
        } catch (SQLException e) {
            throw new IllegalStateException("Zugriffsprotokoll nicht lesbar", e);
        }
    }

    private static String bedingung(ProtokollFilter filter, List<Object> werte) {
        List<String> teile = new ArrayList<>();
        filter.von().ifPresent(von -> {
            teile.add("z.zeit >= ?");
            werte.add(von.atOffset(ZoneOffset.UTC));
        });
        filter.bis().ifPresent(bis -> {
            teile.add("z.zeit < ?");
            werte.add(bis.atOffset(ZoneOffset.UTC));
        });
        filter.benutzer().ifPresent(benutzer -> {
            teile.add("z.benutzer = ?");
            werte.add(benutzer);
        });
        filter.dokumentId().ifPresent(id -> {
            teile.add("z.dokument_id = ?");
            werte.add(id);
        });
        filter.aktion().ifPresent(aktion -> {
            teile.add("z.aktion = ?");
            werte.add(aktion.name());
        });
        return teile.isEmpty() ? "" : " where " + String.join(" and ", teile);
    }

    private static int binden(PreparedStatement abfrage, List<Object> werte) throws SQLException {
        int index = 1;
        for (Object wert : werte) {
            switch (wert) {
                case OffsetDateTime zeit -> abfrage.setObject(index, zeit, Types.TIMESTAMP_WITH_TIMEZONE);
                case UUID id -> abfrage.setObject(index, id, Types.OTHER);
                default -> abfrage.setString(index, wert.toString());
            }
            index++;
        }
        return index;
    }

    private static ProtokollZeile zeile(ResultSet zeile) throws SQLException {
        return new ProtokollZeile(zeile.getLong("id"), zeile.getObject("zeit", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(zeile.getString("benutzer")), Optional.ofNullable(zeile.getString("name")),
                Optional.ofNullable(zeile.getString("rolle")), zeile.getString("aktion"),
                Optional.ofNullable(zeile.getObject("dokument_id", UUID.class)),
                Optional.ofNullable(zeile.getString("ip")), Optional.ofNullable(zeile.getString("details")));
    }
}
