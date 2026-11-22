package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;

/** Protokolliert den Export der Chat-Auswertung in einer eigenen Transaktion. */
public interface ChatAuswertungProtokoll {

    void schreiben(ZugriffEintrag eintrag);
}
