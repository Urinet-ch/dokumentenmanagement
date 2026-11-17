package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Begrenzung neuer API-Token-Prüfungen je Quelladresse, bevor eine Argon2-Rechnung die {@link HashSperre} belegt. */
@ApplicationScoped
public class ApiTokenPruefGrenzen {

    private static final String OHNE_QUELLE = "-";
    private static final Duration AUFRAEUM_ABSTAND = Duration.ofMinutes(1);
    private static final long WARTEZEIT_MAX_SEKUNDEN = 3600;

    public record Einstellung(double proSekunde, double spitze, int maxQuellen) {
        public Einstellung {
            if (!(proSekunde > 0) || !(spitze >= 1) || maxQuellen < 1 || maxQuellen > 10000) {
                throw new IllegalArgumentException("Grenzen der API-Token-Prüfung ungültig");
            }
        }
    }

    private static final class Bucket {
        private final double spitze;
        private final double proSekunde;
        private double marken;
        private Instant stand;

        Bucket(Einstellung einstellung, Instant jetzt) {
            spitze = einstellung.spitze();
            proSekunde = einstellung.proSekunde();
            marken = spitze;
            stand = jetzt;
        }

        long wartezeitSekunden(Instant jetzt) {
            auffuellen(jetzt);
            if (marken >= 1) {
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - marken) / proSekunde - 1e-9));
        }

        void nehmen() {
            marken -= 1;
        }

        boolean voll(Instant jetzt) {
            auffuellen(jetzt);
            return marken >= spitze;
        }

        private void auffuellen(Instant jetzt) {
            if (jetzt.isAfter(stand)) {
                double sekunden = (jetzt.toEpochMilli() - stand.toEpochMilli()) / 1000.0;
                marken = Math.min(spitze, marken + sekunden * proSekunde);
                stand = jetzt;
            }
        }
    }

    private final Einstellung einstellung;
    private final Clock uhr;
    private final Map<String, Bucket> quellen = new HashMap<>();
    private Bucket sammel;
    private Instant letzteBereinigung = Instant.EPOCH;

    @Inject
    public ApiTokenPruefGrenzen(
            @ConfigProperty(name = "archiv.api-token.grenzen.pro-sekunde") double proSekunde,
            @ConfigProperty(name = "archiv.api-token.grenzen.spitze") double spitze,
            @ConfigProperty(name = "archiv.api-token.grenzen.max-quellen") int maxQuellen) {
        this(new Einstellung(proSekunde, spitze, maxQuellen), Clock.systemUTC());
    }

    ApiTokenPruefGrenzen(Einstellung einstellung, Clock uhr) {
        this.einstellung = einstellung;
        this.uhr = uhr;
    }

    /** Jede Prüfung ohne gültigen Cache-Treffer nimmt vor der HashSperre eine Marke. */
    public synchronized void pruefen(Optional<String> quelle) {
        Instant jetzt = uhr.instant();
        String adresse = quelle.filter(wert -> !wert.isBlank()).map(String::strip).orElse(OHNE_QUELLE);
        Bucket bucket = bucket(adresse, jetzt);
        long wartezeit = bucket.wartezeitSekunden(jetzt);
        if (wartezeit > 0) {
            throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.SCHLUESSELPRUEFUNG,
                    Math.min(wartezeit, WARTEZEIT_MAX_SEKUNDEN));
        }
        bucket.nehmen();
    }

    private Bucket bucket(String quelle, Instant jetzt) {
        Bucket vorhanden = quellen.get(quelle);
        if (vorhanden != null) {
            return vorhanden;
        }
        if (quellen.size() >= einstellung.maxQuellen()) {
            if (Duration.between(letzteBereinigung, jetzt).compareTo(AUFRAEUM_ABSTAND) >= 0) {
                letzteBereinigung = jetzt;
                quellen.values().removeIf(eintrag -> eintrag.voll(jetzt));
            }
            if (quellen.size() >= einstellung.maxQuellen()) {
                if (sammel == null) {
                    sammel = new Bucket(einstellung, jetzt);
                }
                return sammel;
            }
        }
        Bucket neu = new Bucket(einstellung, jetzt);
        quellen.put(quelle, neu);
        return neu;
    }

    int quellen() {
        return quellen.size();
    }

    synchronized void fuerSzenarioZuruecksetzen() {
        quellen.clear();
        sammel = null;
        letzteBereinigung = Instant.EPOCH;
    }
}
