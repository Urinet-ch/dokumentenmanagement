package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

import java.util.UUID;

public interface FreigabeProtokoll {
    void verworfen(Benutzer benutzer, UUID entwurfId, String grund, String realIp);
}
