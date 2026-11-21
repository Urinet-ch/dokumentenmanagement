package ch.urinet.archiv.benutzer.adapter.out.keycloak;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import io.smallrye.health.api.HealthGroup;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class IdentitaetsdienstHealthCheckTest {

    @Test
    void istNurInDerDetailHealthGruppe() {
        assertThat(IdentitaetsdienstHealthCheck.class.getAnnotation(HealthGroup.class).value())
                .isEqualTo("detail");
    }

    @Test
    void meldetVerbindungserfolg() {
        var antwort = new IdentitaetsdienstHealthCheck(dienst(() -> { })).call();

        assertThat(antwort.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void meldetFachfehlerOhneInterneDetails() {
        var antwort = new IdentitaetsdienstHealthCheck(
                dienst(() -> { throw new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR); })).call();

        assertThat(antwort.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(antwort.getData()).hasValueSatisfying(daten ->
                assertThat(daten).containsEntry("fehlercode", Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR.name()));
    }

    private IdentitaetsDienst dienst(Runnable verbindungspruefung) {
        return (IdentitaetsDienst) Proxy.newProxyInstance(IdentitaetsDienst.class.getClassLoader(),
                new Class<?>[]{IdentitaetsDienst.class}, (proxy, methode, argumente) -> {
                    if (methode.getName().equals("verbindungPruefen")) {
                        verbindungspruefung.run();
                        return null;
                    }
                    throw new UnsupportedOperationException(methode.getName());
                });
    }
}
