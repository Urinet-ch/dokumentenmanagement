package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Zusatzfelder eines Dokuments speichern (Req 9.5, 10.1, 10.4). Die Werte prüft das Schema der Art; ohne Art gibt es
 * kein Schema, darum sind dann keine Zusatzwerte erlaubt (422 {@code ZUSATZ_UNGUELTIG}). Bei {@code ARCHIVIERT} ist ein
 * Grund Pflicht und die vorherige Fassung geht nach {@code dokument_version}. Recht A6 und Sichtbarkeit prüft die
 * REST-Schicht vorher; der vollständige {@code PATCH /dokumente/{id}} folgt mit Aufgabe 3.5.
 */
@ApplicationScoped
@Transactional
public class DokumentZusatzSpeichern {

    private final DokumentRepository repository;
    private final DokumentartVerwaltung arten;

    public DokumentZusatzSpeichern(DokumentRepository repository, DokumentartVerwaltung arten) {
        this.repository = repository;
        this.arten = arten;
    }

    /** @return die neue Version des Dokuments (für das ETag) */
    public int speichern(Benutzer benutzer, UUID id, int erwarteteVersion, String zusatzJson, String grund) {
        Dokument dokument = repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (dokument.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (zusatzJson != null) {
            if (dokument.art() == null) {
                throw new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
            }
            arten.zusatzPruefen(dokument.art(), zusatzJson);
        }
        boolean archiviert = dokument.status() == DokumentStatus.ARCHIVIERT;
        if (archiviert && (grund == null || grund.isBlank())) {
            throw new FachFehler(Fehlercode.PFLICHTFELD_FEHLT);
        }
        return repository.zusatzSpeichern(id, erwarteteVersion, zusatzJson, benutzer.id(), grund, archiviert);
    }
}
