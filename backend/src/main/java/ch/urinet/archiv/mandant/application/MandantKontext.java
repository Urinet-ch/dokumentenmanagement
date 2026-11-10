package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Setzt den Mandanten für Arbeit ohne Anmeldung (Worker-Aufträge, Admin-Kommando; design.md «Mandant»). Der Mandant
 * stammt aus dem Auftrag bzw. der Konfiguration, nie aus Benutzereingaben. Jeder Aufruf läuft in einem eigenen
 * Request-Kontext, damit die Hibernate-Session mit diesem Mandanten geöffnet wird; der Mandant gilt nur für den
 * aktuellen Thread und wird auch bei Ausnahmen entfernt.
 * <p>
 * Ein neuer Kontext darf nicht in einer laufenden JTA-Transaktion beginnen: Die Hibernate-Session hängt an der
 * Transaktion und behielte den Mandanten des ersten {@code mit}, ein zweites {@code mit} mit anderem Mandanten läse und
 * schriebe im fremden Schema (fail closed, design.md «Mandant»). Die Transaktion gehört darum in den Block.
 */
@ApplicationScoped
public class MandantKontext {

    private static final ThreadLocal<MandantCode> AKTUELL = new ThreadLocal<>();

    private final Instance<RequestContextController> kontrollen;
    private final MandantAusAnmeldung anmeldung;
    private final TransactionManager transaktionen;

    public MandantKontext(Instance<RequestContextController> kontrollen, MandantAusAnmeldung anmeldung,
                          TransactionManager transaktionen) {
        this.kontrollen = kontrollen;
        this.anmeldung = anmeldung;
        this.transaktionen = transaktionen;
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
        if (transaktionAktiv()) {
            throw new IllegalStateException("Mandanten-Kontext in einer laufenden Transaktion ist nicht erlaubt");
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

    private boolean transaktionAktiv() {
        try {
            return transaktionen.getStatus() != Status.STATUS_NO_TRANSACTION;
        } catch (SystemException e) {
            throw new IllegalStateException("Transaktionsstatus nicht lesbar", e);
        }
    }
}
