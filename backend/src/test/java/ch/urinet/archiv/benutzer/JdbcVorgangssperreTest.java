package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.Vorgangssperre;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.VorgangUeberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcVorgangssperreTest {

    @Inject
    AgroalDataSource datenquelle;

    @Inject
    Vorgangssperre sperre;

    @Inject
    VorgangUeberwachung ueberwachung;

    @Test
    void belegungWartetNichtAufGesperrteVorgangszeile() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var mandant = new MandantCode("sperre-c-" + suffix);
        UUID alt = UUID.randomUUID();
        UUID neu = UUID.randomUUID();
        try (Connection verbindung = datenquelle.getConnection()) {
            try (PreparedStatement anlegen = verbindung.prepareStatement(
                    "insert into public.mandant (code, name, schema_name, bucket) values (?, ?, ?, ?)")) {
                anlegen.setString(1, mandant.wert());
                anlegen.setString(2, mandant.wert());
                anlegen.setString(3, mandant.schema());
                anlegen.setString(4, mandant.wert());
                anlegen.executeUpdate();
            }
            try {
                assertThat(sperre.belegen(mandant, alt)).isTrue();
                try (var thread = Executors.newSingleThreadExecutor()) {
                    verbindung.setAutoCommit(false);
                    try (PreparedStatement sperren = verbindung.prepareStatement(
                            "select 1 from public.verwaltung_vorgang where mandant = ? for update")) {
                        sperren.setString(1, mandant.wert());
                        sperren.executeQuery().close();
                        var versuch = thread.submit(() -> sperre.belegen(mandant, neu));
                        assertThat(versuch.get(1, TimeUnit.SECONDS)).isFalse();
                    } finally {
                        verbindung.rollback();
                        verbindung.setAutoCommit(true);
                    }
                }
                assertThat(sperre.freigeben(mandant, alt)).isTrue();
            } finally {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.verwaltung_vorgang where mandant = ?")) {
                    loeschen.setString(1, mandant.wert());
                    loeschen.executeUpdate();
                }
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.mandant where code = ?")) {
                    loeschen.setString(1, mandant.wert());
                    loeschen.executeUpdate();
                }
            }
        }
    }

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
                assertThat(ueberwachung.ungeklaert(a)).isEmpty();
                assertThat(sperre.freigeben(a, neu)).isFalse();
                assertThat(sperre.gueltig(a, alt)).isTrue();
                try (PreparedStatement ablaufen = verbindung.prepareStatement(
                        "update public.verwaltung_vorgang set bis = clock_timestamp() - interval '1 second' "
                                + "where mandant = ?")) {
                    ablaufen.setString(1, a.wert());
                    ablaufen.executeUpdate();
                }
                assertThat(sperre.gueltig(a, alt)).isFalse();
                assertThat(QuarkusTransaction.requiringNew().call(() -> sperre.anfragen(a, alt))).isFalse();
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

                assertThat(sperre.belegen(a, alt)).isTrue();
                assertThatThrownBy(() -> sperre.anfragen(a, alt)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                    assertThat(sperre.anfragen(a, alt)).isTrue();
                    throw new IllegalStateException("synthetischer Fehler");
                })).isInstanceOf(IllegalStateException.class);
                assertThat(sperre.freigeben(a, alt)).isTrue();

                assertThat(sperre.belegen(a, alt)).isTrue();
                assertThat(QuarkusTransaction.requiringNew().call(() -> sperre.anfragen(a, alt))).isTrue();
                try (PreparedStatement ablaufen = verbindung.prepareStatement(
                        "update public.verwaltung_vorgang set bis = clock_timestamp() - interval '1 second' "
                                + "where mandant = ?")) {
                    ablaufen.setString(1, a.wert());
                    ablaufen.executeUpdate();
                }
                assertThat(sperre.belegen(a, neu)).isFalse();
                assertThat(sperre.freigeben(a, alt)).isFalse();
                assertThat(ueberwachung.ungeklaert(a)).containsExactly(
                        new Befund(Befund.Quelle.VERWALTUNG, alt.toString(), "UNKLAR"));
                assertThat(ueberwachung.ungeklaert(b)).isEmpty();
                assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                    assertThat(sperre.abschliessen(a, alt, Vorgangssperre.Abschluss.UNKLAR)).isTrue();
                    throw new IllegalStateException("synthetischer Fehler");
                })).isInstanceOf(IllegalStateException.class);
                assertThat(sperre.belegen(a, neu)).isFalse();
                assertThat(QuarkusTransaction.requiringNew().call(() ->
                        sperre.abschliessen(a, neu, Vorgangssperre.Abschluss.ABGESCHLOSSEN))).isFalse();
                assertThat(QuarkusTransaction.requiringNew().call(() ->
                        sperre.abschliessen(a, alt, Vorgangssperre.Abschluss.ABGESCHLOSSEN))).isFalse();
                assertThat(QuarkusTransaction.requiringNew().call(() ->
                        sperre.abschliessen(a, alt, Vorgangssperre.Abschluss.UNKLAR))).isTrue();
                assertThat(sperre.belegen(a, neu)).isFalse();
                try (PreparedStatement entfernen = verbindung.prepareStatement(
                        "delete from public.verwaltung_vorgang where mandant = ? and vorgang_id = ?")) {
                    entfernen.setString(1, a.wert());
                    entfernen.setObject(2, alt);
                    entfernen.executeUpdate();
                }
                assertThat(sperre.belegen(a, neu)).isTrue();
                assertThat(QuarkusTransaction.requiringNew().call(() -> sperre.anfragen(a, neu))).isTrue();
                assertThat(QuarkusTransaction.requiringNew().call(() ->
                        sperre.abschliessen(a, neu, Vorgangssperre.Abschluss.UNKLAR))).isTrue();
                try (PreparedStatement ablaufen = verbindung.prepareStatement(
                        "update public.verwaltung_vorgang set bis = clock_timestamp() - interval '1 second' "
                                + "where mandant = ?")) {
                    ablaufen.setString(1, a.wert());
                    ablaufen.executeUpdate();
                }
                assertThat(sperre.freigeben(a, neu)).isFalse();
                assertThat(sperre.belegen(a, alt)).isFalse();
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
