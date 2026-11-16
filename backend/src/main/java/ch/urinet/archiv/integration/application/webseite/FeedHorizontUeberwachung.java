package ch.urinet.archiv.integration.application.webseite;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Erkennt den Stillstand des Horizonts des Änderungs-Feeds (Spec 03, Aufgabe 11.9, design.md «Änderungs-Feed», Grenze
 * des Cursors): {@code pg_snapshot_xmin} gilt für den ganzen Datenbank-Cluster; hält eine lange offene Transaktion ihn
 * an, liefert der Feed aller Mandanten nichts Neues, bis sie endet. Stillstand heisst: Der Horizont hat sich seit
 * {@code archiv.webseite.feed-stillstand} nicht bewegt, obwohl noch Transaktionen offen sind
 * ({@code xmin < xmax}); eine ruhige Datenbank ohne offene Transaktion ist kein Stillstand.
 */
@ApplicationScoped
public class FeedHorizontUeberwachung {

    private final AenderungFeedPort port;
    private final Duration grenze;
    private long zuletztXmin = -1;
    private Instant seit = Instant.EPOCH;

    public FeedHorizontUeberwachung(AenderungFeedPort port,
                                    @ConfigProperty(name = "archiv.webseite.feed-stillstand") Duration grenze) {
        this.port = port;
        this.grenze = grenze;
    }

    /** Liest den Horizont; nennt die Dauer des Stillstands, wenn sie die Grenze überschreitet. */
    public Optional<Duration> pruefen(Instant jetzt) {
        AenderungFeedPort.Horizont horizont = port.horizont();
        return beobachten(horizont.xmin(), horizont.xmax(), jetzt);
    }

    synchronized Optional<Duration> beobachten(long xmin, long xmax, Instant jetzt) {
        if (xmin != zuletztXmin) {
            zuletztXmin = xmin;
            seit = jetzt;
            return Optional.empty();
        }
        if (xmin >= xmax) {
            seit = jetzt;
            return Optional.empty();
        }
        Duration dauer = Duration.between(seit, jetzt);
        return dauer.compareTo(grenze) > 0 ? Optional.of(dauer) : Optional.empty();
    }
}
