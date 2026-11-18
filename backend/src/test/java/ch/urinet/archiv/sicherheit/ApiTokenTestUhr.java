package ch.urinet.archiv.sicherheit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Feste Uhr nur für die Szenario-Schritte ({@code ApiTokenSchritte}). Produktivcode ({@code ApiTokenVerwaltung},
 * {@code ApiTokenPruefung}) rechnet weiter mit der Systemuhr; die Szenarien brauchen deshalb keinen «heute»-Fall
 * (nur gestern/morgen/Abstände, die über Mitternacht stabil bleiben).
 */
@ApplicationScoped
public class ApiTokenTestUhr {

    @Produces
    @ApplicationScoped
    Clock uhr() {
        return Clock.fixed(Instant.now(), ZoneOffset.UTC);
    }
}
