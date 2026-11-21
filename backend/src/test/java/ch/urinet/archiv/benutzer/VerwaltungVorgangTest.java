package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.VerwaltungVorgang;
import ch.urinet.archiv.benutzer.application.Vorgangssperre;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
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
    @Inject ZugriffProtokoll protokoll;

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

    @Test
    void fehlerBeimAbschlussRolltDatenbankZurueckUndHaeltDieSperre() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        var absicht = eintrag(id, UUID.randomUUID(), UUID.randomUUID().toString(), "INHABER",
                ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> "ok",
                    fehler -> { throw new IllegalArgumentException("synthetische Kompensation"); },
                    wert -> {
                        protokoll.schreiben(absicht);
                        throw new IllegalStateException("synthetischer Abschluss");
                    }, wert -> absicht)))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .satisfies(fehler -> assertThat(fehler.getCause().getSuppressed())
                            .hasSize(1).satisfies(unterdrueckt ->
                                    assertThat(unterdrueckt[0]).isInstanceOf(IllegalArgumentException.class)));
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void protokollVerlangtZielUndGleicheAkteurrolle() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        var ohneZiel = eintrag(id, null, akteur, "INHABER", ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        var absicht = eintrag(id, ziel, akteur, "INHABER", ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        var ohneRolle = new ZugriffEintrag(absicht.benutzer(), Optional.empty(), absicht.aktion(),
                absicht.dokumentId(), absicht.ip(), absicht.details());
        var falscheRolle = eintrag(id, ziel, akteur, "MITARBEITER",
                ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
        AtomicBoolean ausgefuehrt = new AtomicBoolean();
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, ohneZiel,
                    () -> { }, () -> { ausgefuehrt.set(true); return "ok"; },
                    fehler -> { }, wert -> { }, wert -> falscheRolle)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(phasen(id)).isEmpty();
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, ohneRolle,
                    () -> { }, () -> { ausgefuehrt.set(true); return "ok"; },
                    fehler -> { }, wert -> { }, wert -> falscheRolle)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(phasen(id)).isEmpty();
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> "ok", fehler -> { }, wert -> { }, wert -> falscheRolle)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "UNKLAR");
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void ratengrenzenVerhindernExterneWirkungUndGebenVorbereiteteSperreFrei() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        String akteur = UUID.randomUUID().toString();
        UUID ziel = UUID.randomUUID();
        alteAbsichten(akteur, ziel, "SPERREN", 30);
        UUID anderesZiel = UUID.randomUUID();
        alteAbsichten(UUID.randomUUID().toString(), anderesZiel, "EINLADUNG_ERNEUT", 3);

        try (var thread = Executors.newSingleThreadExecutor()) {
            for (var fall : List.of(
                    eintrag(UUID.randomUUID(), ziel, akteur, "INHABER",
                            ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                    eintrag(UUID.randomUUID(), anderesZiel, UUID.randomUUID().toString(), "INHABER",
                            ProtokollDetails.Verwaltungsphase.ANGEFRAGT,
                            ProtokollDetails.Verwaltungsvorgang.EINLADUNG_ERNEUT))) {
                AtomicBoolean ausgefuehrt = new AtomicBoolean();
                UUID vorgangId = fall.details().verwaltung().vorgangId();
                assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, fall,
                        () -> { }, () -> { ausgefuehrt.set(true); return "ok"; },
                        fehler -> { }, wert -> { }, wert -> fall)))
                        .hasRootCauseInstanceOf(ZuVieleAnfragen.class);
                assertThat(ausgefuehrt).isFalse();
                assertThat(phasen(vorgangId)).isEmpty();
                assertThat(sperre.belegen(mandant, vorgangId)).isTrue();
                assertThat(sperre.freigeben(mandant, vorgangId)).isTrue();
            }
        }
    }

    private void alteAbsichten(String akteur, UUID ziel, String vorgang, int anzahl) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into m_urinet.zugriff_protokoll (zeit, benutzer, aktion, details) "
                             + "values (clock_timestamp(), ?, 'BENUTZER_AENDERN', "
                             + "jsonb_build_object('phase', 'ANGEFRAGT', 'vorgang', ?, 'ziel', ?))")) {
            einfuegen.setString(1, akteur);
            einfuegen.setString(2, vorgang);
            einfuegen.setString(3, ziel.toString());
            for (int i = 0; i < anzahl; i++) {
                einfuegen.executeUpdate();
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
        return eintrag(id, ziel, akteur, rolle, phase, ProtokollDetails.Verwaltungsvorgang.SPERREN);
    }

    private static ZugriffEintrag eintrag(UUID id, UUID ziel, String akteur, String rolle,
                                          ProtokollDetails.Verwaltungsphase phase,
                                          ProtokollDetails.Verwaltungsvorgang vorgang) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel, vorgang, null, null, List.of());
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
