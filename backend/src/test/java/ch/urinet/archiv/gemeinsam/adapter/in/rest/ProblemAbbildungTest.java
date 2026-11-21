package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.EntwurfBefunde;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;
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
    void identitaetsdienstFehlerWerdenMitRetryAfterAbgebildet() {
        try (Response nichtErreichbar = abbildung.fachFehler(
                new ch.urinet.archiv.gemeinsam.domain.FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));
             Response ueberlastet = abbildung.fachFehler(
                     new ch.urinet.archiv.gemeinsam.domain.FachFehler(Fehlercode.UEBERLASTET))) {
            assertThat(nichtErreichbar.getStatus()).isEqualTo(503);
            assertThat(nichtErreichbar.getHeaderString("Retry-After")).isEqualTo("1");
            assertThat(ueberlastet.getStatus()).isEqualTo(503);
            assertThat(ueberlastet.getHeaderString("Retry-After")).isEqualTo("1");
        }
        try (Response fehler = abbildung.fachFehler(
                new ch.urinet.archiv.gemeinsam.domain.FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER))) {
            assertThat(fehler.getStatus()).isEqualTo(502);
            assertThat(fehler.getHeaderString("Retry-After")).isNull();
        }
    }

    @Test
    void stepUpFordertErneuteAnmeldungMitDerZulaessigenDauer() {
        try (Response antwort = abbildung.fachFehler(new StepUpNoetig(450))) {
            assertThat(antwort.getStatus()).isEqualTo(401);
            assertThat(antwort.getHeaderString("WWW-Authenticate"))
                    .contains("insufficient_user_authentication", "max_age=\"450\"");
            assertThat(antwort.getEntity()).asString().contains("\"fehlercode\":\"STEP_UP_NOETIG\"");
        }
    }

    @Test
    void regelverletzungEnthaeltNurDieRegelcodesUndMeldungen() {
        var fehler = new RegelVerletzt(java.util.List.of(
                new RegelVerletzt.Befund("datum_pflicht", "Belegdatum fehlt.")));
        try (Response antwort = abbildung.fachFehler(fehler)) {
            assertThat(antwort.getStatus()).isEqualTo(422);
            assertThat(antwort.getEntity()).asString()
                    .contains("\"fehlercode\":\"REGEL_VERLETZT\"")
                    .contains("\"regeln\":[{\"code\":\"datum_pflicht\",\"meldung\":\"Belegdatum fehlt.\"}]");
        }
    }

    @Test
    void entwurfsbefundNenntDieBefundcodes() {
        try (Response antwort = abbildung.fachFehler(new EntwurfBefunde(java.util.List.of("STUFE_UEBERBLICK")))) {
            assertThat(antwort.getStatus()).isEqualTo(422);
            assertThat(antwort.getEntity()).asString()
                    .contains("\"fehlercode\":\"ENTWURF_BEFUND\"")
                    .contains("\"befunde\":[\"STUFE_UEBERBLICK\"]");
        }
    }

    @Test
    void ungueltigeKonfigurationNenntDieBefunde() {
        var fehler = new ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig(java.util.List.of(
                new ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig.Befund("/arten/0", "required",
                        "Die Datei verletzt das Schema.")));
        try (Response antwort = abbildung.fachFehler(fehler)) {
            assertThat(antwort.getStatus()).isEqualTo(422);
            assertThat(antwort.getEntity()).asString()
                    .contains("\"fehlercode\":\"KONFIGURATION_UNGUELTIG\"")
                    .contains("\"befunde\":[{\"pfad\":\"/arten/0\",\"code\":\"required\","
                            + "\"meldung\":\"Die Datei verletzt das Schema.\"}]");
        }
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

    @Test
    void zyklischeUrsachenketteMitDeadlockIstEinStatuskonflikt() {
        SQLException deadlock = new SQLException("synthetischer Deadlock", "40P01");
        Throwable anfang = new RuntimeException("synthetischer Zyklus") {
            @Override
            public synchronized Throwable getCause() {
                return deadlock;
            }
        };
        deadlock.initCause(anfang);
        pruefen(anfang, 409, Fehlercode.STATUS_KONFLIKT);
    }

    @Test
    void zyklischeUrsachenketteMitFremdemSqlStateBleibtEinInternerFehler() {
        SQLException fremd = new SQLException("synthetischer Constraint-Fehler", "23505");
        Throwable anfang = new RuntimeException("synthetischer Zyklus") {
            @Override
            public synchronized Throwable getCause() {
                return fremd;
            }
        };
        fremd.initCause(anfang);
        pruefen(anfang, 500, Fehlercode.INTERNER_FEHLER);
    }

    private void pruefen(Throwable fehler, int status, Fehlercode code) {
        try (Response antwort = abbildung.unerwartet(fehler)) {
            assertThat(antwort.getStatus()).isEqualTo(status);
            assertThat(antwort.getEntity()).asString().contains("\"fehlercode\":\"" + code.name() + "\"")
                    .doesNotContain("synthetisch");
        }
    }
}
