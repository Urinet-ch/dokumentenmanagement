package ch.urinet.archiv.wissen.adapter.out.protokoll;

import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.wissen.application.FreigabeProtokoll;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ZugriffFreigabeProtokoll implements FreigabeProtokoll {
    private final ZugriffProtokoll protokoll;

    public ZugriffFreigabeProtokoll(ZugriffProtokoll protokoll) {
        this.protokoll = protokoll;
    }

    @Override
    public void verworfen(Benutzer benutzer, UUID entwurfId, String grund, String realIp) {
        protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer.id()), Optional.of(benutzer.rolle().name()),
                ProtokollAktion.WISSEN_VERWERFEN, Optional.of(entwurfId), IpAdresse.aus(realIp),
                new ProtokollDetails(grund, null, null)));
    }
}
