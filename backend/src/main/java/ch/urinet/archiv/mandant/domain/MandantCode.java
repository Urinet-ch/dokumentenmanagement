package ch.urinet.archiv.mandant.domain;

import java.util.regex.Pattern;

/** Code eines Mandanten (z. B. {@code urinet}, {@code vhf-gsk}); daraus folgen Schema und Bucket (Req 1.1). */
public record MandantCode(String wert) {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    // S3-Regeln für Bucket-Namen: 3 bis 63 Zeichen, Kleinbuchstaben, Ziffern, Bindestrich, nicht am Rand
    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");
    private static final int MAX_LAENGE = 40;
    // reserviert: Auf Stufe B ergäbe «a-<x>» den Bucket der Stufe A von Mandant <x> (Präfix urinet-archiv-a-, Req 6.11)
    private static final String RESERVIERT_STUFE_A = "a-";
    // ebenso «p-<x>» für den Bucket der Restore-Probe (Präfix urinet-archiv-p-, Req 16.24)
    private static final String RESERVIERT_STUFE_P = "p-";

    public MandantCode {
        if (wert == null || wert.length() > MAX_LAENGE || !CODE.matcher(wert).matches()
                || wert.startsWith(RESERVIERT_STUFE_A) || wert.startsWith(RESERVIERT_STUFE_P)) {
            throw new IllegalArgumentException("Ungültiger Mandant-Code: " + wert);
        }
    }

    /** Schema {@code m_<code>}, Bindestriche als Unterstrich ({@code vhf-gsk} → {@code m_vhf_gsk}). */
    public String schema() {
        return "m_" + wert.replace('-', '_');
    }

    /** Bucket {@code <präfix><code>} (Präfix aus {@code archiv.ablage.bucket-praefix}). */
    public String bucket(String praefix) {
        String name = praefix + wert;
        if (!BUCKET.matcher(name).matches() || name.contains("--")) {
            throw new IllegalArgumentException("Ungültiger Bucket-Name: " + name);
        }
        return name;
    }
}
