package ch.urinet.archiv.gemeinsam.domain;

/**
 * 429 {@link Fehlercode#ZU_VIELE_ANFRAGEN} mit {@code Retry-After} in Sekunden (design.md «Error Handling»); die
 * REST-Schicht setzt den Header aus {@link #wartezeitSekunden()}.
 */
public class ZuVieleAnfragen extends FachFehler {

    private final long wartezeitSekunden;

    public ZuVieleAnfragen(long wartezeitSekunden) {
        super(Fehlercode.ZU_VIELE_ANFRAGEN, Fehlercode.ZU_VIELE_ANFRAGEN.name());
        if (wartezeitSekunden < 1) {
            throw new IllegalArgumentException("Wartezeit unter 1 Sekunde");
        }
        this.wartezeitSekunden = wartezeitSekunden;
    }

    public long wartezeitSekunden() {
        return wartezeitSekunden;
    }
}
