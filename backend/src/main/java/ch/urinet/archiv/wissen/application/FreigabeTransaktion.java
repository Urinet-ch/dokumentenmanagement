package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;
import java.util.function.IntSupplier;

@ApplicationScoped
public class FreigabeTransaktion {
    private final FreigabeAnnahme annahme;

    public FreigabeTransaktion(FreigabeAnnahme annahme) {
        this.annahme = annahme;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean annehmen(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch,
                            FreigabeAnnahme.Bearbeitung bearbeitung, String stufe, String grund, String realIp) {
        return annahme.ausfuehren(benutzer, entwurfId, nummer, ifMatch, bearbeitung, stufe, grund, realIp);
    }
}
