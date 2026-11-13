package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.verarbeitung.application.AuftragStand;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Letzter Fehlschlag je Mandant und Typ (Req 15.8, 16.5; Review PR #114): Neuere Aufträge in OFFEN, LAEUFT oder
 * FERTIG verdecken ihn nicht, ein Fehlschlag eines anderen Mandanten oder Typs zählt nicht.
 */
@QuarkusTest
class JdbcAuftragStandTest {

    /** Ohne Handler; der Poller läuft im Test nicht, und die Zeilen werden nach jedem Test gelöscht. */
    private static final AuftragTyp TYP = new AuftragTyp("TEST_STAND");
    private static final AuftragTyp ANDERER = new AuftragTyp("TEST_STAND_ANDERER");
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject
    AuftragTestHilfe hilfe;

    @Inject
    AuftragStand stand;

    @BeforeEach
    void vorbereiten() throws SQLException {
        hilfe.vorbereiten();
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        hilfe.ausfuehren("delete from public.auftrag where typ like 'TEST\\_STAND%'");
    }

    @Test
    void ohneFehlschlagLeer() throws SQLException {
        auftrag(URINET, TYP, "FERTIG", Instant.now());
        auftrag(URINET, TYP, "OFFEN", null);
        assertThat(stand.letzterFehler(URINET, TYP)).isEmpty();
    }

    @Test
    void neuereAuftraegeVerdeckenDenFehlschlagNicht() throws SQLException {
        Instant frueher = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant fehler = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        auftrag(URINET, TYP, "FEHLER", frueher);
        auftrag(URINET, TYP, "FEHLER", fehler);
        auftrag(URINET, TYP, "FERTIG", Instant.now());
        auftrag(URINET, TYP, "LAEUFT", null);
        auftrag(URINET, TYP, "OFFEN", null);
        assertThat(stand.letzterFehler(URINET, TYP)).contains(fehler);
    }

    @Test
    void andererMandantUndAndererTypZaehlenNicht() throws SQLException {
        auftrag(VHF, TYP, "FEHLER", Instant.now());
        auftrag(URINET, ANDERER, "FEHLER", Instant.now());
        assertThat(stand.letzterFehler(URINET, TYP)).isEmpty();
        assertThat(stand.letzterFehler(VHF, TYP)).isPresent();
        assertThat(stand.letzterFehler(VHF, ANDERER)).isEmpty();
    }

    private void auftrag(MandantCode mandant, AuftragTyp typ, String status, Instant beendet) throws SQLException {
        long id = hilfe.rohEinreihen(mandant.wert(), typ, "{}");
        hilfe.aendern(id, "status = '" + status + "', nachbearbeitet = now()"
                + (beendet == null ? "" : ", beendet = timestamptz '" + beendet + "'"));
    }
}
