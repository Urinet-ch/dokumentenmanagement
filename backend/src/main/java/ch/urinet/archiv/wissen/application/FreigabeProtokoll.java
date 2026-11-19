package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

import java.util.UUID;

public interface FreigabeProtokoll {
    void angenommen(Benutzer benutzer, UUID entwurfId, int nummer, UUID seiteId, Integer basisVersion, int neueVersion,
                    String realIp);

    void verworfen(Benutzer benutzer, UUID entwurfId, int nummer, UUID seiteId, Integer basisVersion, String grund,
                   String realIp);
}
