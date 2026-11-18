package ch.urinet.archiv.konfiguration.application;

import java.util.List;
import java.util.UUID;

/** Abfrage bestätigter Dokumente für die deklarativen Regeln. */
public interface RegelDatenPort {

    boolean eindeutig(String art, boolean ueberAlleArten, List<String> felder, List<Object> werte,
                      UUID dokumentId);
}
