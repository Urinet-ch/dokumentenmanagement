package ch.urinet.archiv.dokument.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Zuordnungen eines Dokuments im Mandantenschema. */
public interface DokumentKlassifikationenPort {

    Map<String, List<String>> laden(UUID dokumentId);

    void ersetzen(UUID dokumentId, Map<String, List<String>> klassifikationen);
}
