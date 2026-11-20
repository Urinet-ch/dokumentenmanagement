package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.Vorgangssperre;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JdbcVorgangssperreTest {

    @Inject
    AgroalDataSource datenquelle;

    @Inject
    Vorgangssperre sperre;

    @Test
    void leaseIstMandantenbezogenUndAlterInhaberKannNachAblaufNichtFreigeben() throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var a = new MandantCode("sperre-a-" + suffix);
        var b = new MandantCode("sperre-b-" + suffix);
        UUID alt = UUID.randomUUID();
        UUID neu = UUID.randomUUID();
        try (Connection verbindung = datenquelle.getConnection()) {
            try (PreparedStatement anlegen = verbindung.prepareStatement(
                    "insert into public.mandant (code, name, schema_name, bucket) values (?, ?, ?, ?)")) {
                for (MandantCode mandant : new MandantCode[] {a, b}) {
                    anlegen.setString(1, mandant.wert());
                    anlegen.setString(2, mandant.wert());
                    anlegen.setString(3, mandant.schema());
                    anlegen.setString(4, mandant.wert());
                    anlegen.executeUpdate();
                }
            }
            try {
                assertThat(sperre.belegen(a, alt)).isTrue();
                assertThat(sperre.belegen(a, neu)).isFalse();
                assertThat(sperre.belegen(b, neu)).isTrue();
                assertThat(sperre.freigeben(a, neu)).isFalse();
                assertThat(sperre.gueltig(a, alt)).isTrue();
                try (PreparedStatement ablaufen = verbindung.prepareStatement(
                        "update public.verwaltung_vorgang set bis = clock_timestamp() - interval '1 second' "
                                + "where mandant = ?")) {
                    ablaufen.setString(1, a.wert());
                    ablaufen.executeUpdate();
                }
                assertThat(sperre.gueltig(a, alt)).isFalse();
                assertThat(sperre.belegen(a, neu)).isTrue();
                assertThat(sperre.freigeben(a, alt)).isFalse();
                assertThat(sperre.gueltig(a, neu)).isTrue();
                assertThat(sperre.freigeben(a, neu)).isTrue();
                assertThat(sperre.freigeben(b, neu)).isTrue();
                verbindung.setAutoCommit(false);
                try (PreparedStatement sperren = verbindung.prepareStatement(
                        "select pg_advisory_xact_lock(1906, hashtext(?))")) {
                    sperren.setString(1, a.wert());
                    sperren.execute();
                    assertThat(sperre.belegen(a, alt)).isFalse();
                } finally {
                    verbindung.rollback();
                    verbindung.setAutoCommit(true);
                }
                assertThat(sperre.belegen(a, alt)).isTrue();
                assertThat(sperre.freigeben(a, alt)).isTrue();
            } finally {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.verwaltung_vorgang where mandant in (?, ?)")) {
                    loeschen.setString(1, a.wert());
                    loeschen.setString(2, b.wert());
                    loeschen.executeUpdate();
                }
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.mandant where code in (?, ?)")) {
                    loeschen.setString(1, a.wert());
                    loeschen.setString(2, b.wert());
                    loeschen.executeUpdate();
                }
            }
        }
    }
}
