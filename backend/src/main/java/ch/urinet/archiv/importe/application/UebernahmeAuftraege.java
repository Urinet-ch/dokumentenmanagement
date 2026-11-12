package ch.urinet.archiv.importe.application;

import java.util.UUID;

/** Reiht den Auftrag {@code CSV_UEBERNEHMEN} für einen Lauf ein, in der laufenden Transaktion (Aufgabe 4.3). */
@FunctionalInterface
public interface UebernahmeAuftraege {

    void einreihen(UUID laufId);
}
