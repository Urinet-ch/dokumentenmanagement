package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.paket.PaketKopf;
import ch.urinet.archiv.wissen.domain.paket.PaketSeite;

import java.util.Optional;
import java.util.UUID;

public interface WissenImportRepository {
    void sperren(long verbleibendeMillis);
    Optional<UUID> vorhandenerImport(String sha256);
    long offeneImporte();
    void anlegen(UUID id, String benutzer, String sha256, PaketKopf kopf, int unveraendert, int ignoriert);
    Optional<PaketKopf> kopfLaden(UUID id);
    PaketSeite seiteLaden(UUID id, int nummer);
    void paketSeiteSpeichern(UUID id, int nummer, PaketSeite seite, boolean veraltet);
    void pruefungSpeichern(WissenIngestPort.NeueAenderung aenderung);
}
