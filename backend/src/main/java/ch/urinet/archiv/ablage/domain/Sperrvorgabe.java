package ch.urinet.archiv.ablage.domain;

import java.time.LocalDate;

/**
 * Was die Aufbewahrung für die Sperre eines Dokuments vorgibt (Req 6.3, 6.9, 6.12, 9.8): {@code gesetzlichePflicht} der
 * Art, {@code aufbewahrenBis} ({@code null} = unbefristet) und «Frist abgelaufen». Eigener Wert der Ablage, damit sie
 * nicht an der Berechnung in {@code dokumentart} hängt.
 */
public record Sperrvorgabe(boolean gesetzlichePflicht, LocalDate aufbewahrenBis, boolean fristAbgelaufen) {

    public static Sperrvorgabe ohnePflicht() {
        return new Sperrvorgabe(false, null, false);
    }

    public static Sperrvorgabe bis(LocalDate aufbewahrenBis) {
        return new Sperrvorgabe(true, aufbewahrenBis, false);
    }

    public static Sperrvorgabe unbefristet() {
        return new Sperrvorgabe(true, null, false);
    }
}
