package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Bekannt;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Pruefdokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Abweichung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Lauf;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Zaehler;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Kurze Transaktionen des {@link PrueflaufHandler} (design.md «Pflichten der Handler»): Das Fortschreiben sichert zuerst
 * den Anspruch auf den Auftrag und reiht den nächsten Teilauftrag in derselben Transaktion ein. Eigene Klasse, weil
 * {@code @Transactional} nur über den Proxy wirkt.
 */
@ApplicationScoped
public class PrueflaufSpeichern {

    private final PrueflaufRegister register;
    private final DokumentPrueflaufPort dokumente;
    private final AuftragService auftraege;

    public PrueflaufSpeichern(PrueflaufRegister register, DokumentPrueflaufPort dokumente, AuftragService auftraege) {
        this.register = register;
        this.dokumente = dokumente;
        this.auftraege = auftraege;
    }

    /** Legt den Lauf an und reiht Teil 1 ein; leer, wenn für den Monat schon ein Lauf besteht. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<UUID> anlegen(Optional<String> monat, Stufe stufe) {
        Optional<UUID> id = register.anlegen(monat, stufe);
        id.ifPresent(lauf -> auftraege.einreihen(PrueflaufHandler.TYP, nutzlast(lauf, 1)));
        return id;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Lauf> laden(UUID id) {
        return register.laden(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Pruefdokument> archivierte(Optional<UUID> nach, int anzahl) {
        return dokumente.archivierte(nach, anzahl);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Set<Bekannt> bekannte(Collection<String> schluessel) {
        return dokumente.bekannte(schluessel);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Map<UUID, DokumentStatus> status(Collection<UUID> ids) {
        return dokumente.status(ids);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Abweichung> abweichungen(UUID id, int anzahl) {
        return register.abweichungen(id, anzahl);
    }

    /**
     * Ergebnis von Teil {@code teil} festhalten und, solange der Lauf nicht abgeschlossen ist, Teil {@code teil + 1}
     * einreihen. {@code false}, wenn ein früherer Lauf dieses Teils schon geschrieben hat (nichts geschrieben).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean fortschreiben(Auftrag auftrag, UUID lauf, int teil, Fortsetzung weiter, Zaehler zuwachs,
                                 List<Abweichung> abweichungen) {
        auftraege.anspruchSichern(auftrag);
        if (!register.fortschreiben(lauf, teil, weiter, zuwachs, abweichungen)) {
            return false;
        }
        if (weiter.phase() != Phase.ABGESCHLOSSEN) {
            auftraege.einreihen(PrueflaufHandler.TYP, nutzlast(lauf, teil + 1));
        }
        return true;
    }

    static Nutzlast nutzlast(UUID lauf, int teil) {
        return new Nutzlast(Map.of(PrueflaufHandler.LAUF_ID, lauf, PrueflaufHandler.TEIL, teil));
    }
}
