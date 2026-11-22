package ch.urinet.archiv.sicherheit.application;

import java.time.Instant;

public interface TokenWiderruf {
    boolean widerrufen(String benutzer, Instant ausgestellt);

    void setzen(String benutzer, Instant ab);

    int aufraeumen(Instant vor);
}
