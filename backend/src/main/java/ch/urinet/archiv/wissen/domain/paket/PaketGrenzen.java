package ch.urinet.archiv.wissen.domain.paket;

import java.time.Duration;

public record PaketGrenzen(int zipBytes, int entpacktBytes, int dateiBytes, int frontmatterBytes,
                           int seiten, int eintraege, int pfadEbenen, int verhaeltnis, int yamlTiefe,
                           Duration zeit) {
    public PaketGrenzen {
        if (zipBytes < 1 || entpacktBytes < 1 || dateiBytes < 1 || frontmatterBytes < 1 || seiten < 0
                || eintraege < 1 || pfadEbenen < 1 || verhaeltnis < 1 || yamlTiefe < 1
                || zeit == null || zeit.isZero() || zeit.isNegative()
                || zipBytes > 10 * 1024 * 1024 || entpacktBytes > 20 * 1024 * 1024
                || dateiBytes > 128 * 1024 || frontmatterBytes > 16 * 1024 || seiten > 200
                || eintraege > 205 || pfadEbenen > 4 || verhaeltnis > 100 || yamlTiefe > 5
                || zeit.compareTo(Duration.ofSeconds(60)) > 0) throw new IllegalArgumentException("Grenzen");
    }
    public static PaketGrenzen standard() {
        return new PaketGrenzen(10 * 1024 * 1024, 20 * 1024 * 1024, 128 * 1024, 16 * 1024,
                200, 205, 4, 100, 5, Duration.ofSeconds(60));
    }
}
