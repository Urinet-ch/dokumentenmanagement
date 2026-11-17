package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void vorDerResourceAbgelehnteSchluesselpruefungLiefert429MitRetryAfterUndGrund() {
        AtomicInteger status = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        Map<String, String> headers = new HashMap<>();
        HttpServerResponse[] antwort = new HttpServerResponse[1];
        antwort[0] = proxy(HttpServerResponse.class, (methode, argumente) -> switch (methode) {
            case "putHeader" -> {
                headers.put(argumente[0].toString(), argumente[1].toString());
                yield antwort[0];
            }
            case "setStatusCode" -> {
                status.set((Integer) argumente[0]);
                yield antwort[0];
            }
            case "end" -> {
                body.set(argumente[0].toString());
                yield null;
            }
            case "headWritten" -> false;
            default -> null;
        });
        RoutingContext kontext = proxy(RoutingContext.class, (methode, argumente) -> switch (methode) {
            case "failure" -> new ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen(
                    ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen.Grund.SCHLUESSELPRUEFUNG, 2);
            case "response" -> antwort[0];
            default -> null;
        });

        route.ueberlastet(kontext);

        assertThat(status).hasValue(429);
        assertThat(headers).containsEntry("Retry-After", "2");
        assertThat(body.get()).contains("\"fehlercode\":\"ZU_VIELE_ANFRAGEN\"",
                "\"grund\":\"SCHLUESSELPRUEFUNG\"");
    }

    interface Antwort {
        Object auf(String methode, Object[] argumente);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> typ, Antwort antwort) {
        return (T) Proxy.newProxyInstance(typ.getClassLoader(), new Class<?>[]{typ},
                (proxy, methode, argumente) -> antwort.auf(methode.getName(), argumente));
    }
}
