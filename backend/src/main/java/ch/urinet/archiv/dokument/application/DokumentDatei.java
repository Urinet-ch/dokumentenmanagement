package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AblagePfad;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Datei eines Dokuments herunterladen (A2, Req 14.6): eine vorsignierte URL für genau die gespeicherte Versions-ID,
 * höchstens {@code archiv.ablage.url-gueltigkeit} gültig. Unsichtbare, fremde und verworfene Dokumente sowie Dateien
 * ohne Versions-ID ergeben 404 {@code NICHT_GEFUNDEN} (Req 1.4, 2.3). Das Protokoll ({@code HERUNTERLADEN}) schreibt
 * die REST-Schicht.
 */
@ApplicationScoped
@Transactional
public class DokumentDatei {

    private final DokumentRepository repository;
    private final AblagePort ablage;

    public DokumentDatei(DokumentRepository repository, AblagePort ablage) {
        this.repository = repository;
        this.ablage = ablage;
    }

    /** {@code nr} gilt für Original und Arbeitsfassung (ab 1); für PDF/A ist sie 0. */
    public VorsignierteUrl herunterladen(Benutzer benutzer, MandantCode mandant, UUID id, DateiRolle rolle, int nr) {
        DateiFundstelle fundstelle = repository.dateiFinden(id, Sichtregel.fuer(benutzer), rolle, nr)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        AblagePfad pfad = AblagePfad.lesen(fundstelle.pfad());
        if (!pfad.schluessel().docId().equals(id) || pfad.schluessel().rolle() != rolle) {
            throw new IllegalStateException("Gespeicherter Pfad passt nicht zum Dokument " + id);
        }
        return ablage.vorsignierteUrl(mandant, pfad.ort(), pfad.schluessel(), fundstelle.versionId());
    }
}
