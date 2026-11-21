package ch.urinet.archiv.benutzer.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class VerwaltungTransaktionen {

    private final TransactionSynchronizationRegistry transaktionen;

    public VerwaltungTransaktionen(TransactionSynchronizationRegistry transaktionen) {
        this.transaktionen = transaktionen;
    }

    public boolean aktiv() {
        return transaktionen.getTransactionKey() != null;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void neu(Runnable arbeit) {
        arbeit.run();
    }
}
