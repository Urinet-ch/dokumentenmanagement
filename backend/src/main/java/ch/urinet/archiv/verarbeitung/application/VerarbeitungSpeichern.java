package ch.urinet.archiv.verarbeitung.application;

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
import java.util.OptionalInt;
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

    public VerarbeitungSpeichern(DokumentVerarbeitungPort dokumente, AuftragService auftraege) {
        this.dokumente = dokumente;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Verarbeitungsstand> laden(UUID id) {
        return dokumente.laden(id);
    }

    /**
     * Sichert den Anspruch und setzt {@code VERARBEITUNG}. Die Transaktion enthält keinen Aufruf an den Object Storage:
     * Abgeleitete Dateien früherer, abgebrochener Läufe löscht der Handler erst nach dem Commit, ohne Sperre, und prüft
     * vor dem Löschen den Anspruch mit {@link #anspruchPruefen} (Review PR #54, S2).
     *
     * @return {@code false}, wenn das Dokument nicht mehr zu verarbeiten ist (nichts geändert)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean beginnen(Auftrag auftrag, UUID id) {
        auftraege.anspruchSichern(auftrag);
        return dokumente.verarbeitungBeginnen(id);
    }

    /**
     * Prüft den Anspruch in einer eigenen, kurzen Transaktion; wirft {@link AuftragService.AnspruchVerloren}, wenn er
     * nicht mehr hält. Hält er, kann seit dem Auflisten der abgeleiteten Dateien kein späterer Lauf welche abgelegt
     * haben, weil ein späterer Lauf erst nach dem Verlust dieses Anspruchs beginnt.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void anspruchPruefen(Auftrag auftrag) {
        auftraege.anspruchSichern(auftrag);
    }

    /**
     * Speichert das Ergebnis und reiht {@code KI_VORSCHLAG} in derselben Transaktion ein (kein doppelter Folgeauftrag).
     * Status: {@code EINGANGSKORB} bei {@code pdfa_fehlt} (Req 5.9, die KI läuft trotzdem) und ohne Handler für
     * {@code KI_VORSCHLAG} (nur in Tests) ohne Vorschlag; sonst bleibt {@code VERARBEITUNG}, bis der
     * {@link KiVorschlagHandler} fertig ist (Aufgabe 3.2). Die Nutzlast von {@code KI_VORSCHLAG} trägt die
     * {@code version} nach dem Speichern ({@link VerarbeitenHandler#DOKUMENT_VERSION}).
     * <p>
     * {@code ohneKi} (Generator, Req 11.2): die Metadaten sind schon bestätigt, darum {@code ARCHIVIERUNG} und Auftrag
     * {@code ARCHIVIEREN} statt {@code KI_VORSCHLAG}; bei {@code pdfa_fehlt} {@code EINGANGSKORB} ohne Folgeauftrag, der
     * Inhaber bestätigt dort mit {@code ohnePdfa} (Req 5.9).
     *
     * @return {@code false}, wenn das Dokument nicht mehr in {@code VERARBEITUNG} ist (nichts geschrieben)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean ergebnisSpeichern(Auftrag auftrag, UUID id, Verarbeitungsergebnis ergebnis, boolean ohneKi) {
        auftraege.anspruchSichern(auftrag);
        if (ohneKi) {
            DokumentStatus status = ergebnis.pdfaFehlt() ? DokumentStatus.EINGANGSKORB : DokumentStatus.ARCHIVIERUNG;
            if (dokumente.ergebnisSpeichern(id, ergebnis, status).isEmpty()) {
                return false;
            }
            if (status == DokumentStatus.ARCHIVIERUNG) {
                auftraege.einreihen(ArchivierenHandler.TYP,
                        new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id)));
            }
            return true;
        }
        boolean ki = auftraege.hatHandler(KI_VORSCHLAG);
        DokumentStatus status = ergebnis.pdfaFehlt() || !ki ? DokumentStatus.EINGANGSKORB : DokumentStatus.VERARBEITUNG;
        OptionalInt version = dokumente.ergebnisSpeichern(id, ergebnis, status);
        if (version.isEmpty()) {
            return false;
        }
        if (ki) {
            auftraege.einreihen(KI_VORSCHLAG, new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id,
                    VerarbeitenHandler.DOKUMENT_VERSION, version.getAsInt())));
        }
        return true;
    }

    /** Für den Hook nach dem letzten Fehlversuch: der Auftrag ist schon {@code FEHLER}, darum ohne Anspruch. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fehlerSetzen(UUID id, String fehlertext) {
        return dokumente.fehlerSetzen(id, fehlertext);
    }
}
