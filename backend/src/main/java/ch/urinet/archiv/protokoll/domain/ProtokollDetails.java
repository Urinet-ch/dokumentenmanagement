package ch.urinet.archiv.protokoll.domain;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Inhalt der Spalte {@code details} (jsonb): nur Grund, SHA-256, Fehlercode, bei {@code REGEL_AENDERN} die alte und
 * neue Regel einer Dokumentart und bei {@code SPERRE_KONFLIKT} die bisherige und die neu berechnete Frist, nie
 * Dokumentinhalte (Req 9.7, 9.10, 10.2, 16.7). Jedes Feld darf fehlen ({@code null}).
 */
public record ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                               FristKonflikt fristKonflikt) {

    public static final ProtokollDetails KEINE = new ProtokollDetails(null, null, null);

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** Fehlercode, Code einer Dokumentart oder Wert eines Enums */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public ProtokollDetails {
        if (sha256 != null) {
            sha256 = sha256.toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 ist keine SHA-256-Prüfsumme in Hex");
            }
        }
        if (fehlercode != null && !CODE.matcher(fehlercode).matches()) {
            throw new IllegalArgumentException("fehlercode ist kein Fehlercode");
        }
        if (grund != null && grund.isBlank()) {
            grund = null;
        }
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode) {
        this(grund, sha256, fehlercode, null, null);
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung) {
        this(grund, sha256, fehlercode, regelAenderung, null);
    }

    public static ProtokollDetails fehlercode(String fehlercode) {
        return new ProtokollDetails(null, null, fehlercode);
    }

    public static ProtokollDetails regelAenderung(RegelAenderung aenderung) {
        return new ProtokollDetails(null, null, null, aenderung);
    }

    public static ProtokollDetails fristKonflikt(FristKonflikt konflikt) {
        return new ProtokollDetails(null, null, null, null, konflikt);
    }

    public boolean leer() {
        return grund == null && sha256 == null && fehlercode == null && regelAenderung == null
                && fristKonflikt == null;
    }

    /**
     * Aufbewahrungsregel einer Dokumentart: Jahre und «ab» (beide {@code null} heisst unbefristet) sowie die
     * gesetzliche Pflicht, die über die Sperre entscheidet. Nur Werte der Regel, keine Werte eines Dokuments.
     */
    public record Regel(Integer aufbewahrungJahre, String aufbewahrungAb, boolean gesetzlichePflicht) {

        public Regel {
            if (aufbewahrungAb != null && !CODE.matcher(aufbewahrungAb).matches()) {
                throw new IllegalArgumentException("aufbewahrungAb ist kein Code");
            }
        }
    }

    /** Alte und neue Regel der Dokumentart mit dem Code {@code dokumentart}. */
    public record RegelAenderung(String dokumentart, Regel alt, Regel neu) {

        public RegelAenderung {
            if (dokumentart == null || !CODE.matcher(dokumentart).matches()) {
                throw new IllegalArgumentException("dokumentart ist kein Code");
            }
            if (alt == null || neu == null) {
                throw new IllegalArgumentException("alte und neue Regel sind Pflicht");
            }
        }
    }

    /**
     * Konflikt bei der Neuberechnung der Aufbewahrungsfrist (Req 9.7): die bisherige Frist, die bleibt, und die neu
     * berechnete, die nicht übernommen wurde; {@code null} heisst unbefristet.
     */
    public record FristKonflikt(LocalDate bisher, LocalDate neu) {
    }
}
