package ch.urinet.archiv.importe.application;

import java.util.UUID;

/** Reiht den Auftrag {@code CSV_VORSCHAU} für einen Lauf ein, in der laufenden Transaktion (Aufgabe 4.2). */
@FunctionalInterface
public interface VorschauAuftraege {

    void einreihen(UUID laufId);
}
