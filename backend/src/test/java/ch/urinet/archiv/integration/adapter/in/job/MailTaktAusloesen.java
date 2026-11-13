package ch.urinet.archiv.integration.adapter.in.job;

/** Ruft den paketsichtbaren Takt {@link MailTakt#abrufen()} aus den Szenarien auf, wie es der Scheduler tut. */
public final class MailTaktAusloesen {

    private MailTaktAusloesen() {
    }

    public static void abrufen(MailTakt takt) {
        takt.abrufen();
    }
}
