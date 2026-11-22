package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JdbcTokenWiderrufTest {

    @Inject TokenWiderruf tokenWiderruf;
    @Inject AgroalDataSource datenquelle;

    @Test
    void widerrufVergleichtAusstellungszeitUndCacheLaeuftAb() throws Exception {
        String benutzer = "token-widerruf-" + UUID.randomUUID();
        Instant ausgestellt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        Instant ab = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(tokenWiderruf.widerrufen(benutzer, ausgestellt)).isFalse();
        try {
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement einfuegen = verbindung.prepareStatement(
                         "insert into public.token_widerruf (benutzer, ab) values (?, ?)")) {
                einfuegen.setString(1, benutzer);
                einfuegen.setObject(2, ab.atOffset(java.time.ZoneOffset.UTC));
                einfuegen.executeUpdate();
            }
            Thread.sleep(100);
            assertThat(tokenWiderruf.widerrufen(benutzer, ausgestellt)).isTrue();
            assertThat(tokenWiderruf.widerrufen(benutzer, ab)).isFalse();
            assertThat(tokenWiderruf.widerrufen(benutzer, ab.plusSeconds(1))).isFalse();
        } finally {
            loeschen(benutzer);
        }
    }

    @Test
    void setzenUpsertetMonotonUndAufraeumenEntferntNurAlteZeilen() throws Exception {
        String benutzer = "token-widerruf-" + UUID.randomUUID();
        Instant jetzt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            tokenWiderruf.setzen(benutzer, jetzt);
            tokenWiderruf.setzen(benutzer, jetzt.minusSeconds(30));
            assertThat(tokenWiderruf.widerrufen(benutzer, jetzt.minusSeconds(1))).isTrue();
            assertThat(tokenWiderruf.widerrufen(benutzer, jetzt)).isFalse();

            String alt = benutzer + "-alt";
            tokenWiderruf.setzen(alt, jetzt.minusSeconds(2 * 24 * 60 * 60));
            assertThat(tokenWiderruf.aufraeumen(jetzt.minusSeconds(24 * 60 * 60))).isEqualTo(1);
            assertThat(tokenWiderruf.widerrufen(alt, jetzt.minusSeconds(3 * 24 * 60 * 60))).isFalse();
        } finally {
            loeschen(benutzer);
            loeschen(benutzer + "-alt");
        }
    }

    private void loeschen(String benutzer) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "delete from public.token_widerruf where benutzer = ?")) {
            loeschen.setString(1, benutzer);
            loeschen.executeUpdate();
        }
    }
}
