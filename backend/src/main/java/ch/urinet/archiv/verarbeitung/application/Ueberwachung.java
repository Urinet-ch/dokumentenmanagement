package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Überwachung (Req 16.5, design.md «Überwachung»): je aktiven Mandanten Dokumente, die länger als
 * {@code archiv.ueberwachung.grenze} in {@code VERARBEITUNG}, {@code FEHLER} oder {@code ARCHIVIERUNG_FEHLER} stehen,
 * und fehlende Manifeste; einmal für den Betrieb der Stand der Sicherung. Je Befund geht genau eine Mail an den Inhaber,
 * nur mit IDs und Status (Req 16.7). Scheitert der Versand, versucht es der nächste Lauf wieder; scheitert danach auch
 * das Freigeben, wiederholt es jeder weitere Lauf, bis es gelingt (Review PR #91). Ein Fehler bei einem Mandanten hält
 * die anderen nicht auf.
 */
@ApplicationScoped
public class Ueberwachung {

    private static final Logger LOG = Logger.getLogger(Ueberwachung.class);

    private final MandantRegister register;
    private final DokumentUeberwachung dokumente;
    private final ManifestUeberwachung manifeste;
    private final SicherungUeberwachung sicherung;
    private final AlarmSpeicher alarme;
    private final InhaberBenachrichtigung benachrichtigung;
    private final Duration grenze;
    private final MandantCode betrieb;
    /** Ansprüche ohne zugestellte Mail, deren Freigabe gescheitert ist; der nächste Lauf gibt sie zuerst frei. */
    private final Set<Freigabe> offeneFreigaben = ConcurrentHashMap.newKeySet();

    private record Freigabe(MandantCode mandant, Befund befund, Instant anspruch) {
    }

    public Ueberwachung(MandantRegister register, DokumentUeberwachung dokumente, ManifestUeberwachung manifeste,
                        SicherungUeberwachung sicherung, AlarmSpeicher alarme,
                        InhaberBenachrichtigung benachrichtigung,
                        @ConfigProperty(name = "archiv.ueberwachung.grenze") Duration grenze,
                        @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant") String betrieb) {
        this.betrieb = pruefeEinstellungen(grenze, betrieb);
        this.register = register;
        this.dokumente = dokumente;
        this.manifeste = manifeste;
        this.sicherung = sicherung;
        this.alarme = alarme;
        this.benachrichtigung = benachrichtigung;
        this.grenze = grenze;
    }

    /**
     * Prüft die Einstellungen der Überwachung (auch beim Start, {@code UeberwachungPruefung}) und liefert den
     * Betriebs-Mandanten.
     */
    public static MandantCode pruefeEinstellungen(Duration grenze, String betrieb) {
        if (grenze == null || grenze.isNegative() || grenze.isZero()) {
            throw new IllegalArgumentException("archiv.ueberwachung.grenze muss grösser als 0 sein");
        }
        try {
            return new MandantCode(betrieb);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("archiv.ueberwachung.betrieb-mandant ist kein gültiger Mandant-Code", e);
        }
    }

    /**
     * Prüft {@code archiv.ueberwachung.manifest-frist} (auch beim Start, {@code UeberwachungPruefung}): Nach dieser
     * Dauer ohne Fortschritt gilt ein fälliges Manifest als fehlend (Req 15.8, 16.5).
     */
    public static Duration pruefeManifestFrist(Duration frist) {
        if (frist == null || frist.isNegative() || frist.isZero()) {
            throw new IllegalArgumentException("archiv.ueberwachung.manifest-frist muss grösser als 0 sein");
        }
        return frist;
    }

    /** Ein Lauf über alle aktiven Mandanten; liefert die Anzahl zugestellter Mails. */
    public int pruefen() {
        List<MandantCode> mandanten = register.aktive().stream().map(Mandant::code).toList();
        offeneFreigabenNachholen();
        int gemeldet = 0;
        for (MandantCode mandant : mandanten) {
            gemeldet += quelle(mandant, Befund.Quelle.DOKUMENT, () -> Optional.of(dokumente.laengerAls(mandant,
                    grenze)));
            gemeldet += quelle(mandant, Befund.Quelle.MANIFEST, () -> manifeste.befunde(mandant));
        }
        if (mandanten.contains(betrieb)) {
            gemeldet += quelle(betrieb, Befund.Quelle.SICHERUNG, sicherung::befunde);
        } else {
            LOG.warnf("Überwachung der Sicherung: Mandant %s (archiv.ueberwachung.betrieb-mandant) ist nicht aktiv; "
                    + "kein Alarm zur Sicherung möglich", betrieb.wert());
        }
        return gemeldet;
    }

    private int quelle(MandantCode mandant, Befund.Quelle quelle, Supplier<Optional<List<Befund>>> abfrage) {
        try {
            Optional<List<Befund>> befunde = abfrage.get();
            if (befunde.isEmpty()) {
                return 0;
            }
            alarme.abgleichen(mandant, quelle, befunde.get());
            int gemeldet = 0;
            for (Befund befund : befunde.get()) {
                Optional<Instant> anspruch = alarme.beanspruchen(mandant, befund);
                if (anspruch.isPresent()) {
                    if (benachrichtigung.alarm(mandant, befund)) {
                        gemeldet++;
                    } else {
                        freigeben(new Freigabe(mandant, befund, anspruch.get()));
                    }
                }
            }
            return gemeldet;
        } catch (RuntimeException e) {
            // nächster Lauf versucht es wieder; nur Mandant, Quelle und Klassennamen (Req 16.7)
            LOG.warnf("Überwachung von %s für Mandant %s fehlgeschlagen, ursache=%s", quelle, mandant.wert(),
                    Ursachen.kette(e));
            return 0;
        }
    }

    private void offeneFreigabenNachholen() {
        List.copyOf(offeneFreigaben).forEach(f -> {
            offeneFreigaben.remove(f);
            freigeben(f);
        });
    }

    private void freigeben(Freigabe freigabe) {
        try {
            alarme.freigeben(freigabe.mandant(), freigabe.befund(), freigabe.anspruch());
        } catch (RuntimeException e) {
            offeneFreigaben.add(freigabe);
            LOG.warnf("Alarm %s für Mandant %s nicht freigegeben, nächster Lauf wiederholt, ursache=%s",
                    freigabe.befund().quelle(), freigabe.mandant().wert(), Ursachen.kette(e));
        }
    }
}
