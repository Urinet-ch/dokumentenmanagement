package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Optional;

/**
 * Driven Port: Zustand «Wissen freigeschaltet» je Mandant in {@code public.mandant} (Spec 04, Req 22.1). Gelesen wird nur
 * über {@link Wissensfreischaltung}, geändert nur über {@link WissenFreischalten} (Betreiber-Kommando, Req 22.3).
 */
public interface WissensfreischaltungPort {

    /** Zustand des eingetragenen Mandanten; leer, wenn er nicht im Register steht. */
    Optional<Boolean> lesen(MandantCode mandant);

    /** Wie {@link #lesen}, sperrt die Zeile aber bis zum Ende der laufenden Transaktion ({@code for update}). */
    Optional<Boolean> sperrenUndLesen(MandantCode mandant);

    /** Setzt den Zustand in der laufenden Transaktion. */
    void setzen(MandantCode mandant, boolean freigeschaltet);
}
