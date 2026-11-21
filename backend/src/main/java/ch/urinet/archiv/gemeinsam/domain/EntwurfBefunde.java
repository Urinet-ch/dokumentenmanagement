package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;

public class EntwurfBefunde extends FachFehler {
    private final List<String> befunde;

    public EntwurfBefunde(List<String> befunde) {
        super(Fehlercode.ENTWURF_BEFUND);
        this.befunde = List.copyOf(befunde);
    }

    public List<String> befunde() {
        return befunde;
    }
}
