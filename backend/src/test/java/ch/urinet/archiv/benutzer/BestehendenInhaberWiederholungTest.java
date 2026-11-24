package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.BestehendenInhaberDokumentieren;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.VerwaltungVorgang;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class BestehendenInhaberWiederholungTest {
    @Inject VerwaltungVorgang vorgang;
    @Inject BestehendenInhaberDokumentieren dokumentieren;
    @Inject IdentitaetsDienst identitaet;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject MandantRegister register;
    @Inject AgroalDataSource datenquelle;

    @Test
    void ungueltigeBetreiberkennungWirdVorDerArbeitAbgewiesen() {
        MandantCode mandant = new MandantCode("urinet");
        AtomicBoolean ausgefuehrt = new AtomicBoolean();
        for (String akteur : List.of("betreiber:", "betreiber:ungueltig\n", "andere:kennung")) {
            var absicht = eintrag(UUID.randomUUID(), UUID.randomUUID(), akteur);
            assertThatThrownBy(() -> vorgang.ausfuehren(mandant, absicht, () -> { },
                    () -> { ausgefuehrt.set(true); return true; }, fehler -> VerwaltungVorgang.Kompensation.UNKLAR,
                    wert -> { }, wert -> absicht)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ausgefuehrt).isFalse();
    }

    @Test
    void unklarerBetreibervorgangBlockiertWiederholung() throws Exception {
        MandantCode mandant = new MandantCode("urinet");
        if (!register.existiert(mandant)) {
            provisionierung.anlegen(mandant.wert(), null, null);
        }
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        var stub = new IdentitaetsDienstStub();
        String ziel = stub.anlegen(mandant.wert(),
                new IdentitaetsDienst.Neuanlage("inhaber-probe", "inhaber@example.invalid", "Test", "Person"),
                Rolle.INHABER);
        QuarkusMock.installMockForType(stub, IdentitaetsDienst.class);
        UUID vorgangId = UUID.randomUUID();
        UUID betreiber = UUID.randomUUID();
        var absicht = eintrag(vorgangId, UUID.fromString(ziel), "betreiber:" + betreiber);
        try (var thread = Executors.newSingleThreadExecutor()) {
            var erster = thread.submit(() -> kontext.mit(mandant, () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> { throw new IllegalStateException("synthetischer Abbruch"); },
                    fehler -> VerwaltungVorgang.Kompensation.UNKLAR, wert -> { }, wert -> absicht)));
            assertThatThrownBy(() -> erster.get(30, TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
            var wiederholung = thread.submit(() -> kontext.mit(mandant,
                    () -> dokumentieren.ausfuehren(mandant, UUID.fromString(ziel), betreiber, true)));
            assertThatThrownBy(() -> wiederholung.get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(FachFehler.class)
                    .rootCause().extracting(fehler -> ((FachFehler) fehler).code())
                    .isEqualTo(Fehlercode.STATUS_KONFLIKT);
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select phase from public.verwaltung_vorgang where mandant = ? and vorgang_id = ?")) {
                abfrage.setString(1, mandant.wert());
                abfrage.setObject(2, vorgangId);
                try (var zeilen = abfrage.executeQuery()) {
                    assertThat(zeilen.next()).isTrue();
                    assertThat(zeilen.getString(1)).isEqualTo("UNKLAR");
                }
            }
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement entfernen = verbindung.prepareStatement(
                         "delete from public.verwaltung_vorgang where mandant = ? and vorgang_id = ?")) {
                entfernen.setString(1, mandant.wert());
                entfernen.setObject(2, vorgangId);
                entfernen.executeUpdate();
            }
        }
    }

    private static ZugriffEintrag eintrag(UUID id, UUID ziel, String akteur) {
        var details = new ProtokollDetails.Verwaltung(id, ProtokollDetails.Verwaltungsphase.ANGEFRAGT, ziel,
                ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN,
                ProtokollDetails.Verwaltungsrolle.INHABER, ProtokollDetails.Verwaltungsrolle.INHABER,
                List.of(), null, null, ProtokollDetails.Verwaltungsanlass.UEBERNAHME, null);
        return new ZugriffEintrag(Optional.of(akteur), Optional.empty(), ProtokollAktion.ROLLE_ZUWEISEN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }
}
