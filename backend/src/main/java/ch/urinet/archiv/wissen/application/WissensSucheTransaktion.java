package ch.urinet.archiv.wissen.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

@ApplicationScoped
public class WissensSucheTransaktion {
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> T ausfuehren(Supplier<T> abfrage) { return abfrage.get(); }
}
