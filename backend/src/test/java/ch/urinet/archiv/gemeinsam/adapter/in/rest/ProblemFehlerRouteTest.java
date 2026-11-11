package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import io.quarkus.runtime.configuration.MemorySize;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Grenze der Anfrage je Content-Type (Aufgabe 2.4): nur Uploads dürfen bis zur Gesamtgrenze gehen. */
class ProblemFehlerRouteTest {

    private static final long MB = 1024L * 1024;

    private final ProblemFehlerRoute route = new ProblemFehlerRoute(Optional.of(MemorySize.of(500 * MB)),
            MemorySize.of(10 * MB));

    @Test
    void uploadGiltBisZurGesamtgrenze() {
        assertThat(route.grenze("multipart/form-data; boundary=x")).isEqualTo(500 * MB);
        assertThat(route.grenze(" Multipart/Form-Data;boundary=x")).isEqualTo(500 * MB);
    }

    @Test
    void alleAnderenAnfragenNurBisZurKleinenGrenze() {
        assertThat(route.grenze("application/json")).isEqualTo(10 * MB);
        assertThat(route.grenze("application/octet-stream")).isEqualTo(10 * MB);
        assertThat(route.grenze("multipart/mixed; boundary=x")).isEqualTo(10 * MB);
        assertThat(route.grenze(null)).isEqualTo(10 * MB);
    }

    @Test
    void dieKleinereGrenzeGewinnt() {
        ProblemFehlerRoute knapp = new ProblemFehlerRoute(Optional.of(MemorySize.of(MB)), MemorySize.of(10 * MB));
        assertThat(knapp.grenze("application/json")).isEqualTo(MB);
    }
}
