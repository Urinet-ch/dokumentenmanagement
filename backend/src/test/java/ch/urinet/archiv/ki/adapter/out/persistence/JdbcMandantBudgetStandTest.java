package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.oeffentlich.MandantBudget.Stand;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JdbcMandantBudgetStandTest {
    @Inject Instance<AgroalDataSource> datenquelle;
    @Inject MandantProvisionierung provisionierung;

    @Test
    void liestDenLaufendenMonatGetrenntNachMandantOhneZeileAnzulegen() throws Exception {
        for (String code : new String[] {"urinet", "vhf-gsk"}) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        LocalDate monat = LocalDate.of(1901, 2, 1);
        JdbcMandantBudget budget = new JdbcMandantBudget(datenquelle, 2000,
                Clock.fixed(Instant.parse("1901-02-01T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(5));
        MandantCode urinet = new MandantCode("urinet");
        MandantCode vhf = new MandantCode("vhf-gsk");

        assertThat(anzahl(monat)).isZero();
        assertThat(budget.stand(urinet)).isEqualTo(new Stand(monat, 2000, 0));
        assertThat(budget.stand(vhf)).isEqualTo(new Stand(monat, 2000, 0));
        assertThat(anzahl(monat)).isZero();
        try {
            budget.verbrauchen(urinet, monat, 17);
            assertThat(budget.stand(urinet)).isEqualTo(new Stand(monat, 2000, 17));
            assertThat(budget.stand(vhf)).isEqualTo(new Stand(monat, 2000, 0));
        } finally {
            try (Connection verbindung = datenquelle.get().getConnection();
                 PreparedStatement loeschen = verbindung.prepareStatement(
                         "delete from public.mandant_budget where mandant = ? and monat = ?")) {
                loeschen.setString(1, urinet.wert());
                loeschen.setDate(2, Date.valueOf(monat));
                loeschen.executeUpdate();
            }
        }
    }

    private long anzahl(LocalDate monat) throws Exception {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from public.mandant_budget where mandant in ('urinet', 'vhf-gsk') and monat = ?")) {
            abfrage.setDate(1, Date.valueOf(monat));
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    private boolean imRegister(String code) throws Exception {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
