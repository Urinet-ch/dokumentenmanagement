package ch.urinet.archiv.ablage.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Startprüfung ab Stufe B (Req 6.11, entschieden von Fabian am 09.11.2026): {@code manifest/} fest COMPLIANCE mit der
 * Frist nach Req 15.4 (keine Höchstfrist), keine Höchstfrist für {@code archiv/} und nicht der Bucket-Präfix der Stufe
 * A. Stufe A, Tests und lokale Entwicklung dürfen abweichen.
 */
public final class Stufenregel {

    public static final String PRAEFIX_STUFE_A = "urinet-archiv-a-";

    private Stufenregel() {
    }

    public record Ablageeinstellungen(Sperrmodus manifestSperrmodus, Optional<Duration> manifestHoechstfrist,
                                      Optional<Duration> archivHoechstfrist, String bucketPraefix) {
    }

    /** Verstösse als Schlüssel der Konfiguration; leer, wenn die Einstellungen zur Stufe passen. */
    public static List<String> verstoesse(Stufe stufe, Ablageeinstellungen einstellungen) {
        List<String> verstoesse = new ArrayList<>();
        if (stufe == Stufe.A) {
            return verstoesse;
        }
        if (einstellungen.manifestSperrmodus() != Sperrmodus.COMPLIANCE) {
            verstoesse.add("archiv.ablage.manifest-sperrmodus");
        }
        if (einstellungen.manifestHoechstfrist().isPresent()) {
            verstoesse.add("archiv.ablage.manifest-max-sperrfrist");
        }
        if (einstellungen.archivHoechstfrist().isPresent()) {
            verstoesse.add("archiv.ablage.max-sperrfrist");
        }
        if (PRAEFIX_STUFE_A.equals(einstellungen.bucketPraefix())) {
            verstoesse.add("archiv.ablage.bucket-praefix");
        }
        return verstoesse;
    }

    /** Bricht ab, wenn die Einstellungen nicht zur Stufe passen; die Meldung nennt nur Schlüssel, keine Werte. */
    public static void pruefe(Stufe stufe, Ablageeinstellungen einstellungen) {
        List<String> verstoesse = verstoesse(stufe, einstellungen);
        if (!verstoesse.isEmpty()) {
            throw new IllegalStateException("Ablage ab Stufe B: nicht zulässig sind " + String.join(", ", verstoesse)
                    + ". Manifeste nur COMPLIANCE ohne Höchstfrist, keine Höchstfrist und nicht der Präfix "
                    + PRAEFIX_STUFE_A + " (Req 6.11). Stufe A setzt archiv.stufe=A.");
        }
    }
}
