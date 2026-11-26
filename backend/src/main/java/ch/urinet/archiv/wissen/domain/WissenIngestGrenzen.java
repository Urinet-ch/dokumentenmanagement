package ch.urinet.archiv.wissen.domain;

/** Konfigurationsgrenzen für den Ingest (Spec 04, Req 13.4, 13.6; Aufgabe 19.1). */
public record WissenIngestGrenzen(int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                                  int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                                  long monatsbudgetTokens, int auszugMindestZeichen,
                                  double aussageUeberlappung, double randzeilenMindestAnteil) {

    public WissenIngestGrenzen(int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                               int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                               long monatsbudgetTokens) {
        this(abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen, werkzeugMaxZeichen, budgetTokens,
                monatsbudgetTokens, 20, 0.5, 0.3);
    }

    public WissenIngestGrenzen(int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                               int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                               long monatsbudgetTokens, int auszugMindestZeichen,
                               double aussageUeberlappung) {
        this(abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen, werkzeugMaxZeichen, budgetTokens,
                monatsbudgetTokens, auszugMindestZeichen, aussageUeberlappung, 0.3);
    }

    public WissenIngestGrenzen {
        if (abschnittZeichen <= 0 || maxSeiten <= 0 || indexMaxZeichen <= 0 || seiteMaxZeichen <= 0
                || werkzeugMaxZeichen <= 0 || budgetTokens <= 0 || monatsbudgetTokens <= 0
                || auszugMindestZeichen <= 0 || !Double.isFinite(aussageUeberlappung)
                || aussageUeberlappung <= 0 || aussageUeberlappung > 1
                || !Double.isFinite(randzeilenMindestAnteil)
                || randzeilenMindestAnteil <= 0 || randzeilenMindestAnteil > 1) {
            throw new IllegalArgumentException("Wissensbasis-Grenzen müssen positiv sein");
        }
    }
}
