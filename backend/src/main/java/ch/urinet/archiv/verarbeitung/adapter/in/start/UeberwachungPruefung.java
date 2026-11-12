package ch.urinet.archiv.verarbeitung.adapter.in.start;

import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Startprüfung der Überwachung (Req 16.5, Review PR #91): {@code archiv.ueberwachung.grenze} muss grösser als 0 und
 * {@code archiv.ueberwachung.betrieb-mandant} ein gültiger Mandant-Code sein, sonst bricht der Start ab. Ohne diese
 * Prüfung entstünde die Bean {@link Ueberwachung} erst im ersten Takt und die Alarme fielen still aus. Gilt in jeder
 * Rolle, damit eine falsche Einstellung schon beim Deploy auffällt.
 */
@ApplicationScoped
public class UeberwachungPruefung {

    void beimStart(@Observes StartupEvent ereignis,
                   @ConfigProperty(name = "archiv.ueberwachung.grenze") Duration grenze,
                   @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant") String betrieb) {
        Ueberwachung.pruefeEinstellungen(grenze, betrieb);
    }
}
