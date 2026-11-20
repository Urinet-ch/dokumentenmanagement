package ch.urinet.archiv.wissen.application;

import java.util.UUID;

public interface WissenExportProtokoll {
    void angefordert(UUID exportId);

    void abgeschlossen(UUID exportId, String benutzer, String rolle, String sha256);

    void heruntergeladen(UUID exportId, String sha256);
}
