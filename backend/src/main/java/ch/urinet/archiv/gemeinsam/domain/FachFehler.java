package ch.urinet.archiv.gemeinsam.domain;

import java.util.Objects;

/**
 * Fachlicher Fehler mit {@link Fehlercode}; die REST-Schicht übersetzt ihn in Problem Details (design.md «Error
 * Handling»). Die Meldung ist der Fehlercode oder ein fester Text ohne Werte: Sie geht nie mit Eingabewerten oder
 * Inhalten in Antwort oder Log (Req 16.7). Ohne Stacktrace, weil ihn niemand ausgibt.
 */
public class FachFehler extends RuntimeException {

    private final Fehlercode code;

    public FachFehler(Fehlercode code) {
        this(code, Objects.requireNonNull(code, "code").name());
    }

    /** {@code meldung} nur als fester Text ohne Eingabewerte, Inhalte oder Personendaten. */
    protected FachFehler(Fehlercode code, String meldung) {
        super(meldung, null, false, false);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Fehlercode code() {
        return code;
    }
}
