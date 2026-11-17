package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * Auftrag {@code FEED_BEREINIGEN} (Spec 03, Aufgabe 11.9, Req 14.4): löscht je Mandant die Einträge des Änderungs-Feeds
 * nach der Aufbewahrung ({@code archiv.webseite.feed-aufbewahrung}, Standard {@code P90D}) und merkt sich in
 * {@code webseite_feed_stand.bereinigt_bis}, bis zu welcher Transaktion Einträge fehlen können; ein Cursor darunter ist
 * 410 {@code CURSOR_ABGELAUFEN}. Erlaubt sind 1 bis 365 Tage; ein Wert ausserhalb bricht den Start ab (der Feed soll
 * weder sofort noch nie verschwinden). Nicht in einer laufenden Transaktion aufrufen.
 */
@ApplicationScoped
public class AenderungFeedAufraeumen {

    public static final Duration MIN = Duration.ofDays(1);
    public static final Duration MAX = Duration.ofDays(365);

    private final MandantKontext kontext;
    private final AenderungFeedPort port;
    private final Duration aufbewahrung;

    public AenderungFeedAufraeumen(MandantKontext kontext, AenderungFeedPort port,
                                   @ConfigProperty(name = "archiv.webseite.feed-aufbewahrung") Duration aufbewahrung) {
        if (aufbewahrung.compareTo(MIN) < 0 || aufbewahrung.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("archiv.webseite.feed-aufbewahrung muss zwischen 1 und 365 Tagen liegen");
        }
        this.kontext = kontext;
        this.port = port;
        this.aufbewahrung = aufbewahrung;
    }

    /** Grenze für {@code jetzt}: was davor liegt, wird gelöscht. */
    public Instant grenze(Instant jetzt) {
        return jetzt.minus(aufbewahrung);
    }

    /** Anzahl der gelöschten Einträge des Mandanten. */
    public int aufraeumen(MandantCode mandant, Instant jetzt) {
        Instant grenze = grenze(jetzt);
        return kontext.mit(mandant, () -> port.bereinigen(grenze));
    }
}
