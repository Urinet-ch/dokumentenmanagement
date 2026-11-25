package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.adapter.out.persistence.JdbcVerwaltungGrenzen;
import ch.urinet.archiv.benutzer.application.BenutzerAktivitaet;
import ch.urinet.archiv.benutzer.application.BenutzerAnlegen;
import ch.urinet.archiv.benutzer.application.BenutzerAendern;
import ch.urinet.archiv.benutzer.application.BenutzerEtag;
import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.benutzer.adapter.out.mail.MailBenutzerMitteilung;
import io.quarkus.mailer.Mailer;
import ch.urinet.archiv.benutzer.application.BenutzerZugangZuruecksetzen;
import ch.urinet.archiv.benutzer.adapter.out.persistence.JdbcBenutzerAktivitaet;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
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
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class EinladungVersandpfadeTest {
    @Inject AgroalDataSource datenquelle;
    @Inject MandantProvisionierung provisionierung;
    @Inject KonfigurationSeed seed;
    @Inject KonfigurationsdateiCodec codec;
    @Inject MandantKontext kontext;
    @Inject IdentitaetsDienst identitaet;
    @Inject BenutzerAnlegen anlegen;
    @Inject BenutzerAendern aendern;
    @Inject BenutzerZugangZuruecksetzen zuruecksetzen;
    @Inject StepUp stepUp;
    @Inject MailBenutzerMitteilung mitteilung;
    @Inject JdbcBenutzerAktivitaet aktivitaet;
    @Inject JdbcVerwaltungGrenzen verwaltungGrenzen;

    private interface Probe {
        void lauf(MandantCode mandant, String benutzer, Benutzer akteur, AtomicBoolean versandFehler,
                  AtomicBoolean speicherFehler, ExecutorService thread) throws Exception;
    }

    @Test
    @Tag("req-7.4")
    void zugangZuruecksetzenSchreibtAblaufNurNachErfolgreichemVersand() throws Exception {
        mitProbe((mandant, benutzer, akteur, versandFehler, speicherFehler, thread) -> {
            var vorher = ablauf(mandant, benutzer);
            Instant vorAufruf = Instant.now();
            thread.submit(() -> kontext.mit(mandant, () ->
                    zuruecksetzen.ausfuehren(mandant, akteur, benutzer, false, Optional.empty())))
                    .get(30, TimeUnit.SECONDS);
            var nachErfolg = ablauf(mandant, benutzer);
            pruefeNeuerAblauf(vorher, nachErfolg, vorAufruf);

            versandFehler.set(true);
            assertThatThrownBy(() -> thread.submit(() -> kontext.mit(mandant, () ->
                    zuruecksetzen.ausfuehren(mandant, akteur, benutzer, false, Optional.empty())))
                    .get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(ablauf(mandant, benutzer)).isEqualTo(nachErfolg);
        });
    }

    @Test
    @Tag("req-7.4")
    void emailAenderungSchreibtAblaufNurNachErfolgreichemVersand() throws Exception {
        mitProbe((mandant, benutzer, akteur, versandFehler, speicherFehler, thread) -> {
            var vorher = ablauf(mandant, benutzer);
            Instant vorAufruf = Instant.now();
            String tag = etag(mandant, thread, benutzer);
            thread.submit(() -> kontext.mit(mandant, () ->
                    aendern.ausfuehren(mandant, akteur, benutzer, tag,
                            "neu1-" + benutzer + "@example.invalid", null, null, Optional.empty())))
                    .get(30, TimeUnit.SECONDS);
            var nachErfolg = ablauf(mandant, benutzer);
            pruefeNeuerAblauf(vorher, nachErfolg, vorAufruf);

            versandFehler.set(true);
            String tag2 = etag(mandant, thread, benutzer);
            assertThatThrownBy(() -> thread.submit(() -> kontext.mit(mandant, () ->
                    aendern.ausfuehren(mandant, akteur, benutzer, tag2,
                            "neu2-" + benutzer + "@example.invalid", null, null, Optional.empty())))
                    .get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(ablauf(mandant, benutzer)).isEqualTo(nachErfolg);
        });
    }

    @Test
    @Tag("req-7.4")
    void emailAenderungOhneEmailSchreibtKeinenAblauf() throws Exception {
        mitProbe((mandant, benutzer, akteur, versandFehler, speicherFehler, thread) -> {
            var vorher = ablauf(mandant, benutzer);
            String tag = etag(mandant, thread, benutzer);
            thread.submit(() -> kontext.mit(mandant, () ->
                    aendern.ausfuehren(mandant, akteur, benutzer, tag,
                            null, "Neuer", null, Optional.empty())))
                    .get(30, TimeUnit.SECONDS);
            assertThat(ablauf(mandant, benutzer)).isEqualTo(vorher);
        });
    }

    @Test
    @Tag("req-7.4")
    void versandOkSpeichernFehlschlagLaesstAltenEintragUndMeldetFehler() throws Exception {
        mitProbe((mandant, benutzer, akteur, versandFehler, speicherFehler, thread) -> {
            var vorher = ablauf(mandant, benutzer);
            speicherFehler.set(true);
            assertThatThrownBy(() -> thread.submit(() -> kontext.mit(mandant, () ->
                    zuruecksetzen.ausfuehren(mandant, akteur, benutzer, false, Optional.empty())))
                    .get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Einladungsablauf nicht gespeichert")
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(benutzer));
            assertThat(ablauf(mandant, benutzer)).isEqualTo(vorher);
            assertThat(vorgangPhasen(mandant)).contains("UNKLAR");
        });
    }

    @Test
    @Tag("req-7.4")
    void emailAenderungVersandOkSpeichernFehlschlagLaesstAltenEintragUndMeldetFehler() throws Exception {
        mitProbe((mandant, benutzer, akteur, versandFehler, speicherFehler, thread) -> {
            var vorher = ablauf(mandant, benutzer);
            speicherFehler.set(true);
            String tag = etag(mandant, thread, benutzer);
            assertThatThrownBy(() -> thread.submit(() -> kontext.mit(mandant, () ->
                    aendern.ausfuehren(mandant, akteur, benutzer, tag,
                            "neu-" + benutzer + "@example.invalid", null, null, Optional.empty())))
                    .get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Einladungsablauf nicht gespeichert")
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(benutzer));
            assertThat(ablauf(mandant, benutzer)).isEqualTo(vorher);
            assertThat(vorgangPhasen(mandant)).contains("UNKLAR");
        });
    }

    private static void pruefeNeuerAblauf(Ablauf vorher, Ablauf nachher, Instant vorAufruf) {
        assertThat(nachher.versendetAm()).isAfter(vorher.versendetAm());
        assertThat(nachher.versendetAm()).isAfterOrEqualTo(vorAufruf.minusMillis(1));
        assertThat(Duration.between(nachher.versendetAm(), nachher.gueltigBis())).isEqualTo(Duration.ofHours(24));
        assertThat(nachher.gueltigBis()).isAfter(vorher.gueltigBis());
    }

    private String etag(MandantCode mandant, ExecutorService thread, String benutzer) {
        try {
            return "\"" + thread.submit(() -> kontext.mit(mandant, () -> {
                var b = identitaet.benutzerLesen(mandant.wert(), benutzer);
                return BenutzerEtag.berechnen(b, BenutzerStatus.EINGELADEN,
                        aktivitaet.lesen(benutzer).einladungGueltigBis());
            }))
                    .get(30, TimeUnit.SECONDS) + "\"";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void mitProbe(Probe probe) throws Exception {
        var mandant = new MandantCode("probe-versand-" + UUID.randomUUID().toString().substring(0, 8));
        String benutzername = "probe-versand-" + UUID.randomUUID().toString().substring(0, 8);
        KonfigurationSeed originalSeed = ClientProxy.unwrap(seed);
        IdentitaetsDienst originalIdentitaet = ClientProxy.unwrap(identitaet);
        StepUp originalStepUp = ClientProxy.unwrap(stepUp);
        JdbcVerwaltungGrenzen originalGrenzen = ClientProxy.unwrap(verwaltungGrenzen);
        MailBenutzerMitteilung originalMitteilung = ClientProxy.unwrap(mitteilung);
        JdbcBenutzerAktivitaet originalAktivitaet = ClientProxy.unwrap(aktivitaet);
        AtomicBoolean versandFehler = new AtomicBoolean();
        AtomicBoolean speicherFehler = new AtomicBoolean();
        boolean gemockt = false;
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
            QuarkusMock.installMockForType(new IdentitaetsDienstStub() {
                @Override
                public synchronized void einladen(String code, String id, Set<ErforderlicheAktion> aktionen,
                                                   Duration gueltigkeit) {
                    if (versandFehler.get()) {
                        throw new IllegalStateException("synthetischer Versandfehler");
                    }
                    super.einladen(code, id, aktionen, gueltigkeit);
                }
            }, IdentitaetsDienst.class);
            QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
                @Override
                public void pruefen() { }
            }, StepUp.class);
            QuarkusMock.installMockForType(new JdbcVerwaltungGrenzen(null, null, 1, 1, 1, 1) {
                @Override
                public void pruefen(String akteur, VerwaltungGrenzen.Art art, UUID ziel) { }
            }, JdbcVerwaltungGrenzen.class);
            QuarkusMock.installMockForType(new JdbcBenutzerAktivitaet(null, null) {
                @Override
                public Angaben lesen(String benutzer) {
                    return originalAktivitaet.lesen(benutzer);
                }

                @Override
                public void speichern(String benutzer, Instant versendetAm, Duration gueltigkeit) {
                    if (speicherFehler.get()) {
                        throw new IllegalStateException("Einladungsablauf nicht gespeichert");
                    }
                    originalAktivitaet.speichern(benutzer, versendetAm, gueltigkeit);
                }
            }, JdbcBenutzerAktivitaet.class);
            QuarkusMock.installMockForType(new MailBenutzerMitteilung((Mailer) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Mailer.class}, (proxy, methode, args) -> null)) {
                @Override
                public void emailGeaendert(String bisherigeAdresse) { }
            }, MailBenutzerMitteilung.class);
            gemockt = true;
            Benutzer akteur = new Benutzer(UUID.randomUUID().toString(), Rolle.INHABER);
            try (var thread = Executors.newSingleThreadExecutor()) {
                String benutzer = thread.submit(() -> kontext.mit(mandant, () ->
                        anlegen.erstenInhaberEinladen(mandant, "einladung-test",
                                new BenutzerAnlegen.BenutzerAnlage(benutzername, benutzername + "@example.invalid",
                                        "Test", "Person", "inhaber")).toString()))
                        .get(30, TimeUnit.SECONDS);
                probe.lauf(mandant, benutzer, akteur, versandFehler, speicherFehler, thread);
            }
        } finally {
            QuarkusMock.installMockForType(originalSeed, KonfigurationSeed.class);
            if (gemockt) {
                QuarkusMock.installMockForType(originalIdentitaet, IdentitaetsDienst.class);
                QuarkusMock.installMockForType(originalStepUp, StepUp.class);
                QuarkusMock.installMockForType(originalGrenzen, JdbcVerwaltungGrenzen.class);
                QuarkusMock.installMockForType(originalAktivitaet, JdbcBenutzerAktivitaet.class);
                QuarkusMock.installMockForType(originalMitteilung, MailBenutzerMitteilung.class);
            }
            mandantAufraeumen(mandant, benutzername);
        }
    }

    private record Ablauf(Instant versendetAm, Instant gueltigBis) {
    }

    private java.util.List<String> vorgangPhasen(MandantCode mandant) throws SQLException {
        String sql = "select details->>'phase' from " + mandant.schema() + ".zugriff_protokoll";
        var phasen = new java.util.ArrayList<String>();
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql);
             ResultSet zeile = abfrage.executeQuery()) {
            while (zeile.next()) {
                phasen.add(zeile.getString(1));
            }
        }
        return phasen;
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
