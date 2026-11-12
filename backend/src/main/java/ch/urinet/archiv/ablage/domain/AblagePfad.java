package ch.urinet.archiv.ablage.domain;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein gespeicherter Pfad einer Dokumentdatei mit Präfix, z. B. {@code archiv/2026/<docId>/pdfa.pdf} (design.md
 * «Ablage»), zerlegt in {@link Ablageort} und {@link DateiSchluessel}. Andere Pfade (Manifest, Vorschau, fremde
 * Präfixe) gelten nicht als Dokumentdatei.
 */
public record AblagePfad(Ablageort ort, DateiSchluessel schluessel) {

    private static final Pattern PFAD = Pattern.compile("^(eingang|archiv)/(\\d{4})/"
            + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/"
            + "(?:(\\d{1,4})-original\\.([a-z0-9]{1,10})|(\\d{1,4})-arbeitsfassung\\.jpg|pdfa\\.pdf)$");

    public AblagePfad {
        Objects.requireNonNull(ort, "ort");
        Objects.requireNonNull(schluessel, "schluessel");
    }

    /** @throws IllegalArgumentException wenn {@code pfad} keine Dokumentdatei unter {@code eingang/} oder {@code archiv/} ist */
    public static AblagePfad lesen(String pfad) {
        Matcher m = PFAD.matcher(Objects.requireNonNull(pfad, "pfad"));
        if (!m.matches()) {
            throw new IllegalArgumentException("Kein Pfad einer Dokumentdatei");
        }
        Ablageort ort = Arrays.stream(Ablageort.values()).filter(o -> o.praefix().equals(m.group(1))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Ablageort"));
        int jahr = Integer.parseInt(m.group(2));
        UUID docId = UUID.fromString(m.group(3));
        DateiSchluessel schluessel;
        if (m.group(4) != null) {
            schluessel = DateiSchluessel.original(jahr, docId, Integer.parseInt(m.group(4)), m.group(5));
        } else if (m.group(6) != null) {
            schluessel = DateiSchluessel.arbeitsfassung(jahr, docId, Integer.parseInt(m.group(6)));
        } else {
            schluessel = DateiSchluessel.pdfa(jahr, docId);
        }
        return new AblagePfad(ort, schluessel);
    }
}
