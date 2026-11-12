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
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Überwachung (Req 16.5, design.md «Überwachung»): je aktiven Mandanten Dokumente, die länger als
 * {@code archiv.ueberwachung.grenze} in {@code VERARBEITUNG}, {@code FEHLER} oder {@code ARCHIVIERUNG_FEHLER} stehen,
 * und fehlende Manifeste; einmal für den Betrieb der Stand der Sicherung. Je Befund geht genau eine Mail an den Inhaber,
 * nur mit IDs und Status (Req 16.7). Scheitert der Versand, versucht es der nächste Lauf wieder. Ein Fehler bei einem
 * Mandanten hält die anderen nicht auf.
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

    public Ueberwachung(MandantRegister register, DokumentUeberwachung dokumente, ManifestUeberwachung manifeste,
                        SicherungUeberwachung sicherung, AlarmSpeicher alarme,
                        InhaberBenachrichtigung benachrichtigung,
                        @ConfigProperty(name = "archiv.ueberwachung.grenze") Duration grenze,
                        @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant") String betrieb) {
        if (grenze.isNegative() || grenze.isZero()) {
            throw new IllegalArgumentException("archiv.ueberwachung.grenze muss positiv sein");
        }
        this.register = register;
        this.dokumente = dokumente;
        this.manifeste = manifeste;
        this.sicherung = sicherung;
        this.alarme = alarme;
        this.benachrichtigung = benachrichtigung;
        this.grenze = grenze;
        this.betrieb = new MandantCode(betrieb);
    }

    /** Ein Lauf über alle aktiven Mandanten; liefert die Anzahl zugestellter Mails. */
    public int pruefen() {
        List<MandantCode> mandanten = register.aktive().stream().map(Mandant::code).toList();
        int gemeldet = 0;
        for (MandantCode mandant : mandanten) {
            gemeldet += quelle(mandant, Befund.Quelle.DOKUMENT, () -> Optional.of(dokumente.seitMindestens(mandant,
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
                if (alarme.beanspruchen(mandant, befund)) {
                    if (benachrichtigung.alarm(mandant, befund)) {
                        gemeldet++;
                    } else {
                        alarme.freigeben(mandant, befund);
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
}
