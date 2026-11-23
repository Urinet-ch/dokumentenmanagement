package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.DienstkontoAlarm;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AbgleichAlarmQuelle;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcDienstkontoAlarmTest {
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject MandantProvisionierung provisionierung;
    @Inject MandantKontext kontext;
    @Inject DienstkontoAlarm alarm;
    @Inject AbgleichAlarmQuelle quelle;
    @Inject AgroalDataSource datenquelle;

    @Test
    void mandantenalarmBleibtAuchBeiRollbackUndIstIsoliert() throws Exception {
        vorbereiten(URINET);
        vorbereiten(VHF);
        Set<String> vorherUrinet = schluessel(URINET);
        Set<String> vorherVhf = schluessel(VHF);

        assertThatThrownBy(() -> TaktThread.ausfuehren("dienstkonto-test", () -> kontext.mit(URINET,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    alarm.vormerken(401);
                    throw new IllegalStateException("synthetischer Rollback");
                })))).isInstanceOf(IllegalStateException.class);

        assertThat(schluessel(URINET)).filteredOn(s -> !vorherUrinet.contains(s))
                .singleElement().asString().endsWith(":DIENSTKONTO_UNAUTORISIERT");
        assertThat(schluessel(VHF)).isEqualTo(vorherVhf);
    }

    @Test
    void realmweiterFehlerWirdFuerAlleAktivenMandantenVorgemerkt() throws Exception {
        vorbereiten(URINET);
        vorbereiten(VHF);
        Set<String> vorherUrinet = schluessel(URINET);
        Set<String> vorherVhf = schluessel(VHF);

        TaktThread.ausfuehren("dienstkonto-realm-test", () -> alarm.vormerken(403));

        Set<String> neuUrinet = schluessel(URINET).stream().filter(s -> !vorherUrinet.contains(s))
                .collect(Collectors.toSet());
        Set<String> neuVhf = schluessel(VHF).stream().filter(s -> !vorherVhf.contains(s))
                .collect(Collectors.toSet());
        assertThat(neuUrinet).singleElement().asString().endsWith(":DIENSTKONTO_VERBOTEN");
        assertThat(neuVhf).isEqualTo(neuUrinet);
    }

    private Set<String> schluessel(MandantCode mandant) {
        return quelle.offene(mandant).stream().map(Befund::schluessel).collect(Collectors.toSet());
    }

    private void vorbereiten(MandantCode code) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(code.wert(), null, null);
                }
            }
        }
    }
}
