package ch.urinet.archiv.wissen.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

/** Eigene Transaktion für Zustand und Protokolleintrag des Betreiber-Kommandos (Spec 04, Req 22.2). */
@ApplicationScoped
public class WissenFreischaltenTransaktion {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> T ausfuehren(Supplier<T> arbeit) {
        return arbeit.get();
    }
}
