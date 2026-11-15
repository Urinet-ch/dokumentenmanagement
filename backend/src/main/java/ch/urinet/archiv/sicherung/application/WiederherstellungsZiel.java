package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Ablage-Port für eine Wiederherstellung; eine Version wird erst nach ihrer Prüfsummenprüfung gesperrt. */
public interface WiederherstellungsZiel {

    /** Schreibt eine neue, noch ungesperrte Version und berechnet die Prüfsumme der geschriebenen Bytes. */
    Quittung schreiben(String schluessel, String versionId, Instant lastModified, InputStream inhalt, long groesse);

    /** Setzt die Sperre auf die mit {@code schreiben} zurückgegebene neue Version. */
    void sperren(Quittung quittung, Sperrmodus modus, Instant bis);

    /** Entfernt genau die neu geschriebene Version, solange sie noch ungesperrt ist. */
    void verwerfen(Quittung quittung);

    String praefix();

    boolean leer(MandantCode mandant);

    record Quittung(String versionId, String sha256) {
        private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

        public Quittung {
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(sha256, "sha256");
            if (versionId.isBlank() || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Ungültige Quittung");
            }
        }
    }
}
