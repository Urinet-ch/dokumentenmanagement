package ch.urinet.archiv.gemeinsam.adapter.in.health;

import ch.urinet.archiv.gemeinsam.domain.Bereitschaftsfrist;
import ch.urinet.archiv.gemeinsam.domain.Bereitschaftsfrist.Lage;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Selbstheilung der Readiness (entschieden von Fabian am 11.11.2026, PR #42 P42-1; design.md «Betrieb auf der VPS»):
 * Die Readiness umfasst den OIDC-Tenant ({@code quarkus.oidc.health.enabled=true}). War Keycloak beim Start nicht
 * erreichbar (Neustart der VPS: Docker startet {@code api} ohne {@code depends_on}), bleibt der Tenant «Not Ready», bis
 * eine angemeldete Anfrage ihn neu aufbaut – die kommt nicht, weil Traefik nicht an einen unhealthy Container leitet.
 * Ist der Tenant {@code archiv.oidc.waechter.frist} lang nicht bereit, beendet sich die API darum mit
 * {@value #EXIT_CODE}, und {@code restart: unless-stopped} startet sie neu. Jeder Neustart beginnt die Frist neu
 * (mindestens {@link Bereitschaftsfrist#MINDESTFRIST}), das verhindert eine schnelle Schleife.
 * <p>
 * Das Log nennt eine Fehler-ID und den festen Code {@value #FEHLERCODE} (für den Deploy-Workflow), nie Adressen oder
 * Meldungen. Ohne OIDC-Health-Check (z. B. im Kommando-Modus, Tenant aus) prüft der Wächter nichts.
 */
@Startup
@ApplicationScoped
public class OidcBereitschaftWaechter {

    /** EX_TEMPFAIL (sysexits.h): vorübergehender Fehler, ein Neustart kann helfen. */
    public static final int EXIT_CODE = 75;
    public static final String FEHLERCODE = "OIDC_NICHT_BEREIT";
    static final String OIDC_CHECK = "io.quarkus.oidc.runtime.health.OidcTenantHealthCheck";

    private static final Logger LOG = Logger.getLogger(OidcBereitschaftWaechter.class);

    private final Instance<HealthCheck> checks;
    private final ProzessEnde ende;
    private final Bereitschaftsfrist frist;
    private final boolean aktiv;
    private volatile Optional<HealthCheck> oidc;
    private volatile boolean beendet;

    public OidcBereitschaftWaechter(@Any Instance<HealthCheck> checks, ProzessEnde ende,
                                    @ConfigProperty(name = "archiv.oidc.waechter.frist") Duration frist,
                                    @ConfigProperty(name = "quarkus.oidc.health.enabled") boolean health,
                                    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "true")
                                    boolean tenant) {
        this.checks = checks;
        this.ende = ende;
        // bricht den Start bei einer zu kurzen Frist ab (Schleifenschutz)
        this.frist = new Bereitschaftsfrist(frist);
        this.aktiv = health && tenant;
    }

    @Scheduled(identity = "oidc-waechter", every = "${archiv.oidc.waechter.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void takt() {
        pruefen(System.nanoTime());
    }

    /** Eine Prüfung zum Zeitpunkt {@code jetztNanos} (monoton); leer, wenn der Wächter nichts prüft. */
    public Optional<Lage> pruefen(long jetztNanos) {
        if (!aktiv || beendet) {
            return Optional.empty();
        }
        Optional<HealthCheck> check = oidcCheck();
        if (check.isEmpty()) {
            return Optional.empty();
        }
        boolean bereit;
        try {
            bereit = check.get().call().getStatus() == HealthCheckResponse.Status.UP;
        } catch (RuntimeException e) {
            LOG.warnf("OIDC-Bereitschaft nicht prüfbar, ursache=%s", Ursachen.kette(e));
            bereit = false;
        }
        Lage lage = frist.melden(bereit, jetztNanos);
        if (lage == Lage.WARTEN && frist.zaehler() == 1) {
            LOG.warnf("fehlercode=%s: OIDC-Tenant nicht bereit; bleibt es %d s so, startet die API neu",
                    FEHLERCODE, frist.frist().toSeconds());
        } else if (lage == Lage.BEENDEN) {
            beendet = true;
            LOG.errorf("Fehler %s: fehlercode=%s, OIDC-Tenant seit %d s nicht bereit (%d Prüfungen), API beendet sich"
                            + " mit Exit-Code %d für einen Neustart", UUID.randomUUID(), FEHLERCODE,
                    frist.dauer(jetztNanos).toSeconds(), frist.zaehler(), EXIT_CODE);
            ende.beenden(EXIT_CODE);
        }
        return Optional.of(lage);
    }

    private Optional<HealthCheck> oidcCheck() {
        Optional<HealthCheck> gefunden = oidc;
        if (gefunden == null) {
            gefunden = checks.handlesStream()
                    .filter(h -> h.getBean().getBeanClass().getName().equals(OIDC_CHECK))
                    .findFirst()
                    .map(Instance.Handle::get);
            if (gefunden.isEmpty()) {
                LOG.errorf("fehlercode=%s: OIDC-Health-Check nicht gefunden, der Wächter prüft nichts", FEHLERCODE);
            }
            oidc = gefunden;
        }
        return gefunden;
    }
}
