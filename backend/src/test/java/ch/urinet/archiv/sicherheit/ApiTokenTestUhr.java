package ch.urinet.archiv.sicherheit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@ApplicationScoped
public class ApiTokenTestUhr {

    @Produces
    @ApplicationScoped
    Clock uhr() {
        return Clock.fixed(Instant.now(), ZoneOffset.UTC);
    }
}
