package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dokument und Eingangskorb lesen (A2–A5, Req 2.3, 2.21, 2.24). Ein unsichtbares oder fremdes Dokument gilt als nicht
 * vorhanden (404, Req 1.4); die REST-Schicht prüft das Recht vorher, hier filtert die {@link Sichtregel} nochmals.
 */
@ApplicationScoped
@Transactional
public class DokumentLesen {

    static final int GROESSE_MAX = 100;

    private final DokumentRepository repository;
    private final DokumentStatusPort status;
    private final MandantAufloesung aufloesung;
    private final MandantEinstellungen einstellungen;

    public DokumentLesen(DokumentRepository repository, DokumentStatusPort status, MandantAufloesung aufloesung,
                         MandantEinstellungen einstellungen) {
        this.repository = repository;
        this.status = status;
        this.aufloesung = aufloesung;
        this.einstellungen = einstellungen;
    }

    public Dokument lesen(Benutzer benutzer, UUID id) {
        return repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    /** Eingangskorb, neueste zuerst; {@code seite} ab 0, {@code groesse} 1–{@value #GROESSE_MAX}, sonst 400. */
    public Seite<Dokument> eingangskorb(Benutzer benutzer, int seite, int groesse) {
        if (seite < 0 || groesse < 1 || groesse > GROESSE_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return repository.eingangskorb(Sichtregel.fuer(benutzer), seite, groesse);
    }

    /**
     * Warnungen eines Dokuments im Eingangskorb (Req 8.10, design.md «Statusmodell»): {@code BELEGNUMMER_BEKANNT}, wenn
     * ein anderes, nicht verworfenes Dokument gleiche Art, Belegnummer und Partner hat und die Art keine eigene Art
     * des Mandanten ist (dort verhindert Req 8.9 die Bestätigung). Ausserhalb des Eingangskorbs keine Warnungen.
     */
    public List<Warnung> warnungen(Benutzer benutzer, Dokument dokument) {
        if (!DokumentStatus.EINGANGSKORB_STATUS.contains(dokument.status()) || dokument.art() == null
                || einstellungen.lesen(aufloesung.pruefen()).eigeneArten().contains(dokument.art())) {
            return List.of();
        }
        Optional<UUID> vorhanden = status.gleicherBeleg(dokument.id());
        if (vorhanden.isEmpty()) {
            return List.of();
        }
        UUID sichtbar = repository.sichtbar(vorhanden.get(), Sichtregel.fuer(benutzer)) ? vorhanden.get() : null;
        return List.of(new Warnung(Warnung.BELEGNUMMER_BEKANNT, sichtbar));
    }
}
