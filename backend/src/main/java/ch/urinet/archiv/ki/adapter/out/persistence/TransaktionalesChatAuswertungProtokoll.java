package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.ChatAuswertungProtokoll;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

/** Schreibt den Exportvermerk unabhängig von der Lesetransaktion des ZIP-Streams. */
@ApplicationScoped
public class TransaktionalesChatAuswertungProtokoll implements ChatAuswertungProtokoll {

    private final ZugriffProtokoll protokoll;

    public TransaktionalesChatAuswertungProtokoll(ZugriffProtokoll protokoll) {
        this.protokoll = protokoll;
    }

    @Override
    @Transactional(TxType.REQUIRES_NEW)
    public void schreiben(ZugriffEintrag eintrag) {
        protokoll.schreiben(eintrag);
    }
}
