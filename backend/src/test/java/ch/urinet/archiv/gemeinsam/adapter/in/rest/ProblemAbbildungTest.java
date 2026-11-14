package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAbbildungTest {

    private final ProblemAbbildung abbildung = new ProblemAbbildung();

    @Test
    void deadlockIstEinStatuskonflikt() {
        pruefen(new PSQLException("synthetischer Deadlock", PSQLState.DEADLOCK_DETECTED),
                409, Fehlercode.STATUS_KONFLIKT);
    }

    @Test
    void deadlockInHibernateUrsachenketteIstEinStatuskonflikt() {
        SQLException deadlock = new PSQLException("synthetischer Deadlock", PSQLState.DEADLOCK_DETECTED);
        pruefen(new PersistenceException("synthetischer Persistenzfehler",
                new IllegalStateException("synthetischer Adapterfehler", deadlock)),
                409, Fehlercode.STATUS_KONFLIKT);
    }

    @Test
    void abgelaufeneSperrzeitIstEinStatuskonflikt() {
        pruefen(new SQLException("synthetische Sperrzeit", "55P03"), 409, Fehlercode.STATUS_KONFLIKT);
    }

    @Test
    void fremderSqlStateBleibtEinInternerFehler() {
        pruefen(new SQLException("synthetischer Constraint-Fehler", "23505"),
                500, Fehlercode.INTERNER_FEHLER);
    }

    @Test
    void zyklischeUrsachenketteWirdBegrenzt() {
        Throwable zyklus = new RuntimeException("synthetischer Zyklus") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        pruefen(zyklus, 500, Fehlercode.INTERNER_FEHLER);
    }

    private void pruefen(Throwable fehler, int status, Fehlercode code) {
        try (Response antwort = abbildung.unerwartet(fehler)) {
            assertThat(antwort.getStatus()).isEqualTo(status);
            assertThat(antwort.getEntity()).asString().contains("\"fehlercode\":\"" + code.name() + "\"")
                    .doesNotContain("synthetisch");
        }
    }
}
