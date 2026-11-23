package ch.urinet.archiv.konfiguration.application;

import java.time.Instant;

public interface EinstellungsAuswirkungen {
    long chatNachrichtenVor(Instant grenze);

    long oeffentlicheFragenVor(Instant grenze);
}
