package ch.urinet.archiv.wissen;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;

import java.time.Duration;

/** Nur Unit-Tests ohne Datenbank: eine Freischaltung mit festem Zustand für jeden Mandanten. */
public final class FesteWissensfreischaltung extends Wissensfreischaltung {

    private final boolean freigeschaltet;

    private FesteWissensfreischaltung(boolean freigeschaltet) {
        super(null, null, null, Duration.ZERO);
        this.freigeschaltet = freigeschaltet;
    }

    public static Wissensfreischaltung ein() {
        return new FesteWissensfreischaltung(true);
    }

    public static Wissensfreischaltung aus() {
        return new FesteWissensfreischaltung(false);
    }

    @Override
    public boolean freigeschaltet(MandantCode mandant) {
        return freigeschaltet;
    }

    @Override
    public boolean aktuell() {
        return freigeschaltet;
    }
}
