package ch.urinet.archiv.verarbeitung.adapter.in.job;

import java.util.Arrays;

/** {@code archiv.rolle} enthält {@code worker} (an Kommas getrennt, getrimmt, wie beim Start). */
final class WorkerRolle {

    private WorkerRolle() {
    }

    static boolean aktiv(String rolle) {
        return rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }
}
