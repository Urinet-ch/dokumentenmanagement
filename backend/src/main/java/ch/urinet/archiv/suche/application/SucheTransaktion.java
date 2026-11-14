package ch.urinet.archiv.suche.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

/**
 * Kurze eigene Transaktion je Datenbankteil der Hybridsuche (Spec 02, Aufgabe 6.4): Die Aufrufe der Modelle laufen
 * dazwischen ohne Transaktion, damit keine Verbindung auf Infomaniak wartet.
 */
@ApplicationScoped
public class SucheTransaktion {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> T ausfuehren(Supplier<T> arbeit) {
        return arbeit.get();
    }
}
