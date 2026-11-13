package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.verarbeitung.application.AuftragStand;
import ch.urinet.archiv.verarbeitung.application.ManifestUeberwachung;
import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Überwachung der Tages-Manifeste (Req 15.8, 16.5; Aufgabe 5.6): höchstens ein Befund je Mandant, das erste Datum ohne
 * Manifest mit Status {@value #FEHLT}. Ein vorhandenes Manifest gilt als gültig, auch mit nur einem Zeitstempel
 * (Req 15.11); ob es gültig ist, prüfen Prüfwerkzeug und Prüflauf. Die Überwachung schickt je Datum genau eine Mail und
 * erledigt den Alarm, sobald das Manifest nachgeholt ist (Req 15.6), ohne weitere Mail.
 */
@ApplicationScoped
public class ManifestBefunde implements ManifestUeberwachung {

    static final String FEHLT = "FEHLT";
    private static final String FEHLER = "FEHLER";

    private final ManifestFortschritt fortschritt;
    private final AuftragStand auftraege;
    private final MandantRegister register;
    private final Duration frist;

    public ManifestBefunde(ManifestFortschritt fortschritt, AuftragStand auftraege, MandantRegister register,
                           @ConfigProperty(name = "archiv.ueberwachung.manifest-frist") Duration frist) {
        this.frist = Ueberwachung.pruefeManifestFrist(frist);
        this.fortschritt = fortschritt;
        this.auftraege = auftraege;
        this.register = register;
    }

    @Override
    public Optional<List<Befund>> befunde(MandantCode mandant) {
        Optional<ManifestFortschritt.Stand> stand = fortschritt.stand(mandant);
        LocalDate faellig = ManifestErzeugen.naechstes(stand.map(ManifestFortschritt.Stand::letztesDatum),
                () -> register.erstellt(mandant));
        return Optional.of(befund(faellig, stand.map(ManifestFortschritt.Stand::zuletztErstellt),
                auftraege.juengsterStatus(mandant, ManifestErzeugen.TYP), frist, Instant.now())
                .stream().toList());
    }

    /**
     * Regel ohne Datenbank: {@code faellig} (erstes Datum ohne Manifest) fehlt, wenn der Tag schon erzeugt sein müsste
     * ({@link ManifestErzeugen#zielTag}) und entweder das Ende des Tages und der letzte Fortschritt (zuletzt erstelltes
     * Manifest; ohne Manifest kein Fortschritt) beide mehr als {@code frist} zurückliegen oder der jüngste Auftrag
     * {@code MANIFEST} des Mandanten in {@code FEHLER} steht. So meldet ein Nachholen über mehrere Folgeaufträge nichts,
     * solange es vorankommt.
     */
    static Optional<Befund> befund(LocalDate faellig, Optional<Instant> letzterFortschritt,
                                   Optional<String> juengsterAuftrag, Duration frist,
                                   Instant jetzt) {
        if (faellig.isAfter(ManifestErzeugen.zielTag(Nutzlast.leer(), jetzt))) {
            return Optional.empty();
        }
        Instant grenze = jetzt.minus(frist);
        boolean fristAbgelaufen = ManifestFenster.bis(faellig).isBefore(grenze)
                && letzterFortschritt.map(e -> e.isBefore(grenze)).orElse(true);
        if (fristAbgelaufen || juengsterAuftrag.filter(FEHLER::equals).isPresent()) {
            return Optional.of(new Befund(Befund.Quelle.MANIFEST, faellig.toString(), FEHLT));
        }
        return Optional.empty();
    }
}
