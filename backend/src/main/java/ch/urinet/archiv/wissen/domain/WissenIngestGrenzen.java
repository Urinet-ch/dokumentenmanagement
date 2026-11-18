package ch.urinet.archiv.wissen.domain;

/** Konfigurationsgrenzen für Vorprüfung und Schätzung eines Ingests (Spec 04, Req 13.4, 13.6). */
public record WissenIngestGrenzen(int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                                  int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                                  long monatsbudgetTokens) {

    public WissenIngestGrenzen {
        if (abschnittZeichen <= 0 || maxSeiten <= 0 || indexMaxZeichen <= 0 || seiteMaxZeichen <= 0
                || werkzeugMaxZeichen <= 0 || budgetTokens <= 0 || monatsbudgetTokens <= 0) {
            throw new IllegalArgumentException("Wissensbasis-Grenzen müssen positiv sein");
        }
    }
}
