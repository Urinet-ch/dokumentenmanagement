package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Stand;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Verwerfen (A9, Req 6.6, 6.7, 8.5; design.md «Ablage»): nur aus {@code EINGANGSKORB} und {@code FEHLER}, sonst 409
 * ohne Löschen. Setzt {@code VERWORFEN} mit Grund, markiert die Dateien als verworfen, gibt eine reservierte
 * Belegnummer frei (Req 11.11), protokolliert je Datei SHA-256 und Grund und löscht zuletzt alle Dateien unter
 * {@code eingang/} und die Vorschaubilder. Scheitert das Löschen, rollt alles zurück; ein erneuter Aufruf löscht, was
 * noch da ist. Unter {@code archiv/} liegt in diesen Status nie etwas.
 */
@ApplicationScoped
@Transactional
public class DokumentVerwerfen {

    static final int GRUND_MAX = 1000;
    private static final Set<DokumentStatus> ERLAUBT = Set.of(DokumentStatus.EINGANGSKORB, DokumentStatus.FEHLER);
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final DokumentRepository repository;
    private final DokumentStatusPort status;
    private final VerwerfenProtokollPort protokoll;
    private final AblagePort ablage;
    private final MandantAufloesung aufloesung;

    public DokumentVerwerfen(DokumentRepository repository, DokumentStatusPort status,
                             VerwerfenProtokollPort protokoll, AblagePort ablage, MandantAufloesung aufloesung) {
        this.repository = repository;
        this.status = status;
        this.protokoll = protokoll;
        this.ablage = ablage;
        this.aufloesung = aufloesung;
    }

    public void verwerfen(Benutzer benutzer, UUID id, int erwarteteVersion, String grund) {
        Dokument dokument = repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (dokument.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (!ERLAUBT.contains(dokument.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        String text = grund == null ? null : grund.strip();
        if (text == null || text.isEmpty()) {
            throw new PflichtfelderFehlen(List.of("grund"));
        }
        if (text.length() > GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        MandantCode mandant = aufloesung.pruefen();
        Stand stand = status.stand(id, true).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (stand.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (!ERLAUBT.contains(stand.status()) || !status.verwerfen(id, erwarteteVersion, text)) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        for (String sha256 : stand.sha256()) {
            protokoll.dateiVerworfen(id, sha256, text);
        }
        int jahr = stand.eingang().atZone(ZUERICH).getYear();
        ablage.abgeleiteteBereinigen(mandant, jahr, id, List.of());
        ablage.loeschenEingang(mandant, jahr, id);
    }
}
