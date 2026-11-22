package ch.urinet.archiv.benutzer.adapter.out.stepup;

import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

@ApplicationScoped
@DefaultBean
@Startup
@Typed(StepUp.class)
public class StepUpMitAuthTime implements StepUp {
    private static final Duration MAX_GUELTIGKEIT = Duration.ofMinutes(15);

    private final SecurityIdentity identitaet;
    private final Duration gueltigkeit;
    private final Clock uhr;

    @Inject
    public StepUpMitAuthTime(SecurityIdentity identitaet,
                             @ConfigProperty(name = "archiv.verwaltung.step-up-gueltigkeit",
                                     defaultValue = "PT5M") Duration gueltigkeit) {
        this(identitaet, gueltigkeit, Clock.systemUTC());
    }

    StepUpMitAuthTime(SecurityIdentity identitaet, Duration gueltigkeit, Clock uhr) {
        this.identitaet = identitaet;
        this.gueltigkeit = gueltigkeit;
        this.uhr = uhr;
        maxAlterSekunden(gueltigkeit);
    }

    @Override
    public void pruefen() {
        if (!(identitaet.getPrincipal() instanceof JsonWebToken token)) {
            throw new FachFehler(Fehlercode.NICHT_ERLAUBT);
        }
        pruefenAuthTime(token.getClaim("auth_time"), gueltigkeit, uhr.instant());
    }

    static void pruefenAuthTime(Object claim, Duration gueltigkeit, Instant jetzt) {
        long maxAlter = maxAlterSekunden(gueltigkeit);
        OptionalLong authTime = epochSekunden(claim);
        if (authTime.isEmpty() || authTime.getAsLong() < 0) {
            throw new StepUpNoetig(maxAlter);
        }
        Instant anmeldung;
        try {
            anmeldung = Instant.ofEpochSecond(authTime.getAsLong());
        } catch (DateTimeException e) {
            throw new StepUpNoetig(maxAlter);
        }
        if (anmeldung.isAfter(jetzt) || anmeldung.plusSeconds(maxAlter).isBefore(jetzt)) {
            throw new StepUpNoetig(maxAlter);
        }
    }

    private static long maxAlterSekunden(Duration gueltigkeit) {
        if (gueltigkeit == null || gueltigkeit.isNegative() || gueltigkeit.isZero()
                || gueltigkeit.getNano() != 0 || gueltigkeit.compareTo(MAX_GUELTIGKEIT) > 0) {
            throw new IllegalArgumentException("archiv.verwaltung.step-up-gueltigkeit muss eine positive ganze "
                    + "Sekundenzahl und höchstens 15 Minuten sein");
        }
        return gueltigkeit.getSeconds();
    }

    private static OptionalLong epochSekunden(Object claim) {
        if (!(claim instanceof Number number)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(new BigDecimal(number.toString()).longValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            return OptionalLong.empty();
        }
    }
}
