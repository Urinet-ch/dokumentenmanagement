package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.dokument.application.DokumentArchivierungPort;
import ch.urinet.archiv.dokument.application.DokumentArchivierungPort.Archiviert;
import ch.urinet.archiv.dokument.application.DokumentArchivierungPort.Archivierungsstand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kurze Transaktionen des {@link ArchivierenHandler} (design.md «Pflichten der Handler», (2)): Jede schreibende Methode
 * sichert zuerst den Anspruch auf den Auftrag; ohne Anspruch schreibt sie nichts. Eigene Klasse, weil
 * {@code @Transactional} nur über den Proxy wirkt.
 */
@ApplicationScoped
public class ArchivierungSpeichern {

    private final DokumentArchivierungPort dokumente;
    private final AuftragService auftraege;

    public ArchivierungSpeichern(DokumentArchivierungPort dokumente, AuftragService auftraege) {
        this.dokumente = dokumente;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Archivierungsstand> laden(UUID id) {
        return dokumente.laden(id);
    }

    /**
     * Hält die Archivierung fest und reiht in derselben Transaktion {@code EMBEDDING} ein (Spec 02, Req 2.1), sobald es
     * dafür einen Handler gibt.
     *
     * @return {@code false}, wenn das Dokument nicht mehr in {@code ARCHIVIERUNG} ist (nichts geschrieben)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean festhalten(Auftrag auftrag, UUID id, List<Archiviert> dateien, LocalDate sperreBis) {
        auftraege.anspruchSichern(auftrag);
        if (!dokumente.festhalten(id, dateien, sperreBis)) {
            return false;
        }
        if (auftraege.hatHandler(EmbeddingSpeichern.EMBEDDING)) {
            auftraege.einreihen(EmbeddingSpeichern.EMBEDDING, EmbeddingSpeichern.einzeln(id));
        }
        return true;
    }

    /** Abbruch nach Req 6.4 während des Laufs: mit Anspruch. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehlerSetzen(Auftrag auftrag, UUID id, String fehlertext) {
        auftraege.anspruchSichern(auftrag);
        return dokumente.fehlerSetzen(id, fehlertext);
    }

    /** Für den Hook nach dem letzten Fehlversuch: der Auftrag ist schon {@code FEHLER}, darum ohne Anspruch. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehlerSetzen(UUID id, String fehlertext) {
        return dokumente.fehlerSetzen(id, fehlertext);
    }
}
