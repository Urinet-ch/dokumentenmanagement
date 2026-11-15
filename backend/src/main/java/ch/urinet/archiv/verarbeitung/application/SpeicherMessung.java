package ch.urinet.archiv.verarbeitung.application;

/**
 * Stand eines Speicherbereichs der JVM (Req 16.5): {@code benutzt} und {@code maximum} in Bytes. Ein Maximum unter 1
 * heisst unbegrenzt (z. B. Metaspace ohne {@code -XX:MaxMetaspaceSize}); dafür gibt es keine Schwelle.
 */
public record SpeicherMessung(String pool, long benutzt, long maximum) {

    public boolean begrenzt() {
        return maximum > 0;
    }

    public double prozent() {
        return begrenzt() ? 100.0 * benutzt / maximum : 0;
    }
}
