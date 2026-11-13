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

    /**
     * Liest zuerst den letzten Fehlschlag, danach den Stand der Kette (Review PR #114, Kann 2): Ein Lauf hält jedes
     * Manifest in einer eigenen Transaktion fest, bevor er in {@code FEHLER} endet. Sieht die erste Abfrage diesen
     * Fehlschlag, zeigt die zweite darum mindestens den Stand danach, und der Befund nennt nie ein Datum, das schon ein
     * Manifest hat. Umgekehrt verpasst ein Takt höchstens einen Fehlschlag, der zwischen den Abfragen endet; den meldet
     * der nächste Takt. Ein gemeinsamer Schnappschuss ist darum nicht nötig, und beide Ports bleiben im eigenen Modul.
     */
    @Override
    public Optional<List<Befund>> befunde(MandantCode mandant) {
        Optional<Instant> letzterFehler = auftraege.letzterFehler(mandant, ManifestErzeugen.TYP);
        Optional<ManifestFortschritt.Stand> stand = fortschritt.stand(mandant);
        Optional<Instant> anlage = stand.isPresent() ? Optional.empty() : register.erstellt(mandant);
        LocalDate faellig = ManifestErzeugen.naechstes(stand.map(ManifestFortschritt.Stand::letztesDatum), () -> anlage);
        return Optional.of(befund(faellig, stand.map(ManifestFortschritt.Stand::zuletztErstellt), anlage,
                letzterFehler, frist, Instant.now()).stream().toList());
    }

    /**
     * Regel ohne Datenbank: {@code faellig} (erstes Datum ohne Manifest) fehlt, wenn der Tag schon erzeugt sein müsste
     * ({@link ManifestErzeugen#zielTag}) und (a) das Ende des Tages und der letzte Fortschritt (zuletzt erstelltes
     * Manifest; ohne Manifest kein Fortschritt) beide mehr als {@code frist} zurückliegen oder (b) ein Auftrag
     * {@code MANIFEST} des Mandanten nach dem letzten Fortschritt (ohne Manifest: nach der Anlage des Mandanten) und
     * nach dem Fälligwerden von {@code faellig} (Tagesende plus {@link ManifestErzeugen#NACHLAUF}) in
     * {@code FEHLER} geendet hat. So meldet ein Nachholen über mehrere Folgeaufträge nichts, solange es vorankommt. Ein
     * neuerer Auftrag in {@code OFFEN} oder {@code LAEUFT} oder ein Lauf ohne Arbeit (Sperre belegt) hebt (b) nicht auf,
     * erst ein Fortschritt; sonst setzte der Abgleich den Alarm auf erledigt, und der nächste Fehlschlag brächte für
     * denselben Tag eine zweite Mail (Review PR #114, Req 16.5).
     */
    static Optional<Befund> befund(LocalDate faellig, Optional<Instant> letzterFortschritt, Optional<Instant> anlage,
                                   Optional<Instant> letzterFehler, Duration frist, Instant jetzt) {
        if (faellig.isAfter(ManifestErzeugen.zielTag(Nutzlast.leer(), jetzt))) {
            return Optional.empty();
        }
        Instant grenze = jetzt.minus(frist);
        boolean fristAbgelaufen = ManifestFenster.bis(faellig).isBefore(grenze)
                && letzterFortschritt.map(e -> e.isBefore(grenze)).orElse(true);
        Optional<Instant> bezug = letzterFortschritt.or(() -> anlage);
        // Ein Fehlschlag zählt erst, wenn der Tag beim Fehlschlag schon fällig war (Review PR #119): Scheitert ein Lauf
        // bei vollständiger Kette, gälte er sonst nach Mitternacht für den neuen Tag, noch bevor ihn ein Lauf erzeugt.
        Instant faelligAb = ManifestFenster.bis(faellig).plus(ManifestErzeugen.NACHLAUF);
        boolean gescheitert = letzterFehler
                .filter(f -> f.isAfter(faelligAb) && bezug.map(f::isAfter).orElse(true)).isPresent();
        if (fristAbgelaufen || gescheitert) {
            return Optional.of(new Befund(Befund.Quelle.MANIFEST, faellig.toString(), FEHLT));
        }
        return Optional.empty();
    }
}
