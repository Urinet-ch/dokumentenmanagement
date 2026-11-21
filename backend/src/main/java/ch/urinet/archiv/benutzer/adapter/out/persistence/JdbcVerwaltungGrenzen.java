package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcVerwaltungGrenzen implements VerwaltungGrenzen {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final Duration STUNDE = Duration.ofHours(1);
    private static final Duration TAG = Duration.ofHours(24);
    private static final String AKTIONEN = "(aktion in ('BENUTZER_ANLEGEN', 'BENUTZER_AENDERN', "
            + "'BENUTZER_LOESCHEN', 'ROLLE_ZUWEISEN') and details->>'phase' = 'ANGEFRAGT' "
            + "or aktion = 'BENUTZER_ANTRAG' and coalesce(details->>'phase', 'ANGEFRAGT') = 'ANGEFRAGT' "
            + "or aktion = 'KONFIGURATION_AENDERN')";
    private static final String EMAILS = "details->>'ziel' = ? and "
            + "(aktion = 'BENUTZER_ANLEGEN' and details->>'phase' in ('ABGESCHLOSSEN', 'UNKLAR', 'FEHLGESCHLAGEN') "
            + "or aktion = 'BENUTZER_AENDERN' and details->>'phase' = 'ANGEFRAGT' "
            + "and details->>'vorgang' in ('EINLADUNG_ERNEUT', 'ZUGANG_ZURUECKSETZEN', 'REAKTIVIEREN'))";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final int aktionen;
    private final int anlagen;
    private final int emails;
    private final int einstellungen;

    public JdbcVerwaltungGrenzen(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
            @ConfigProperty(name = "archiv.verwaltung.grenzen.aktionen-je-akteur") int aktionen,
            @ConfigProperty(name = "archiv.verwaltung.grenzen.anlagen-je-mandant") int anlagen,
            @ConfigProperty(name = "archiv.verwaltung.grenzen.emails-je-ziel") int emails,
            @ConfigProperty(name = "archiv.verwaltung.grenzen.einstellungen-je-mandant")
            int einstellungen) {
        if (aktionen < 1 || anlagen < 1 || emails < 1 || einstellungen < 1) {
            throw new IllegalArgumentException("Verwaltungsgrenzen müssen mindestens 1 sein");
        }
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.aktionen = aktionen;
        this.anlagen = anlagen;
        this.emails = emails;
        this.einstellungen = einstellungen;
    }

    @Override
    public void pruefen(String akteur, Art art, UUID ziel) {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Verwaltungsgrenzen nur in einer Transaktion prüfen");
        }
        if (akteur == null || akteur.isBlank()) {
            throw new IllegalArgumentException("Akteur ist Pflicht");
        }
        Objects.requireNonNull(art);
        if (art == Art.EMAIL && ziel == null) {
            throw new IllegalArgumentException("Ziel einer E-Mail ist Pflicht");
        }
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Verwaltungsgrenzen ohne Mandanten-Schema");
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement sperre = verbindung.prepareStatement("select pg_advisory_xact_lock(1907, hashtext(?))")) {
            sperre.setString(1, schema);
            try (ResultSet zeile = sperre.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Verwaltungsgrenzen nicht gesperrt");
                }
            }
            Instant jetzt;
            try (PreparedStatement uhr = verbindung.prepareStatement("select clock_timestamp()");
                 ResultSet zeile = uhr.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Zeit für Verwaltungsgrenzen nicht lesbar");
                }
                jetzt = zeile.getObject(1, OffsetDateTime.class).toInstant();
            }
            pruefen(verbindung, schema, "benutzer = ? and " + AKTIONEN, akteur, aktionen, STUNDE, jetzt);
            switch (art) {
                case ANLEGEN -> pruefen(verbindung, schema,
                        "aktion = 'BENUTZER_ANLEGEN' and details->>'phase' = 'ANGEFRAGT'",
                        null, anlagen, STUNDE, jetzt);
                case EMAIL -> pruefen(verbindung, schema, EMAILS, ziel.toString(), emails, TAG, jetzt);
                case EINSTELLUNG -> pruefen(verbindung, schema, "aktion = 'KONFIGURATION_AENDERN'",
                        null, einstellungen, STUNDE, jetzt);
                case BENUTZER -> { }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Verwaltungsgrenzen nicht geprüft", e);
        }
    }

    private static void pruefen(Connection verbindung, String schema, String filter, String wert, int grenze,
                                Duration fenster, Instant jetzt) throws SQLException {
        String sql = "select zeit from " + schema + ".zugriff_protokoll where zeit > ? and " + filter
                + " order by zeit desc limit 1 offset ?";
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            int parameter = 1;
            abfrage.setObject(parameter++, jetzt.minus(fenster).atOffset(ZoneOffset.UTC));
            if (wert != null) {
                abfrage.setString(parameter++, wert);
            }
            abfrage.setInt(parameter, grenze - 1);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (zeile.next()) {
                    Instant freiAb = zeile.getObject(1, OffsetDateTime.class).toInstant().plus(fenster);
                    Duration wartezeit = Duration.between(jetzt, freiAb);
                    long sekunden = wartezeit.getSeconds() + (wartezeit.getNano() == 0 ? 0 : 1);
                    throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.VERWALTUNG, Math.max(1, sekunden));
                }
            }
        }
    }
}
