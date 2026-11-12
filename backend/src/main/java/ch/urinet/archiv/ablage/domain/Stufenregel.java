package ch.urinet.archiv.ablage.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Startprüfung ab Stufe B (Req 6.11, entschieden von Fabian am 09.11.2026): {@code manifest/} fest COMPLIANCE mit der
 * Frist nach Req 15.4 (keine Höchstfrist), keine Höchstfrist für {@code archiv/} und nicht der Bucket-Präfix der Stufe
 * A oder P. Stufe A, Tests und lokale Entwicklung dürfen abweichen. Stufe P (Restore-Probe, Req 16.24) verlangt den
 * Präfix {@value #PRAEFIX_STUFE_P}, beide Höchstfristen von höchstens 1 Tag und {@code manifest/} COMPLIANCE.
 */
public final class Stufenregel {

    public static final String PRAEFIX_STUFE_A = "urinet-archiv-a-";
    public static final String PRAEFIX_STUFE_P = "urinet-archiv-p-";
    public static final Duration HOECHSTFRIST_STUFE_A = Duration.ofDays(1);
    public static final Duration HOECHSTFRIST_STUFE_P = Duration.ofDays(1);

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
        if (stufe == Stufe.P) {
            return verstoesseStufeP(einstellungen);
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
        if (PRAEFIX_STUFE_A.equals(einstellungen.bucketPraefix())
                || PRAEFIX_STUFE_P.equals(einstellungen.bucketPraefix())) {
            verstoesse.add("archiv.ablage.bucket-praefix");
        }
        return verstoesse;
    }

    private static List<String> verstoesseStufeP(Ablageeinstellungen einstellungen) {
        List<String> verstoesse = new ArrayList<>();
        if (einstellungen.manifestSperrmodus() != Sperrmodus.COMPLIANCE) {
            verstoesse.add("archiv.ablage.manifest-sperrmodus");
        }
        if (einstellungen.manifestHoechstfrist().filter(f -> f.compareTo(HOECHSTFRIST_STUFE_P) <= 0).isEmpty()) {
            verstoesse.add("archiv.ablage.manifest-max-sperrfrist");
        }
        if (einstellungen.archivHoechstfrist().filter(f -> f.compareTo(HOECHSTFRIST_STUFE_P) <= 0).isEmpty()) {
            verstoesse.add("archiv.ablage.max-sperrfrist");
        }
        if (!PRAEFIX_STUFE_P.equals(einstellungen.bucketPraefix())) {
            verstoesse.add("archiv.ablage.bucket-praefix");
        }
        return verstoesse;
    }

    /**
     * Hinweise für Stufe A (Req 6.11, Review PR #43 K2): Höchstfrist für {@code archiv/} fehlt oder liegt über 1 Tag,
     * oder der Bucket-Präfix ist nicht der von Stufe A. Nur Warnung, weil dev und test mit {@code archiv.stufe=A} und
     * dem Präfix {@code urinet-archiv-} laufen; die Werte der Stufe A setzt Aufgabe 1.10.
     */
    public static List<String> warnungen(Stufe stufe, Ablageeinstellungen einstellungen) {
        List<String> warnungen = new ArrayList<>();
        if (stufe != Stufe.A) {
            return warnungen;
        }
        if (einstellungen.archivHoechstfrist().filter(f -> f.compareTo(HOECHSTFRIST_STUFE_A) <= 0).isEmpty()) {
            warnungen.add("archiv.ablage.max-sperrfrist");
        }
        if (!PRAEFIX_STUFE_A.equals(einstellungen.bucketPraefix())) {
            warnungen.add("archiv.ablage.bucket-praefix");
        }
        return warnungen;
    }

    /** Bricht ab, wenn die Einstellungen nicht zur Stufe passen; die Meldung nennt nur Schlüssel, keine Werte. */
    public static void pruefe(Stufe stufe, Ablageeinstellungen einstellungen) {
        List<String> verstoesse = verstoesse(stufe, einstellungen);
        if (!verstoesse.isEmpty() && stufe == Stufe.P) {
            throw new IllegalStateException("Ablage Stufe P: nicht zulässig sind " + String.join(", ", verstoesse)
                    + ". Manifeste nur COMPLIANCE, beide Höchstfristen höchstens " + HOECHSTFRIST_STUFE_P
                    + " und der Präfix " + PRAEFIX_STUFE_P + " (Req 16.24).");
        }
        if (!verstoesse.isEmpty()) {
            throw new IllegalStateException("Ablage ab Stufe B: nicht zulässig sind " + String.join(", ", verstoesse)
                    + ". Manifeste nur COMPLIANCE ohne Höchstfrist, keine Höchstfrist und nicht der Präfix "
                    + PRAEFIX_STUFE_A + " oder " + PRAEFIX_STUFE_P + " (Req 6.11). Stufe A setzt archiv.stufe=A.");
        }
    }
}
