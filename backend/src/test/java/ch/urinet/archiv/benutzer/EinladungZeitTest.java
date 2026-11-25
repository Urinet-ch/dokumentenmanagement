package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.adapter.out.persistence.JdbcVerwaltungGrenzen;
import ch.urinet.archiv.benutzer.application.BenutzerAktivitaet;
import ch.urinet.archiv.benutzer.application.BenutzerAnlegen;
import ch.urinet.archiv.benutzer.application.BenutzerEinladen;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.BenutzerStatusAendern;
import ch.urinet.archiv.benutzer.application.BenutzerStatusSpeicher;
import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.benutzer.adapter.out.persistence.NamenssperreHash;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadKonfigurationSeed;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsgrund;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class EinladungZeitTest {
    @Inject AgroalDataSource datenquelle;
    @Inject MandantProvisionierung provisionierung;
    @Inject KonfigurationSeed seed;
    @Inject KonfigurationsdateiCodec codec;
    @Inject MandantKontext kontext;
    @Inject IdentitaetsDienst identitaet;
    @Inject BenutzerAnlegen anlegen;
    @Inject BenutzerStatusAendern statusAendern;
    @Inject BenutzerStatusSpeicher status;
    @Inject StepUp stepUp;
    @Inject BenutzerEinladen einladen;
    @Inject BenutzerAktivitaet aktivitaet;
    @Inject JdbcVerwaltungGrenzen verwaltungGrenzen;

    @Test
    @Tag("req-7.4")
    void ablaufWirdBeiAnlageReaktivierungUndErneutemVersandNurNachErfolgGespeichert() throws Exception {
        var mandant = new MandantCode("probe-einladung-" + UUID.randomUUID().toString().substring(0, 8));
        String benutzername = "probe-einladung-" + UUID.randomUUID().toString().substring(0, 8);
        KonfigurationSeed originalSeed = ClientProxy.unwrap(seed);
        IdentitaetsDienst originalIdentitaet = ClientProxy.unwrap(identitaet);
        StepUp originalStepUp = ClientProxy.unwrap(stepUp);
        JdbcVerwaltungGrenzen originalVerwaltungGrenzen = ClientProxy.unwrap(verwaltungGrenzen);
        AtomicInteger gesendet = new AtomicInteger();
        String[] benutzerId = {null};
        boolean identitaetGemockt = false;
        boolean stepUpGemockt = false;
        boolean verwaltungGrenzenGemockt = false;
        try {
            QuarkusMock.installMockForType(new KlassenpfadKonfigurationSeed(codec) {
                @Override
                public Konfiguration lesen(MandantCode ignoriert) {
                    return originalSeed.lesen(new MandantCode("urinet"));
                }
            }, KonfigurationSeed.class);
            try {
                provisionierung.anlegen(mandant.wert(), null, null);
            } finally {
                QuarkusMock.installMockForType(originalSeed, KonfigurationSeed.class);
            }
            var stub = new IdentitaetsDienstStub() {
                @Override
                public synchronized void einladen(String code, String id, Set<ErforderlicheAktion> aktionen,
                                                   Duration gueltigkeit) {
                    if (gesendet.incrementAndGet() == 4) {
                        throw new IllegalStateException("synthetischer Versandfehler");
                    }
                    super.einladen(code, id, aktionen, gueltigkeit);
                }
            };
            QuarkusMock.installMockForType(stub, IdentitaetsDienst.class);
            identitaetGemockt = true;
            QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
                @Override
                public void pruefen() { }
            }, StepUp.class);
            stepUpGemockt = true;
            QuarkusMock.installMockForType(new JdbcVerwaltungGrenzen(null, null, 1, 1, 1, 1) {
                @Override
                public void pruefen(String akteur, VerwaltungGrenzen.Art art, UUID ziel) { }
            }, JdbcVerwaltungGrenzen.class);
            verwaltungGrenzenGemockt = true;
            Benutzer akteur = new Benutzer(UUID.randomUUID().toString(), Rolle.INHABER);
            try (var thread = Executors.newSingleThreadExecutor()) {
                benutzerId[0] = thread.submit(() -> kontext.mit(mandant, () ->
                        anlegen.erstenInhaberEinladen(mandant, "einladung-test",
                                new BenutzerAnlegen.BenutzerAnlage(benutzername, benutzername + "@example.invalid",
                                        "Test", "Person", "inhaber")).toString()))
                        .get(30, TimeUnit.SECONDS);
                var angelegt = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(benutzerId[0])))
                        .get(30, TimeUnit.SECONDS);
                var ersterAblauf = ablauf(mandant, benutzerId[0]);
                assertThat(angelegt.einladungSeit()).isNotNull();
                assertThat(angelegt.einladungGueltigBis()).isEqualTo(ersterAblauf.gueltigBis());
                assertThat(Duration.between(ersterAblauf.versendetAm(), ersterAblauf.gueltigBis()))
                        .isEqualTo(Duration.ofHours(24));

                thread.submit(() -> kontext.mit(mandant, () -> {
                    stub.aktivieren(mandant.wert(), benutzerId[0], false);
                    QuarkusTransaction.requiringNew().run(() -> status.setzen(benutzerId[0],
                            new BenutzerStatusSpeicher.Eintrag(BenutzerStatus.DEAKTIVIERT, Instant.now(),
                                    Verwaltungsgrund.AUSTRITT, akteur.id())));
                })).get(30, TimeUnit.SECONDS);
                thread.submit(() -> kontext.mit(mandant, () -> statusAendern.ausfuehren(mandant, akteur,
                        benutzerId[0], BenutzerStatusAendern.Vorgang.REAKTIVIEREN, null, Optional.empty())))
                        .get(30, TimeUnit.SECONDS);
                var reaktiviert = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(benutzerId[0])))
                        .get(30, TimeUnit.SECONDS);
                assertThat(reaktiviert.einladungSeit()).isAfter(angelegt.einladungSeit());
                assertThat(reaktiviert.einladungGueltigBis()).isAfter(angelegt.einladungGueltigBis());

                thread.submit(() -> kontext.mit(mandant, () ->
                        einladen.erneut(mandant, akteur, benutzerId[0], Optional.empty())))
                        .get(30, TimeUnit.SECONDS);
                var erneut = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(benutzerId[0])))
                        .get(30, TimeUnit.SECONDS);
                assertThat(erneut.einladungSeit()).isAfter(reaktiviert.einladungSeit());
                assertThat(erneut.einladungGueltigBis()).isAfter(reaktiviert.einladungGueltigBis());

                assertThatThrownBy(() -> thread.submit(
                        () -> kontext.mit(mandant, () ->
                                einladen.erneut(mandant, akteur, benutzerId[0], Optional.empty())))
                        .get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                var nachFehler = thread.submit(() -> kontext.mit(mandant, () ->
                                aktivitaet.lesen(benutzerId[0])))
                        .get(30, TimeUnit.SECONDS);
                assertThat(nachFehler.einladungSeit()).isEqualTo(erneut.einladungSeit());
                assertThat(nachFehler.einladungGueltigBis()).isEqualTo(erneut.einladungGueltigBis());

                String altbestand = stub.anlegen(mandant.wert(), new IdentitaetsDienst.Neuanlage(
                        "altbestand-" + benutzername, "altbestand@example.invalid", "Alt", "Bestand"),
                        Rolle.MITARBEITER);
                var unbekannt = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(altbestand)))
                        .get(30, TimeUnit.SECONDS);
                assertThat(unbekannt.einladungGueltigBis()).isNull();
            }
        } finally {
            QuarkusMock.installMockForType(originalSeed, KonfigurationSeed.class);
            if (identitaetGemockt) {
                QuarkusMock.installMockForType(originalIdentitaet, IdentitaetsDienst.class);
            }
            if (stepUpGemockt) {
                QuarkusMock.installMockForType(originalStepUp, StepUp.class);
            }
            if (verwaltungGrenzenGemockt) {
                QuarkusMock.installMockForType(originalVerwaltungGrenzen, JdbcVerwaltungGrenzen.class);
            }
            mandantAufraeumen(mandant, benutzername);
        }
    }

    private record Ablauf(Instant versendetAm, Instant gueltigBis) {
    }

    private Ablauf ablauf(MandantCode mandant, String benutzer) throws SQLException {
        String sql = "select versendet_am, gueltig_bis from " + mandant.schema()
                + ".benutzer_einladung where benutzer = ?";
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, benutzer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return new Ablauf(zeile.getObject("versendet_am", OffsetDateTime.class).toInstant(),
                        zeile.getObject("gueltig_bis", OffsetDateTime.class).toInstant());
            }
        }
    }

    private void mandantAufraeumen(MandantCode mandant, String benutzername) throws SQLException {
        try (Connection verbindung = datenquelle.getConnection()) {
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "delete from public.verwaltung_vorgang where mandant = ?")) {
                loeschen.setString(1, mandant.wert());
                loeschen.executeUpdate();
            }
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("drop schema if exists " + mandant.schema() + " cascade");
            }
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "delete from public.mandant where code = ?")) {
                loeschen.setString(1, mandant.wert());
                loeschen.executeUpdate();
            }
            NamenssperreHash.ausKonfiguration().ifPresent(hash -> {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.benutzername_sperre where hash = ?")) {
                    loeschen.setBytes(1, hash.hash(benutzername));
                    loeschen.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException("Proben-Namenssperre nicht entfernt", e);
                }
            });
        }
    }
}
