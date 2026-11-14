package ch.urinet.archiv.gemeinsam.adapter.in.job;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Führt die Arbeit eines {@code @Scheduled}-Takts in einem eigenen Thread ohne Request-Kontext und ohne Anmeldung aus
 * und wartet auf ihr Ende (design.md «Mandant»). Quarkus aktiviert für jede {@code @Scheduled}-Methode einen
 * Request-Kontext; darin verweigert {@code MandantKontext.mit} ohne passende Anmeldung den Mandanten (fail closed), denn
 * eine schon offene Hibernate-Session könnte einen anderen Mandanten tragen. Im eigenen Thread öffnet {@code mit} einen
 * frischen Request-Kontext mit dem Mandanten, wie im {@code AuftragArbeiter}.
 * <p>
 * Der Takt wartet, damit {@code ConcurrentExecution.SKIP} weiter wirkt. Eine Ausnahme der Arbeit kommt unverändert beim
 * Takt an. Wird der Takt beim Warten unterbrochen (Stopp), unterbricht er die Arbeit und kehrt mit gesetztem
 * Interrupt-Flag zurück.
 */
public final class TaktThread {

    private TaktThread() {
    }

    public static void ausfuehren(String name, Runnable arbeit) {
        FutureTask<Void> aufgabe = new FutureTask<>(arbeit, null);
        // erbt den Context-ClassLoader der Anwendung (ServiceLoader, z. B. TLS); der Request-Kontext von Quarkus und
        // die JTA-Transaktion hängen an gewöhnlichen ThreadLocals und gehen nicht mit
        Thread.ofPlatform().name(name).daemon().start(aufgabe);
        try {
            aufgabe.get();
        } catch (InterruptedException e) {
            aufgabe.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException laufzeit -> throw laufzeit;
                case Error fehler -> throw fehler;
                default -> throw new IllegalStateException("Takt mit geprüfter Ausnahme beendet", e.getCause());
            }
        }
    }
}
