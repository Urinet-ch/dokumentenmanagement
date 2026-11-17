package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of removing unlocked object versions from one tenant bucket. */
public record MandantAblageAbbau(int geloescht, int loeschbar, List<GesperrtesObjekt> gesperrt) {

    public MandantAblageAbbau {
        if (geloescht < 0 || loeschbar < geloescht) {
            throw new IllegalArgumentException("ungültige Anzahl löschbarer Objekte");
        }
        gesperrt = List.copyOf(gesperrt);
    }

    public MandantAblageAbbau(int geloescht, List<GesperrtesObjekt> gesperrt) {
        this(geloescht, geloescht, gesperrt);
    }

    public record GesperrtesObjekt(String schluessel, String versionId, Optional<Instant> gesperrtBis,
                                  boolean legalHold) {
        public GesperrtesObjekt {
            Objects.requireNonNull(schluessel, "schluessel");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(gesperrtBis, "gesperrtBis");
            if (gesperrtBis.isEmpty() && !legalHold) {
                throw new IllegalArgumentException("gesperrtes Objekt braucht eine aktive Sperre");
            }
        }

        public GesperrtesObjekt(String schluessel, String versionId, Instant gesperrtBis) {
            this(schluessel, versionId, Optional.of(gesperrtBis), false);
        }
    }
}
