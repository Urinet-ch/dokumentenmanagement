package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.VerwaltungVorgang;
import ch.urinet.archiv.benutzer.application.Vorgangssperre;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class VerwaltungVorgangTest {

    @Inject VerwaltungVorgang vorgang;
    @Inject Vorgangssperre sperre;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenquelle;

    @Test
    void absichtIstVorWirkungFestgeschriebenUndFehlerBleibenGesperrt() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        var absicht = eintrag(id, ziel, akteur, "INHABER", ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        var abschluss = eintrag(id, ziel, akteur, "INHABER", ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
        try (var thread = Executors.newSingleThreadExecutor()) {
            AtomicBoolean ausgefuehrt = new AtomicBoolean();
            var falsch = eintrag(UUID.randomUUID(), ziel, akteur, "UNGUELTIG",
                    ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, falsch, () -> { }, () -> {
                ausgefuehrt.set(true);
                return "ok";
            }, fehler -> { }, wert -> { }, wert -> abschluss)))
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(sperre.belegen(mandant, falsch.details().verwaltung().vorgangId())).isTrue();
            assertThat(sperre.freigeben(mandant, falsch.details().verwaltung().vorgangId())).isTrue();

            assertThatThrownBy(() -> in(thread, "vhf-gsk", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> "ok", fehler -> { }, wert -> { }, wert -> abschluss)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { throw new IllegalStateException("synthetische Prüfung"); }, () -> {
                        ausgefuehrt.set(true);
                        return "ok";
                    }, fehler -> { }, wert -> { }, wert -> abschluss)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(phasen(id)).isEmpty();
            assertThat(in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht, () -> { }, () -> {
                assertThat(QuarkusTransaction.isActive()).isFalse();
                assertThat(phasen(id)).containsExactly("ANGEFRAGT");
                return "ok";
            }, fehler -> { }, wert -> assertThat(QuarkusTransaction.isActive()).isTrue(),
                    wert -> abschluss))).isEqualTo("ok");
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "ABGESCHLOSSEN");
            UUID konfliktId = UUID.randomUUID();
            var konflikt = eintrag(konfliktId, ziel, akteur, "INHABER",
                    ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
            AtomicBoolean konfliktKompensiert = new AtomicBoolean();
            try {
                assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, konflikt, () -> { },
                        () -> { throw new IdentitaetsDienst.KonfliktException(); },
                        fehler -> konfliktKompensiert.set(true), wert -> { }, wert -> abschluss)))
                        .hasRootCauseInstanceOf(IdentitaetsDienst.KonfliktException.class);
                assertThat(konfliktKompensiert).isTrue();
                assertThat(phasen(konfliktId)).containsExactly("ANGEFRAGT", "UNKLAR");
                assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
            } finally {
                testsperreEntfernen(mandant, konfliktId);
            }

            UUID fehlerId = UUID.randomUUID();
            var fehlerAbsicht = eintrag(fehlerId, ziel, akteur, "INHABER",
                    ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
            AtomicBoolean kompensiert = new AtomicBoolean();
            try {
                assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, fehlerAbsicht, () -> { },
                        () -> { throw new IllegalStateException("synthetischer Fehler"); },
                        fehler -> kompensiert.set(true), wert -> { }, wert -> abschluss)))
                        .hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(kompensiert).isTrue();
                assertThat(phasen(fehlerId)).containsExactly("ANGEFRAGT", "UNKLAR");
                assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
            } finally {
                testsperreEntfernen(mandant, fehlerId);
            }
        }
    }

    private void testsperreEntfernen(MandantCode mandant, UUID id) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement entfernen = verbindung.prepareStatement(
                     "delete from public.verwaltung_vorgang where mandant = ? and vorgang_id = ?")) {
            entfernen.setString(1, mandant.wert());
            entfernen.setObject(2, id);
            entfernen.executeUpdate();
        }
    }

    private static ZugriffEintrag eintrag(UUID id, UUID ziel, String akteur, String rolle,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.SPERREN, null, null, List.of());
        return new ZugriffEintrag(Optional.of(akteur), Optional.of(rolle), ProtokollAktion.BENUTZER_AENDERN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }

    private List<String> phasen(UUID id) {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details->>'phase' from m_urinet.zugriff_protokoll "
                             + "where details->>'vorgangId' = ? order by id")) {
            abfrage.setString(1, id.toString());
            try (ResultSet zeilen = abfrage.executeQuery()) {
                List<String> phasen = new ArrayList<>();
                while (zeilen.next()) {
                    phasen.add(zeilen.getString(1));
                }
                return phasen;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Testprotokoll nicht lesbar", e);
        }
    }

    private void mandantenVorbereiten() throws Exception {
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
    }

    private <T> T in(java.util.concurrent.ExecutorService thread, String code, Supplier<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(code), arbeit)).get(30, TimeUnit.SECONDS);
    }
}
