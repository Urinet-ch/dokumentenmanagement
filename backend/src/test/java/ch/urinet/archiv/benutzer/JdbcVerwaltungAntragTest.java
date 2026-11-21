package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Antrag;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Art;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Status;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcVerwaltungAntragTest {

    @Inject VerwaltungAntragSpeicher speicher;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenquelle;

    @Test
    void antraegeSindGetrenntUndEntscheideAtomar() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, code);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
        Instant zeit = Instant.parse("2026-01-01T10:00:00Z");
        var antrag = new Antrag(UUID.randomUUID(), Art.INHABER_ROLLE, "ziel", Rolle.INHABER, null,
                "antragsteller", zeit, zeit.plusSeconds(60), Status.OFFEN, null, null);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> thread.submit(() -> speicher.lesen(antrag.id())).get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            in(thread, "urinet", () -> {
                speicher.anlegen(antrag);
                return null;
            });
            assertThat(in(thread, "urinet", () -> speicher.lesen(antrag.id()))).contains(antrag);
            assertThat(in(thread, "vhf-gsk", () -> speicher.lesen(antrag.id()))).isEmpty();
            assertThat(in(thread, "urinet", () -> speicher.offene(zeit))).contains(antrag);
            assertThat(in(thread, "vhf-gsk", () -> speicher.offene(zeit))).doesNotContain(antrag);
            assertThat(in(thread, "urinet", () -> speicher.offene(zeit.plusSeconds(60)))).doesNotContain(antrag);
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "antragsteller", zeit))).isFalse();
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit.minusSeconds(1)))).isFalse();
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit.plusSeconds(60)))).isFalse();
            assertThatThrownBy(() -> in(thread, "urinet", () -> {
                assertThat(speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit)).isTrue();
                throw new IllegalStateException("synthetischer Fehler");
            })).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(in(thread, "urinet", () -> speicher.lesen(antrag.id()))).contains(antrag);
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.ABGELAUFEN, null, zeit.plusSeconds(60)))).isTrue();
            assertThat(in(thread, "urinet", () -> speicher.offene(zeit))).doesNotContain(antrag);
            assertThat(in(thread, "urinet", () ->
                    speicher.entscheiden(antrag.id(), Status.BESTAETIGT, "anderer", zeit))).isFalse();
            for (String code : List.of("urinet", "vhf-gsk")) {
                var ungueltig = new Antrag(UUID.randomUUID(), Art.INHABER_ROLLE, "ziel", Rolle.INHABER,
                        "synthetischer Freitext", "antragsteller", zeit, zeit.plusSeconds(60), Status.OFFEN, null, null);
                assertThatThrownBy(() -> in(thread, code, () -> {
                    speicher.anlegen(ungueltig);
                    return null;
                })).hasRootCauseInstanceOf(KonfigurationUngueltig.class);
                assertThat(in(thread, code, () -> speicher.lesen(ungueltig.id()))).isEmpty();
                var gueltig = new Antrag(UUID.randomUUID(), Art.INHABER_ROLLE, "ziel", Rolle.INHABER,
                        "SICHERHEIT", "antragsteller", zeit, zeit.plusSeconds(60), Status.OFFEN, null, null);
                in(thread, code, () -> {
                    speicher.anlegen(gueltig);
                    return null;
                });
                assertThat(in(thread, code, () -> speicher.lesen(gueltig.id()))).contains(gueltig);
            }
            var alt = new Antrag(UUID.randomUUID(), Art.INHABER_ROLLE, "ziel", Rolle.INHABER,
                    "synthetischer Alttext", "antragsteller", zeit, zeit.plusSeconds(60), Status.OFFEN, null, null);
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement einfuegen = verbindung.prepareStatement(
                         "insert into m_urinet.verwaltung_antrag "
                                 + "(id, art, ziel, rolle, grund, beantragt_von, beantragt_am, gueltig_bis, status) "
                                 + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                einfuegen.setObject(1, alt.id());
                einfuegen.setString(2, alt.art().name());
                einfuegen.setString(3, alt.ziel());
                einfuegen.setString(4, alt.rolle().code());
                einfuegen.setString(5, alt.grund());
                einfuegen.setString(6, alt.beantragtVon());
                einfuegen.setObject(7, alt.beantragtAm().atOffset(java.time.ZoneOffset.UTC));
                einfuegen.setObject(8, alt.gueltigBis().atOffset(java.time.ZoneOffset.UTC));
                einfuegen.setString(9, alt.status().name());
                einfuegen.executeUpdate();
            }
            assertThat(in(thread, "urinet", () -> speicher.lesen(alt.id()))).contains(alt);
        }
    }

    private <T> T in(java.util.concurrent.ExecutorService thread, String code, Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(code), () ->
                QuarkusTransaction.requiringNew().call(arbeit))).get(30, TimeUnit.SECONDS);
    }
}
