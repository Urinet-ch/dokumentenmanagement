package ch.urinet.archiv.integration.domain.webseite;

import java.time.Instant;

/**
 * Token-Bucket (Spec 03, Req 15.1): fasst höchstens {@code spitze} Marken und füllt mit {@code proSekunde} Marken je
 * Sekunde auf; jede Anfrage nimmt eine Marke. Rechnet nur mit der übergebenen Zeit (Uhr-Attrappe im Test) und ist nicht
 * thread-sicher: Der Aufrufer ({@code WebseitenGrenzen}) sperrt.
 */
public final class TokenBucket {

    private final double spitze;
    private final double proSekunde;
    private double marken;
    private Instant stand;

    public TokenBucket(double spitze, double proSekunde, Instant jetzt) {
        if (!(spitze >= 1) || !(proSekunde > 0)) {
            throw new IllegalArgumentException("Spitze mindestens 1 und Auffüllrate über 0 nötig");
        }
        this.spitze = spitze;
        this.proSekunde = proSekunde;
        this.marken = spitze;
        this.stand = jetzt;
    }

    /** Füllt bis {@code jetzt} auf; eine rückwärts laufende Uhr füllt nichts auf. */
    private void auffuellen(Instant jetzt) {
        if (jetzt.isAfter(stand)) {
            double sekunden = (jetzt.toEpochMilli() - stand.toEpochMilli()) / 1000.0;
            marken = Math.min(spitze, marken + sekunden * proSekunde);
            stand = jetzt;
        }
    }

    /** Sekunden (aufgerundet, mindestens 1) bis eine Marke da ist; 0, wenn schon eine da ist. */
    public long wartezeitSekunden(Instant jetzt) {
        auffuellen(jetzt);
        if (marken >= 1) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil((1 - marken) / proSekunde - 1e-9));
    }

    /** Nimmt eine Marke; nur nach {@link #wartezeitSekunden} mit Ergebnis 0 aufrufen. */
    public void nehmen() {
        marken -= 1;
    }

    /** Wahr, wenn der Bucket voll ist (ein Eintrag im Leerlauf lässt sich ohne Verlust verwerfen). */
    public boolean voll(Instant jetzt) {
        auffuellen(jetzt);
        return marken >= spitze;
    }
}
