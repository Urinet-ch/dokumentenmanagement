package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

public interface WissenPaketImport {
    int maxZipBytes();
    long zeitgrenzeNanos();
    WissenImport.Antwort importieren(Benutzer benutzer, String mandant, byte[] zip, long deadline, String realIp);
}
