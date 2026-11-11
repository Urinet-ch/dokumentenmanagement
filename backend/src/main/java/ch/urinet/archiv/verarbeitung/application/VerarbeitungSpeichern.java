package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Verarbeitungsergebnis;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Verarbeitungsstand;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kurze Transaktionen des {@link VerarbeitenHandler} (design.md «Pflichten der Handler», (2)): Jede schreibende Methode
 * sichert zuerst den Anspruch auf den Auftrag und hält dessen Zeilensperre bis zum Commit; ohne Anspruch schreibt sie
 * nichts ({@link AuftragService.AnspruchVerloren}). Eigene Klasse, weil {@code @Transactional} nur über den Proxy wirkt.
 */
@ApplicationScoped
public class VerarbeitungSpeichern {

    public static final AuftragTyp KI_VORSCHLAG = new AuftragTyp("KI_VORSCHLAG");

    private final DokumentVerarbeitungPort dokumente;
    private final AuftragService auftraege;
    private final AblagePort ablage;

    public VerarbeitungSpeichern(DokumentVerarbeitungPort dokumente, AuftragService auftraege, AblagePort ablage) {
        this.dokumente = dokumente;
        this.auftraege = auftraege;
        this.ablage = ablage;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Verarbeitungsstand> laden(UUID id) {
        return dokumente.laden(id);
    }

    /**
     * Setzt {@code VERARBEITUNG} und löscht danach abgeleitete Dateien früherer, abgebrochener Läufe (PDF/A und
     * Vorschau, nie Originale oder Arbeitsfassungen). Das Löschen läuft unter der Sperre des Anspruchs: So kann kein
     * späterer Lauf mit gültigem Anspruch in der Zwischenzeit Ergebnisse gespeichert haben, die hier verloren gingen.
     *
     * @return {@code false}, wenn das Dokument nicht mehr zu verarbeiten ist (nichts geändert)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean beginnen(Auftrag auftrag, UUID id, int jahr) {
        auftraege.anspruchSichern(auftrag);
        if (!dokumente.verarbeitungBeginnen(id)) {
            return false;
        }
        ablage.abgeleiteteBereinigen(auftrag.mandant(), jahr, id, List.of());
        return true;
    }

    /**
     * Speichert das Ergebnis und reiht {@code KI_VORSCHLAG} in derselben Transaktion ein (kein doppelter Folgeauftrag).
     * Status: {@code EINGANGSKORB} bei {@code pdfa_fehlt} (Req 5.9, die KI läuft trotzdem) und, solange es keinen
     * Handler für {@code KI_VORSCHLAG} gibt (bis Aufgabe 3.2), ohne Vorschlag; sonst bleibt {@code VERARBEITUNG}, bis die
     * KI fertig ist.
     *
     * @return {@code false}, wenn das Dokument nicht mehr in {@code VERARBEITUNG} ist (nichts geschrieben)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean ergebnisSpeichern(Auftrag auftrag, UUID id, Verarbeitungsergebnis ergebnis) {
        auftraege.anspruchSichern(auftrag);
        boolean ki = auftraege.hatHandler(KI_VORSCHLAG);
        DokumentStatus status = ergebnis.pdfaFehlt() || !ki ? DokumentStatus.EINGANGSKORB : DokumentStatus.VERARBEITUNG;
        if (!dokumente.ergebnisSpeichern(id, ergebnis, status)) {
            return false;
        }
        if (ki) {
            auftraege.einreihen(KI_VORSCHLAG, new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id)));
        }
        return true;
    }

    /** Für den Hook nach dem letzten Fehlversuch: der Auftrag ist schon {@code FEHLER}, darum ohne Anspruch. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehlerSetzen(UUID id, String fehlertext) {
        return dokumente.fehlerSetzen(id, fehlertext);
    }
}
