package ch.urinet.archiv.verarbeitung.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frühwarnung vor Speicherknappheit der JVM (Req 16.5, design.md «Überwachung»): Je Bereich (Metaspace, Compressed
 * Class Space, Heap nach Garbage Collection) vergleicht sie die Auslastung mit {@code warn-prozent} und
 * {@code kritisch-prozent}. Über einer Schwelle schreibt jeder Lauf eine WARN-Zeile; die Mail an den Inhaber geht je
 * Bereich und Stufe einmal, erneut erst, nachdem der Wert wieder unter die Schwelle gefallen ist (Zustand nur im
 * Speicher, nach einem Neustart darf sie neu melden). Ohne Maximum gibt es keine Schwelle, nur eine Log-Zeile. Scheitert
 * der Versand, versucht es der nächste Lauf wieder.
 */
@ApplicationScoped
public class SpeicherUeberwachung {

    public enum Stufe { WARNUNG, KRITISCH }

    private static final Logger LOG = Logger.getLogger(SpeicherUeberwachung.class);
    static final double MIB = 1024.0 * 1024.0;

    private final SpeicherQuelle quelle;
    private final SpeicherAlarm alarm;
    private final SpeicherKennzahl kennzahl;
    private final int warn;
    private final int kritisch;
    private final Map<String, Set<Stufe>> gemeldet = new ConcurrentHashMap<>();

    public SpeicherUeberwachung(SpeicherQuelle quelle, SpeicherAlarm alarm, SpeicherKennzahl kennzahl,
                                @ConfigProperty(name = "archiv.ueberwachung.speicher.warn-prozent") int warn,
                                @ConfigProperty(name = "archiv.ueberwachung.speicher.kritisch-prozent") int kritisch) {
        pruefeEinstellungen(warn, kritisch);
        this.quelle = quelle;
        this.alarm = alarm;
        this.kennzahl = kennzahl;
        this.warn = warn;
        this.kritisch = kritisch;
    }

    public static void pruefeEinstellungen(int warn, int kritisch) {
        if (warn < 1 || kritisch > 100 || warn >= kritisch) {
            throw new IllegalArgumentException("archiv.ueberwachung.speicher: 0 < warn-prozent < kritisch-prozent "
                    + "<= 100 verlangt, ist " + warn + " und " + kritisch);
        }
    }

    public void pruefen() {
        pruefen(quelle.messen());
    }

    public void pruefen(List<SpeicherMessung> messungen) {
        for (SpeicherMessung messung : messungen) {
            try {
                pruefen(messung);
            } catch (RuntimeException e) {
                LOG.warnf("Speicherüberwachung von %s fehlgeschlagen, ursache=%s", messung.pool(),
                        e.getClass().getName());
            }
        }
    }

    private void pruefen(SpeicherMessung messung) {
        String pool = messung.pool();
        if (!messung.begrenzt()) {
            gemeldet.remove(pool);
            LOG.infof("Speicher %s: %.0f MiB benutzt, kein Maximum gesetzt (keine Schwelle)", pool,
                    messung.benutzt() / MIB);
            return;
        }
        double prozent = messung.prozent();
        kennzahl.setzen(pool, prozent);
        Stufe stufe = prozent >= kritisch ? Stufe.KRITISCH : prozent >= warn ? Stufe.WARNUNG : null;
        Set<Stufe> bereits = gemeldet.computeIfAbsent(pool, p -> EnumSet.noneOf(Stufe.class));
        bereits.removeIf(s -> stufe == null || s.compareTo(stufe) > 0);
        if (stufe == null) {
            return;
        }
        LOG.warnf("Speicher %s knapp (%s): %d %% belegt, %.0f von %.0f MiB", pool, stufe, (int) prozent,
                messung.benutzt() / MIB, messung.maximum() / MIB);
        if (!bereits.contains(stufe) && alarm.speicherKnapp(stufe, messung)) {
            bereits.addAll(EnumSet.range(Stufe.WARNUNG, stufe));
        }
    }
}
