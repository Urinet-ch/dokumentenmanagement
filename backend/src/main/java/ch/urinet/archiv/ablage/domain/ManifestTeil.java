package ch.urinet.archiv.ablage.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Dateien eines Tages-Manifests unter {@code manifest/<yyyy-mm-dd>.<endung>} (design.md «Ablage», «Tages-Manifest»):
 * {@code json} und {@code sig}, je Zeitstempeldienst {@code <dienst>.tsr}, dazu die Prüfdaten für die spätere Prüfung
 * ohne Netz: {@code <dienst>.kette.pem} (Zertifikatskette) und {@code <dienst>.ocsp.der} bzw. {@code <dienst>.crl.der}.
 */
public record ManifestTeil(String endung) {

    // Muster vor den Konstanten: sonst prüft der Konstruktor gegen ein noch leeres Feld
    private static final Pattern DIENST = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern ENDUNG = Pattern.compile("json|sig|[a-z0-9][a-z0-9-]{0,31}\\.(tsr|kette\\.pem|ocsp\\.der|crl\\.der)");

    public static final ManifestTeil JSON = new ManifestTeil("json");
    public static final ManifestTeil SIG = new ManifestTeil("sig");

    public ManifestTeil {
        Objects.requireNonNull(endung, "endung");
        if (!ENDUNG.matcher(endung).matches()) {
            throw new IllegalArgumentException("Ungültiger Manifest-Teil: " + endung);
        }
    }

    /** Zeitstempel-Antwort (RFC 3161) des Dienstes. */
    public static ManifestTeil tsr(String dienst) {
        return new ManifestTeil(dienst(dienst) + ".tsr");
    }

    /** Zertifikatskette des Zeitstempeldienstes (PEM, TSA-Zertifikat zuerst). */
    public static ManifestTeil kette(String dienst) {
        return new ManifestTeil(dienst(dienst) + ".kette.pem");
    }

    /** OCSP-Antwort zum TSA-Zertifikat (DER). */
    public static ManifestTeil ocsp(String dienst) {
        return new ManifestTeil(dienst(dienst) + ".ocsp.der");
    }

    /** Sperrliste der ausstellenden CA (DER). */
    public static ManifestTeil crl(String dienst) {
        return new ManifestTeil(dienst(dienst) + ".crl.der");
    }

    public String pfad(LocalDate datum) {
        return "manifest/" + Objects.requireNonNull(datum, "datum") + "." + endung;
    }

    private static String dienst(String dienst) {
        if (dienst == null || !DIENST.matcher(dienst).matches()) {
            throw new IllegalArgumentException("Ungültige Kennung des Zeitstempeldienstes: " + dienst);
        }
        return dienst;
    }
}
