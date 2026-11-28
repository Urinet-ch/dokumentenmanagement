package ch.urinet.archiv.importe.domain.quelle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ein Import aus einer Quelle mit Zählern (Spec 09, 10.3). */
public record QuellLauf(UUID id, UUID quelleId, String quelleCode, LaufArt art, QuellLaufStatus status,
                        Zaehler zaehler, List<String> meldungen, Optional<String> benutzer, Instant gestartet,
                        Optional<Instant> beendet) {

    public static final int MELDUNGEN_MAX = 100;
    public static final int MELDUNG_MAX = 500;

    public QuellLauf {
        meldungen = List.copyOf(meldungen);
    }

    /** Nimmt Uploads auf: läuft und ist kein Trockenlauf. */
    public boolean nimmtUploadsAuf() {
        return status == QuellLaufStatus.LAEUFT && art != LaufArt.TROCKEN;
    }

    public static boolean meldungenGueltig(List<String> meldungen) {
        return meldungen.size() <= MELDUNGEN_MAX
                && meldungen.stream().allMatch(m -> m != null && m.length() <= MELDUNG_MAX);
    }
}
