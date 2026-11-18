package ch.urinet.archiv.konfiguration.application;

import java.util.List;
import java.util.UUID;

/** Abfrage bestätigter Dokumente für die deklarativen Regeln. */
public interface RegelDatenPort {

    /**
     * {@code true}, wenn ein anderes bestätigtes Dokument dieselben Werte trägt. Mit {@code sperren} serialisiert der
     * Aufruf gleiche Werte derselben Regel bis zum Commit (nur Schreibpfade; Lesepfade sperren nicht).
     */
    boolean dubletteVorhanden(String art, boolean ueberAlleArten, List<String> felder, List<Object> werte,
                              UUID dokumentId, boolean sperren);
}
