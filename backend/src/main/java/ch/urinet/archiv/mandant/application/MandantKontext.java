package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.Instance;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Setzt den Mandanten für Arbeit ohne Anmeldung (Worker-Aufträge, Admin-Kommando; design.md «Mandant»). Der Mandant
 * stammt aus dem Auftrag bzw. der Konfiguration, nie aus Benutzereingaben. Jeder Aufruf läuft in einem eigenen
 * Request-Kontext, damit die Hibernate-Session mit diesem Mandanten geöffnet wird; der Mandant gilt nur für den
 * aktuellen Thread und wird auch bei Ausnahmen entfernt.
 */
@ApplicationScoped
public class MandantKontext {

    private static final ThreadLocal<MandantCode> AKTUELL = new ThreadLocal<>();

    private final Instance<RequestContextController> kontrollen;
    private final MandantAusAnmeldung anmeldung;

    public MandantKontext(Instance<RequestContextController> kontrollen, MandantAusAnmeldung anmeldung) {
        this.kontrollen = kontrollen;
        this.anmeldung = anmeldung;
    }

    public Optional<MandantCode> aktuell() {
        return Optional.ofNullable(AKTUELL.get());
    }

    public void mit(MandantCode code, Runnable arbeit) {
        mit(code, () -> {
            arbeit.run();
            return null;
        });
    }

    public <T> T mit(MandantCode code, Supplier<T> arbeit) {
        MandantCode vorher = AKTUELL.get();
        if (vorher != null) {
            if (!vorher.equals(code)) {
                throw new IllegalStateException("Mandantenwechsel innerhalb eines Mandanten-Kontexts ist nicht erlaubt");
            }
            return arbeit.get();
        }
        RequestContextController kontrolle = kontrollen.get();
        boolean neu = kontrolle.activate();
        try {
            // in einer laufenden Anfrage könnte die Session schon mit einem anderen Mandanten offen sein
            if (!neu && !(anmeldung.ermitteln() instanceof MandantAusAnmeldung.Gueltig g && g.code().equals(code))) {
                throw new IllegalStateException("Mandanten-Kontext widerspricht der Anmeldung");
            }
            AKTUELL.set(code);
            return arbeit.get();
        } finally {
            AKTUELL.remove();
            if (neu) {
                kontrolle.deactivate();
            }
            kontrollen.destroy(kontrolle);
        }
    }
}
