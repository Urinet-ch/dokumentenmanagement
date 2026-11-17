package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.integration.domain.webseite.Grenzklasse;
import ch.urinet.archiv.integration.domain.webseite.TokenBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Grenzen je Webseiten-Schlüssel (Spec 03, Req 15.1; design.md «Grenzen»): je Schlüssel-ID (nie je Header-Wert) fünf
 * Token-Buckets: Lesen, Suche, Erfassung, Uploads, Prüfdatei. Eine Anfrage nimmt aus allen Buckets ihrer {@link Grenzklasse} je eine
 * Marke, oder aus keinem (eine abgelehnte Anfrage kostet nichts). Die Suche zählt im Bucket «Lesen» mit und hat dazu
 * einen strengeren eigenen; ein Upload zählt im Bucket «Erfassung» mit.
 * <ul>
 *   <li>Die Zähler liegen im Speicher dieser Instanz (Stufe A hat eine); nach einem Neustart beginnen alle Buckets voll,
 *       mehrere Instanzen bräuchten einen gemeinsamen Zähler (design.md, Open Points).</li>
 *   <li>Speicher begrenzt: höchstens {@code max-schluessel} Einträge; nur ein angemeldeter, gültiger Schlüssel legt einen
 *       an. Ist die Tabelle voll, fliegen Einträge im Leerlauf (alle Buckets voll, also ohne Verlust) hinaus, höchstens
 *       einmal je Minute; reicht das nicht, teilen sich die weiteren Schlüssel einen Sammel-Eintrag.</li>
 *   <li>Ein Fehler der Prüfung selbst lässt die Anfrage durch (die Anmeldung davor bleibt fail closed): Die Prüfung rechnet
 *       nur im Speicher; ein Programmfehler soll die Webseite nicht ausschalten. Er zählt in der Kennzahl und steht im
 *       Log nur mit Klassennamen.</li>
 * </ul>
 */
@ApplicationScoped
public class WebseitenGrenzen {

    private static final Logger LOG = Logger.getLogger(WebseitenGrenzen.class);
    private static final Duration AUFRAEUM_ABSTAND = Duration.ofMinutes(1);
    private static final long WARTEZEIT_MAX_SEKUNDEN = 3600;

    /** Grenzwerte (Marken je Sekunde, Spitze) je Bucket. */
    public record Einstellung(double lesenProSekunde, double lesenSpitze, double sucheProSekunde, double sucheSpitze,
                              double erfassungProSekunde, double erfassungSpitze, double uploadsProStunde,
                              double dateiProSekunde, double dateiSpitze, int maxSchluessel) {
        public Einstellung {
            if (maxSchluessel < 1) {
                throw new IllegalArgumentException("archiv.webseite.grenzen.max-schluessel muss mindestens 1 sein");
            }
        }
    }

    private static final class Eintrag {
        final TokenBucket lesen;
        final TokenBucket suche;
        final TokenBucket erfassung;
        final TokenBucket upload;
        final TokenBucket datei;
        Instant zuletzt;

        Eintrag(Einstellung e, Instant jetzt) {
            lesen = new TokenBucket(e.lesenSpitze(), e.lesenProSekunde(), jetzt);
            suche = new TokenBucket(e.sucheSpitze(), e.sucheProSekunde(), jetzt);
            erfassung = new TokenBucket(e.erfassungSpitze(), e.erfassungProSekunde(), jetzt);
            // Uploads: Fassungsvermögen = Stundenwert, Auffüllung gleichmässig über die Stunde
            upload = new TokenBucket(e.uploadsProStunde(), e.uploadsProStunde() / 3600.0, jetzt);
            // Prüfdatei: engere Grenze zusätzlich zur Erfassungsgrenze (Öffnen und unmittelbar Wiederholen; Startwert 1/s, Spitze 2)
            datei = new TokenBucket(e.dateiSpitze(), e.dateiProSekunde(), jetzt);
            zuletzt = jetzt;
        }

        boolean leerlauf(Instant jetzt) {
            return lesen.voll(jetzt) && suche.voll(jetzt) && erfassung.voll(jetzt) && upload.voll(jetzt)
                    && datei.voll(jetzt);
        }
    }

    private final Einstellung einstellung;
    private final Clock uhr;
    private final WebseitenGrenzenKennzahl kennzahl;
    private final Map<String, Eintrag> eintraege = new HashMap<>();
    private Eintrag sammel;
    private Instant letzteBereinigung = Instant.EPOCH;

    @Inject
    public WebseitenGrenzen(
            @ConfigProperty(name = "archiv.webseite.grenzen.lesen-je-sekunde") double lesenProSekunde,
            @ConfigProperty(name = "archiv.webseite.grenzen.lesen-spitze") double lesenSpitze,
            @ConfigProperty(name = "archiv.webseite.grenzen.suche-je-sekunde") double sucheProSekunde,
            @ConfigProperty(name = "archiv.webseite.grenzen.suche-spitze") double sucheSpitze,
            @ConfigProperty(name = "archiv.webseite.grenzen.erfassung-je-sekunde") double erfassungProSekunde,
            @ConfigProperty(name = "archiv.webseite.grenzen.erfassung-spitze") double erfassungSpitze,
            @ConfigProperty(name = "archiv.webseite.grenzen.uploads-je-stunde") double uploadsProStunde,
            @ConfigProperty(name = "archiv.webseite.grenzen.datei-je-sekunde") double dateiProSekunde,
            @ConfigProperty(name = "archiv.webseite.grenzen.datei-spitze") double dateiSpitze,
            @ConfigProperty(name = "archiv.webseite.grenzen.max-schluessel") int maxSchluessel,
            WebseitenGrenzenKennzahl kennzahl) {
        this(new Einstellung(lesenProSekunde, lesenSpitze, sucheProSekunde, sucheSpitze, erfassungProSekunde,
                erfassungSpitze, uploadsProStunde, dateiProSekunde, dateiSpitze, maxSchluessel), Clock.systemUTC(), kennzahl);
    }

    public WebseitenGrenzen(Einstellung einstellung, Clock uhr, WebseitenGrenzenKennzahl kennzahl) {
        // Konstruktion der Buckets prüft die Werte (Startabbruch bei ungültiger Einstellung, ohne einen Wert zu nennen)
        try {
            new Eintrag(einstellung, Instant.EPOCH);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("archiv.webseite.grenzen.* ungültig: Spitzen mindestens 1, Raten über 0");
        }
        this.einstellung = einstellung;
        this.uhr = uhr;
        this.kennzahl = kennzahl;
    }

    /**
     * Nimmt eine Anfrage des Schlüssels an oder lehnt sie ab: 429 {@code ZU_VIELE_ANFRAGEN} mit {@code Retry-After}
     * (Sekunden bis zur nächsten Marke des knappsten Buckets) und {@code grund} {@code ANFRAGEN}, {@code SUCHE} oder
     * {@code UPLOADS}.
     */
    public void pruefen(String schluesselId, Grenzklasse klasse) {
        try {
            entscheiden(schluesselId, klasse);
        } catch (ZuVieleAnfragen e) {
            kennzahl.abgelehnt(e.grund());
            throw e;
        } catch (RuntimeException e) {
            LOG.warnf("Grenze je Schlüssel nicht prüfbar (%s); Anfrage durchgelassen", e.getClass().getSimpleName());
            kennzahl.ausgefallen();
        }
    }

    private synchronized void entscheiden(String schluesselId, Grenzklasse klasse) {
        java.util.Objects.requireNonNull(klasse);
        java.util.Objects.requireNonNull(schluesselId);
        Instant jetzt = uhr.instant();
        Eintrag eintrag = eintrag(schluesselId, jetzt);
        eintrag.zuletzt = jetzt;
        long wartezeit = 0;
        ZuVieleAnfragen.Grund grund = null;
        for (Pruefung p : pruefungen(eintrag, klasse)) {
            long w = p.bucket().wartezeitSekunden(jetzt);
            if (w > wartezeit) {
                wartezeit = w;
                grund = p.grund();
            }
        }
        if (grund != null) {
            throw new ZuVieleAnfragen(grund, Math.min(wartezeit, WARTEZEIT_MAX_SEKUNDEN));
        }
        pruefungen(eintrag, klasse).forEach(p -> p.bucket().nehmen());
    }

    private record Pruefung(TokenBucket bucket, ZuVieleAnfragen.Grund grund) {
    }

    private static java.util.List<Pruefung> pruefungen(Eintrag e, Grenzklasse klasse) {
        return switch (klasse) {
            case LESEN -> java.util.List.of(new Pruefung(e.lesen, ZuVieleAnfragen.Grund.ANFRAGEN));
            case SUCHE -> java.util.List.of(new Pruefung(e.lesen, ZuVieleAnfragen.Grund.ANFRAGEN),
                    new Pruefung(e.suche, ZuVieleAnfragen.Grund.SUCHE));
            case ERFASSUNG -> java.util.List.of(new Pruefung(e.erfassung, ZuVieleAnfragen.Grund.ANFRAGEN));
            case UPLOAD -> java.util.List.of(new Pruefung(e.erfassung, ZuVieleAnfragen.Grund.ANFRAGEN),
                    new Pruefung(e.upload, ZuVieleAnfragen.Grund.UPLOADS));
            case DATEI -> java.util.List.of(new Pruefung(e.erfassung, ZuVieleAnfragen.Grund.ANFRAGEN),
                    new Pruefung(e.datei, ZuVieleAnfragen.Grund.ANFRAGEN));
        };
    }

    private Eintrag eintrag(String schluesselId, Instant jetzt) {
        Eintrag vorhanden = eintraege.get(schluesselId);
        if (vorhanden != null) {
            return vorhanden;
        }
        if (eintraege.size() >= einstellung.maxSchluessel()) {
            if (Duration.between(letzteBereinigung, jetzt).compareTo(AUFRAEUM_ABSTAND) >= 0) {
                letzteBereinigung = jetzt;
                eintraege.values().removeIf(e -> e.leerlauf(jetzt));
            }
            if (eintraege.size() >= einstellung.maxSchluessel()) {
                if (sammel == null) {
                    sammel = new Eintrag(einstellung, jetzt);
                }
                return sammel;
            }
        }
        Eintrag neu = new Eintrag(einstellung, jetzt);
        eintraege.put(schluesselId, neu);
        return neu;
    }

    /** Zahl der Einträge (für Tests). */
    synchronized int eintraege() {
        return eintraege.size();
    }
}
