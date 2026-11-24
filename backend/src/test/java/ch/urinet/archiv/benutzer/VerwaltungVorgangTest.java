package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.in.kommando.InhaberEinladenKommando;
import ch.urinet.archiv.benutzer.adapter.out.keycloak.KeycloakAdminAdapter;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.VerwaltungVorgang;
import ch.urinet.archiv.benutzer.application.VerwaltungVorgang.Kompensation;
import ch.urinet.archiv.benutzer.application.Vorgangssperre;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
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
    @Inject InhaberEinladenKommando inhaberEinladen;
    @Inject ch.urinet.archiv.benutzer.adapter.in.kommando.BestehendenInhaberKommando bestehendenInhaber;
    @Inject ch.urinet.archiv.benutzer.application.AbgleichStand abgleichStand;
    @Inject IdentitaetsDienst identitaet;
    @Inject MandantRegister register;

    @Test
    void betreibervorgangDokumentiertBestehendenInhaberEinmaligOhneKeycloakZuAendern() throws Exception {
        adminrollenMandantVorbereiten();
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        var stub = new IdentitaetsDienstStub();
        QuarkusMock.installMockForType(stub, IdentitaetsDienst.class);
        try {
            String ziel = stub.anlegen("probe-adminrollen",
                    new IdentitaetsDienst.Neuanlage("inhaber-probe", "probe@example.invalid", "Test", "Person"),
                    ch.urinet.archiv.sicherheit.domain.Rolle.INHABER);
            UUID betreiber = UUID.randomUUID();
            var ausgabe = new ByteArrayOutputStream();
            var fehler = new ByteArrayOutputStream();
            var aus = new PrintStream(ausgabe, true, StandardCharsets.UTF_8);
            var fehl = new PrintStream(fehler, true, StandardCharsets.UTF_8);
            var aufruf = new ArrayList<>(List.of("inhaber-uebernehmen", "probe-adminrollen", "--benutzer", ziel,
                    "--wer", betreiber.toString()));
            try (var thread = Executors.newSingleThreadExecutor()) {
                assertThat(in(thread, "probe-adminrollen", () -> bestehendenInhaber.ausfuehren(aufruf, aus, fehl)))
                        .as(fehler.toString(StandardCharsets.UTF_8)).isZero();
                assertThat(ausgabe.toString(StandardCharsets.UTF_8)).contains("GEPLANT");
                assertThat(abgleichStand.lesen(new MandantCode("probe-adminrollen")).dokumentierteInhaber())
                        .doesNotContain(UUID.fromString(ziel));
                aufruf.add("--ausfuehren");
                assertThat(in(thread, "probe-adminrollen", () -> bestehendenInhaber.ausfuehren(aufruf, aus, fehl)))
                        .as(fehler.toString(StandardCharsets.UTF_8)).isZero();
                assertThat(in(thread, "probe-adminrollen", () -> bestehendenInhaber.ausfuehren(aufruf, aus, fehl)))
                        .as(fehler.toString(StandardCharsets.UTF_8)).isZero();
                assertThat(ausgabe.toString(StandardCharsets.UTF_8)).contains("DOKUMENTIERT", "BEREITS_DOKUMENTIERT");
                assertThat(stub.benutzerLesen("probe-adminrollen", ziel).rollen())
                        .containsExactly(ch.urinet.archiv.sicherheit.domain.Rolle.INHABER);
                assertThat(abgleichStand.lesen(new MandantCode("probe-adminrollen")).dokumentierteInhaber())
                        .contains(UUID.fromString(ziel));
                try (Connection verbindung = datenquelle.getConnection();
                     PreparedStatement abfrage = verbindung.prepareStatement(
                             "select details->>'phase', details->>'anlass', details::text from "
                                     + "m_probe_adminrollen.zugriff_protokoll where aktion = 'ROLLE_ZUWEISEN' "
                                     + "and details->>'ziel' = ? order by id")) {
                    abfrage.setString(1, ziel);
                    try (ResultSet zeilen = abfrage.executeQuery()) {
                        assertThat(zeilen.next()).isTrue();
                        assertThat(zeilen.getString(1)).isEqualTo("ANGEFRAGT");
                        assertThat(zeilen.getString(2)).isEqualTo("UEBERNAHME");
                        assertThat(zeilen.getString(3)).doesNotContain("probe@example.invalid", "inhaber-probe");
                        assertThat(zeilen.next()).isTrue();
                        assertThat(zeilen.getString(1)).isEqualTo("ABGESCHLOSSEN");
                        assertThat(zeilen.getString(2)).isEqualTo("UEBERNAHME");
                        assertThat(zeilen.next()).isFalse();
                    }
                }
                aufruf.set(1, "vhf-gsk");
                assertThat(in(thread, "vhf-gsk", () -> bestehendenInhaber.ausfuehren(aufruf, aus, fehl)))
                        .isEqualTo(1);
                String mitarbeiter = stub.anlegen("probe-adminrollen",
                        new IdentitaetsDienst.Neuanlage("mitarbeiter-probe", "andere@example.invalid", "Test", "Person"),
                        ch.urinet.archiv.sicherheit.domain.Rolle.MITARBEITER);
                aufruf.set(1, "probe-adminrollen");
                aufruf.set(3, mitarbeiter);
                assertThat(in(thread, "probe-adminrollen", () -> bestehendenInhaber.ausfuehren(aufruf, aus, fehl)))
                        .isEqualTo(1);
                assertThat(stub.benutzerLesen("probe-adminrollen", mitarbeiter).rollen())
                        .containsExactly(ch.urinet.archiv.sicherheit.domain.Rolle.MITARBEITER);
            }
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }

    @Test
    void betreiberLaedtNurDenErstenInhaberEinUndProtokolliertDieAnlage() throws Exception {
        adminrollenMandantVorbereiten();
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        QuarkusMock.installMockForType(new IdentitaetsDienstStub(), IdentitaetsDienst.class);
        try {
            var ausgabe = new ByteArrayOutputStream();
            var fehler = new ByteArrayOutputStream();
            var aus = new PrintStream(ausgabe, true, StandardCharsets.UTF_8);
            var fehl = new PrintStream(fehler, true, StandardCharsets.UTF_8);
            String name = "probe." + UUID.randomUUID().toString().substring(0, 8);
            List<String> aufruf = List.of("inhaber-einladen", "probe-adminrollen", "--benutzer", name,
                    "--email", name + "@example.invalid", "--vorname", "Test", "--nachname", "Person",
                    "--wer", "test-betreiber");
            try (var thread = Executors.newSingleThreadExecutor()) {
                assertThat(in(thread, "probe-adminrollen", () -> inhaberEinladen.ausfuehren(aufruf, aus, fehl)))
                        .as(fehler.toString(StandardCharsets.UTF_8)).isZero();
                UUID id = UUID.fromString(ausgabe.toString(StandardCharsets.UTF_8).strip());
                assertThat(identitaet.benutzerLesen("probe-adminrollen", id.toString()).rollen())
                        .containsExactly(ch.urinet.archiv.sicherheit.domain.Rolle.INHABER);
                assertThatThrownBy(() -> identitaet.benutzerLesen("vhf-gsk", id.toString()))
                        .isInstanceOf(FachFehler.class);
                var zweiteEinladung = new ArrayList<>(aufruf);
                zweiteEinladung.set(3, "zweiter." + name);
                zweiteEinladung.set(5, "zweiter." + name + "@example.invalid");
                assertThat(in(thread, "probe-adminrollen",
                        () -> inhaberEinladen.ausfuehren(zweiteEinladung, aus, fehl))).isEqualTo(1);
                assertThat(fehler.toString(StandardCharsets.UTF_8)).contains("STATUS_KONFLIKT");
                assertThat(identitaet.benutzerZaehlen("probe-adminrollen")).isEqualTo(1);
                try (Connection verbindung = datenquelle.getConnection();
                     PreparedStatement abfrage = verbindung.prepareStatement(
                             "select details->>'phase', rolle from m_probe_adminrollen.zugriff_protokoll "
                                     + "where benutzer = 'betreiber:test-betreiber' and aktion = 'BENUTZER_ANLEGEN' "
                                     + "order by id")) {
                    try (ResultSet zeilen = abfrage.executeQuery()) {
                        assertThat(zeilen.next()).isTrue();
                        assertThat(zeilen.getString(1)).isEqualTo("ANGEFRAGT");
                        assertThat(zeilen.getString(2)).isNull();
                        assertThat(zeilen.next()).isTrue();
                        assertThat(zeilen.getString(1)).isEqualTo("ABGESCHLOSSEN");
                        assertThat(zeilen.getString(2)).isNull();
                        assertThat(zeilen.next()).isFalse();
                    }
                }
            }
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }

    @Test
    void betreibereinladungErreichtKeycloakUndMailpit() throws Exception {
        adminrollenMandantVorbereiten();
        String oidc = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        String basis = oidc.substring(0, oidc.indexOf("/realms/"));
        String admin = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", "admin").formParam("password", "admin")
                .post(basis + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
        String adminPfad = basis + "/admin/realms/urinet-archiv";
        String clientId = given().auth().oauth2(admin).queryParam("clientId", "archiv-verwaltung")
                .get(adminPfad + "/clients").then().statusCode(200).extract().path("[0].id");
        String secret = given().auth().oauth2(admin).get(adminPfad + "/clients/" + clientId + "/client-secret")
                .then().statusCode(200).extract().path("value");
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        var keycloak = new KeycloakAdminAdapter(new ObjectMapper(), Optional.of(basis), "urinet-archiv",
                "archiv-verwaltung", Optional.of(secret), "archiv-pwa", Optional.empty(),
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                status -> { throw new AssertionError("Unerwarteter Dienstkonto-Alarm: " + status); });
        String name = "probe." + UUID.randomUUID().toString().substring(0, 8);
        String email = name + "@example.invalid";
        QuarkusMock.installMockForType(identitaetsDienstMitKeycloak(keycloak), IdentitaetsDienst.class);
        try {
            var ausgabe = new ByteArrayOutputStream();
            var fehler = new ByteArrayOutputStream();
            var aus = new PrintStream(ausgabe, true, StandardCharsets.UTF_8);
            var fehl = new PrintStream(fehler, true, StandardCharsets.UTF_8);
            var aufruf = List.of("inhaber-einladen", "probe-adminrollen", "--benutzer", name,
                    "--email", email, "--vorname", "Test", "--nachname", "Person", "--wer", "probe-real");
            try (var thread = Executors.newSingleThreadExecutor()) {
                assertThat(in(thread, "probe-adminrollen", () -> inhaberEinladen.ausfuehren(aufruf, aus, fehl)))
                        .as(fehler.toString(StandardCharsets.UTF_8)).isZero();
            }
            String id = UUID.fromString(ausgabe.toString(StandardCharsets.UTF_8).strip()).toString();
            var benutzer = keycloak.benutzerLesen("probe-adminrollen", id);
            assertThat(benutzer.rollen()).containsExactly(ch.urinet.archiv.sicherheit.domain.Rolle.INHABER);
            assertThat(benutzer.aktionen()).containsExactlyInAnyOrder(
                    IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
                    IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                    IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
            int port = ConfigProvider.getConfig().getValue("archiv.entwicklung.mailpit-port", Integer.class);
            String mailpit = "http://localhost:" + port;
            List<String> mailIds;
            long frist = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                mailIds = given().baseUri(mailpit).queryParam("query", "to:" + email)
                        .get("/api/v1/search").then().statusCode(200).extract().jsonPath().getList("messages.ID");
                if (!mailIds.isEmpty()) {
                    break;
                }
                Thread.sleep(100);
            } while (System.nanoTime() < frist);
            assertThat(mailIds).isNotEmpty();
            var mail = given().baseUri(mailpit).get("/api/v1/message/" + mailIds.getFirst())
                    .then().statusCode(200).extract().jsonPath();
            assertThat(mail.getString("Text") + mail.getString("HTML"))
                    .as("Mailpit enthält den Einmal-Link zur Einrichtung").contains("action-token?key=");
            assertThat(ausgabe.toString(StandardCharsets.UTF_8)).doesNotContain(name, email, "http");
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
            List<String> ids = given().auth().oauth2(admin).queryParam("username", name).queryParam("exact", "true")
                    .get(adminPfad + "/users").then().statusCode(200).extract().path("id");
            for (String id : ids) {
                given().auth().oauth2(admin).delete(adminPfad + "/users/{id}", id).then().statusCode(204);
            }
        }
    }

    private void adminrollenMandantVorbereiten() {
        if (!register.existiert(new MandantCode("probe-adminrollen"))) {
            provisionierung.anlegen("probe-adminrollen", null, null);
        }
    }

    private static IdentitaetsDienst identitaetsDienstMitKeycloak(KeycloakAdminAdapter keycloak) {
        return new IdentitaetsDienstStub() {
            @Override
            public synchronized List<IdentitaetsDienst.Benutzer> benutzerSuchen(
                    String mandant, String suche, int seite, int groesse) {
                return keycloak.benutzerSuchen(mandant, suche, seite, groesse);
            }

            @Override
            public synchronized IdentitaetsDienst.Benutzer benutzerLesen(String mandant, String id) {
                return keycloak.benutzerLesen(mandant, id);
            }

            @Override
            public synchronized String anlegen(String mandant, IdentitaetsDienst.Neuanlage benutzer,
                                               ch.urinet.archiv.sicherheit.domain.Rolle rolle) {
                return keycloak.anlegen(mandant, benutzer, rolle);
            }

            @Override
            public synchronized String anlegenOhneRolle(String mandant, IdentitaetsDienst.Neuanlage benutzer) {
                return keycloak.anlegenOhneRolle(mandant, benutzer);
            }

            @Override
            public synchronized void einladen(String mandant, String id,
                                              java.util.Set<IdentitaetsDienst.ErforderlicheAktion> aktionen,
                                              Duration gueltigkeit) {
                keycloak.einladen(mandant, id, aktionen, gueltigkeit);
            }

            @Override
            public synchronized void anlageRueckgaengig(String mandant, String id) {
                keycloak.anlageRueckgaengig(mandant, id);
            }

            @Override
            public synchronized long benutzerZaehlen(String mandant) {
                return keycloak.benutzerZaehlen(mandant);
            }

            @Override
            public synchronized long inhaberZaehlen(String mandant) {
                return keycloak.inhaberZaehlen(mandant);
            }
        };
    }

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
            }, fehler -> Kompensation.UNKLAR, wert -> { }, wert -> abschluss)))
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(sperre.belegen(mandant, falsch.details().verwaltung().vorgangId())).isTrue();
            assertThat(sperre.freigeben(mandant, falsch.details().verwaltung().vorgangId())).isTrue();

            assertThatThrownBy(() -> in(thread, "vhf-gsk", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> "ok", fehler -> Kompensation.UNKLAR, wert -> { }, wert -> abschluss)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { throw new IllegalStateException("synthetische Prüfung"); }, () -> {
                        ausgefuehrt.set(true);
                        return "ok";
                    }, fehler -> Kompensation.UNKLAR, wert -> { }, wert -> abschluss)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(phasen(id)).isEmpty();
            assertThat(in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht, () -> { }, () -> {
                assertThat(QuarkusTransaction.isActive()).isFalse();
                assertThat(phasen(id)).containsExactly("ANGEFRAGT");
                return "ok";
            }, fehler -> Kompensation.UNKLAR, wert -> assertThat(QuarkusTransaction.isActive()).isTrue(),
                    wert -> abschluss))).isEqualTo("ok");
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "ABGESCHLOSSEN");
            UUID konfliktId = UUID.randomUUID();
            var konflikt = eintrag(konfliktId, ziel, akteur, "INHABER",
                    ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
            AtomicBoolean konfliktKompensiert = new AtomicBoolean();
            try {
                assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, konflikt, () -> { },
                        () -> { throw new IdentitaetsDienst.KonfliktException(); },
                        fehler -> {
                            konfliktKompensiert.set(true);
                            return Kompensation.GEKLAERT;
                        }, wert -> { }, wert -> abschluss)))
                        .hasRootCauseInstanceOf(IdentitaetsDienst.KonfliktException.class);
                assertThat(konfliktKompensiert).isTrue();
                assertThat(phasen(konfliktId)).containsExactly("ANGEFRAGT", "FEHLGESCHLAGEN");
                UUID neuerVorgang = UUID.randomUUID();
                assertThat(sperre.belegen(mandant, neuerVorgang)).isTrue();
                assertThat(sperre.freigeben(mandant, neuerVorgang)).isTrue();
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
                        fehler -> {
                            kompensiert.set(true);
                            return Kompensation.UNKLAR;
                        }, wert -> { }, wert -> abschluss)))
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
        var abschluss = eintrag(id, absicht.details().verwaltung().ziel(), absicht.benutzer().orElseThrow(), "INHABER",
                ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> "ok",
                    fehler -> { throw new IllegalArgumentException("synthetische Kompensation"); },
                    wert -> {
                        protokoll.schreiben(absicht);
                        throw new IllegalStateException("synthetischer Abschluss");
                    }, wert -> abschluss)))
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
    void betreiberOhneUebernahmeanlassDarfKeineRolleZuweisen() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        var details = new ProtokollDetails.Verwaltung(id, ProtokollDetails.Verwaltungsphase.ANGEFRAGT,
                UUID.randomUUID(), ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN,
                null, ProtokollDetails.Verwaltungsrolle.INHABER, List.of());
        var absicht = new ZugriffEintrag(Optional.of("betreiber:" + UUID.randomUUID()), Optional.empty(),
                ProtokollAktion.ROLLE_ZUWEISEN, Optional.empty(), Optional.empty(),
                ProtokollDetails.verwaltung(details));
        AtomicBoolean ausgefuehrt = new AtomicBoolean();
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> { ausgefuehrt.set(true); return true; },
                    fehler -> Kompensation.GEKLAERT, wert -> { }, wert -> absicht)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(ausgefuehrt).isFalse();
        }
    }

    @Test
    void betreibervorgangNachGeklaertemAbschlussfehlerWiederholbar() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        UUID erneut = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        String akteur = "betreiber:" + UUID.randomUUID();
        var absicht = betreiberRolle(id, ziel, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        var abschluss = betreiberRolle(id, ziel, akteur, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> true, fehler -> Kompensation.GEKLAERT,
                    wert -> { throw new IllegalStateException("synthetischer Abschlussfehler"); },
                    wert -> abschluss))).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "FEHLGESCHLAGEN");
            var neueAbsicht = betreiberRolle(erneut, ziel, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
            var neuerAbschluss = betreiberRolle(erneut, ziel, akteur, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
            assertThat(in(thread, "urinet", () -> vorgang.ausfuehren(mandant, neueAbsicht,
                    () -> { }, () -> true, fehler -> Kompensation.GEKLAERT,
                    wert -> { }, wert -> neuerAbschluss))).isTrue();
            assertThat(phasen(erneut)).containsExactly("ANGEFRAGT", "ABGESCHLOSSEN");
        } finally {
            testsperreEntfernen(mandant, id);
            testsperreEntfernen(mandant, erneut);
        }
    }

    @Test
    void fehlendesKompensationsergebnisBleibtUngeklaert() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        var absicht = eintrag(id, UUID.randomUUID(), UUID.randomUUID().toString(), "INHABER",
                ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> { throw new IllegalStateException("synthetischer Fehler"); },
                    fehler -> null, wert -> { }, wert -> absicht)))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .satisfies(fehler -> assertThat(fehler.getCause().getSuppressed())
                            .hasSize(1).satisfies(unterdrueckt ->
                                    assertThat(unterdrueckt[0]).isInstanceOf(NullPointerException.class)));
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void kompensationsfehlerBeiFachfehlerWirdOhneInhalteGemeldet() throws Exception {
        MandantCode mandant = mandantFuerAnlage();
        UUID id = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        var absicht = anlage(id, null, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor(); var log = LogMitschnitt.starten()) {
            assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> { throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER); },
                    fehler -> { throw new IllegalStateException("geheimer synthetischer Inhalt"); },
                    wert -> { }, wert -> absicht)))
                    .hasRootCauseInstanceOf(FachFehler.class);
            assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
            assertThat(log.volltext()).contains(id.toString(), "Kompensation fehlgeschlagen", "IllegalStateException")
                    .doesNotContain("geheimer synthetischer Inhalt");
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void anlagefehlerUebernimmtZielUndHaeltUnklareEntfernungGesperrt() throws Exception {
        MandantCode mandant = mandantFuerAnlage();
        UUID ziel = UUID.randomUUID();
        try (var thread = Executors.newSingleThreadExecutor()) {
            for (boolean entfernt : List.of(true, false)) {
                UUID id = UUID.randomUUID();
                var absicht = anlage(id, null, UUID.randomUUID().toString(),
                        ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
                try {
                    assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                            () -> { }, () -> {
                                throw new IdentitaetsDienst.AnlageException(ziel.toString(), entfernt,
                                        new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER));
                            }, fehler -> Kompensation.GEKLAERT, wert -> { }, wert -> absicht)))
                            .hasRootCauseInstanceOf(FachFehler.class);
                    String phase = entfernt ? "FEHLGESCHLAGEN" : "UNKLAR";
                    assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", phase);
                    assertThat(zielDerPhase(mandant.schema(), id, phase)).isEqualTo(ziel.toString());
                    UUID neuerVorgang = UUID.randomUUID();
                    assertThat(sperre.belegen(mandant, neuerVorgang)).isEqualTo(entfernt);
                    if (entfernt) {
                        assertThat(sperre.freigeben(mandant, neuerVorgang)).isTrue();
                    }
                } finally {
                    testsperreEntfernen(mandant, id);
                }
            }
        }
    }

    @Test
    void konfliktBeiAnlageErgibtNeutralenFehlerUndGibtSperreFrei() throws Exception {
        MandantCode mandant = mandantFuerAnlage("vhf-gsk");
        UUID id = UUID.randomUUID();
        var absicht = anlage(id, null, UUID.randomUUID().toString(), ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> { throw new IdentitaetsDienst.KonfliktException(); },
                    fehler -> Kompensation.GEKLAERT, wert -> { }, wert -> absicht)))
                    .hasRootCauseInstanceOf(FachFehler.class)
                    .satisfies(fehler -> assertThat(((FachFehler) fehler.getCause()).code())
                            .isEqualTo(Fehlercode.BENUTZER_EXISTIERT));
            assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "FEHLGESCHLAGEN");
            UUID neuerVorgang = UUID.randomUUID();
            assertThat(sperre.belegen(mandant, neuerVorgang)).isTrue();
            assertThat(sperre.freigeben(mandant, neuerVorgang)).isTrue();
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void ungeklaerterAnlagekonfliktBleibtGesperrtUndWirdNichtAlsGeklaertAbgebildet() throws Exception {
        MandantCode mandant = mandantFuerAnlage("vhf-gsk");
        UUID id = UUID.randomUUID();
        var absicht = anlage(id, null, UUID.randomUUID().toString(), ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> { throw new IdentitaetsDienst.KonfliktException(); },
                    fehler -> Kompensation.UNKLAR, wert -> { }, wert -> absicht)))
                    .hasRootCauseInstanceOf(IdentitaetsDienst.KonfliktException.class);
            assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void ungueltigeAnlagekennungBleibtUnklarOhneDenWertZuLoggen() throws Exception {
        MandantCode mandant = mandantFuerAnlage();
        UUID id = UUID.randomUUID();
        var absicht = anlage(id, null, UUID.randomUUID().toString(), ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor(); var log = LogMitschnitt.starten()) {
            assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> {
                        throw new IdentitaetsDienst.AnlageException("geheimer-synthetischer-wert", true,
                                new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER));
                    }, fehler -> Kompensation.GEKLAERT, wert -> { }, wert -> absicht)))
                    .hasRootCauseInstanceOf(FachFehler.class);
            assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(zielDerPhase(mandant.schema(), id, "UNKLAR")).isNull();
            assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
            assertThat(log.volltext()).contains(id.toString(), "Zielkennung der Anlage ungültig")
                    .doesNotContain("geheimer-synthetischer-wert");
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void anlageMitGescheitertemAbschlussTraegtZielImUnklarEintrag() throws Exception {
        MandantCode mandant = mandantFuerAnlage();
        UUID id = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        String sub = UUID.randomUUID().toString();
        var absicht = anlage(id, null, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> sub, fehler -> Kompensation.UNKLAR,
                    wert -> { throw new IllegalStateException("synthetischer Abschluss"); },
                    wert -> anlage(id, UUID.fromString(wert), akteur,
                            ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN))))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(zielDerPhase(mandant.schema(), id, "UNKLAR")).isEqualTo(sub);
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void abgelehnterOderFehlenderAnlageabschlussUebernimmtKeinUngeprueftesZiel() throws Exception {
        MandantCode mandant = mandantFuerAnlage();
        try (var thread = Executors.newSingleThreadExecutor()) {
            for (int fall = 0; fall < 3; fall++) {
                UUID id = UUID.randomUUID();
                UUID ziel = UUID.randomUUID();
                String akteur = UUID.randomUUID().toString();
                var absicht = anlage(id, null, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
                AtomicInteger abschlussAufrufe = new AtomicInteger();
                AtomicBoolean gespeichert = new AtomicBoolean();
                int fehlerart = fall;
                Function<UUID, ZugriffEintrag> abschluss = wert -> {
                    abschlussAufrufe.incrementAndGet();
                    if (fehlerart == 2) {
                        throw new IllegalStateException("synthetische Zielermittlung");
                    }
                    return anlage(fehlerart == 0 ? UUID.randomUUID() : id, wert,
                            fehlerart == 1 ? UUID.randomUUID().toString() : akteur,
                            ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
                };
                try {
                    assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.ausfuehren(mandant, absicht,
                            () -> { }, () -> ziel, fehler -> Kompensation.GEKLAERT,
                            wert -> gespeichert.set(true), abschluss)))
                            .hasRootCauseInstanceOf(fehlerart == 2
                                    ? IllegalStateException.class : IllegalArgumentException.class);
                    assertThat(abschlussAufrufe).hasValue(1);
                    assertThat(gespeichert).isFalse();
                    assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "UNKLAR");
                    assertThat(zielDerPhase(mandant.schema(), id, "UNKLAR")).isNull();
                    assertThat(sperre.belegen(mandant, UUID.randomUUID())).isFalse();
                } finally {
                    testsperreEntfernen(mandant, id);
                }
            }
        }
    }

    @Test
    void fehlerDerArbeitMitZielTraegtDasZielInsUnklarProtokoll() throws Exception {
        MandantCode mandant = mandantFuerAnlage();
        UUID id = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        var absicht = anlage(id, null, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, mandant.wert(), () -> vorgang.<UUID>ausfuehren(mandant, absicht,
                    () -> { }, () -> { throw new VerwaltungVorgang.ArbeitFehler(sub, new IllegalStateException()); },
                    fehler -> Kompensation.UNKLAR, wert -> { },
                    wert -> anlage(id, wert, akteur, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN))))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(phasen(mandant.schema(), id)).containsExactly("ANGEFRAGT", "UNKLAR");
            assertThat(zielDerPhase(mandant.schema(), id, "UNKLAR")).isEqualTo(sub.toString());
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    @Test
    void abschlussMitAndererRolleAlsAngefragtWirdAbgelehnt() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        AtomicBoolean gespeichert = new AtomicBoolean();
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant,
                    rollenvorgang(id, ziel, akteur, ProtokollDetails.Verwaltungsphase.ANGEFRAGT,
                            ProtokollDetails.Verwaltungsrolle.MITARBEITER),
                    () -> { }, () -> "ok", fehler -> Kompensation.UNKLAR, wert -> gespeichert.set(true),
                    wert -> rollenvorgang(id, ziel, akteur, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN,
                            ProtokollDetails.Verwaltungsrolle.INHABER))))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(gespeichert).isFalse();
            assertThat(phasen(id)).containsExactly("ANGEFRAGT", "UNKLAR");
        } finally {
            testsperreEntfernen(mandant, id);
        }
    }

    private static ZugriffEintrag rollenvorgang(UUID id, UUID ziel, String akteur,
                                                ProtokollDetails.Verwaltungsphase phase,
                                                ProtokollDetails.Verwaltungsrolle rolleNeu) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN, null, rolleNeu, List.of());
        return new ZugriffEintrag(Optional.of(akteur), Optional.of("INHABER"), ProtokollAktion.ROLLE_ZUWEISEN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }

    private static ZugriffEintrag anlage(UUID id, UUID ziel, String akteur,
                                         ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel, ProtokollDetails.Verwaltungsvorgang.ANLEGEN,
                null, null, List.of());
        return new ZugriffEintrag(Optional.of(akteur), Optional.of("INHABER"), ProtokollAktion.BENUTZER_ANLEGEN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }

    private String zielDerPhase(String schema, UUID id, String phase) {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details->>'ziel' from " + schema + ".zugriff_protokoll "
                             + "where details->>'vorgangId' = ? and details->>'phase' = ?")) {
            abfrage.setString(1, id.toString());
            abfrage.setString(2, phase);
            try (ResultSet zeilen = abfrage.executeQuery()) {
                return zeilen.next() ? zeilen.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Testprotokoll nicht lesbar", e);
        }
    }

    @Test
    void abschlussNachFristBleibtUngeklaert() throws Exception {
        mandantenVorbereiten();
        MandantCode mandant = new MandantCode("urinet");
        UUID id = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        String akteur = UUID.randomUUID().toString();
        var absicht = eintrag(id, ziel, akteur, "INHABER", ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        var abschluss = eintrag(id, ziel, akteur, "INHABER", ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
        try (var thread = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> {
                        try (Connection verbindung = datenquelle.getConnection();
                             PreparedStatement ablaufen = verbindung.prepareStatement(
                                     "update public.verwaltung_vorgang set bis = clock_timestamp() "
                                             + "- interval '1 second' where mandant = ? and vorgang_id = ?")) {
                            ablaufen.setString(1, mandant.wert());
                            ablaufen.setObject(2, id);
                            assertThat(ablaufen.executeUpdate()).isEqualTo(1);
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e);
                        }
                        return "ok";
                    }, fehler -> Kompensation.GEKLAERT, wert -> protokoll.schreiben(absicht), wert -> abschluss)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
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
                    fehler -> Kompensation.UNKLAR, wert -> { }, wert -> falscheRolle)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(phasen(id)).isEmpty();
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, ohneRolle,
                    () -> { }, () -> { ausgefuehrt.set(true); return "ok"; },
                    fehler -> Kompensation.UNKLAR, wert -> { }, wert -> falscheRolle)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(ausgefuehrt).isFalse();
            assertThat(phasen(id)).isEmpty();
            assertThatThrownBy(() -> in(thread, "urinet", () -> vorgang.ausfuehren(mandant, absicht,
                    () -> { }, () -> "ok", fehler -> Kompensation.UNKLAR, wert -> { }, wert -> falscheRolle)))
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
                        fehler -> Kompensation.UNKLAR, wert -> { }, wert -> fall)))
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

    private static ZugriffEintrag betreiberRolle(UUID id, UUID ziel, String akteur,
                                                ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN,
                ProtokollDetails.Verwaltungsrolle.INHABER, ProtokollDetails.Verwaltungsrolle.INHABER,
                List.of(), null, null, ProtokollDetails.Verwaltungsanlass.UEBERNAHME, null);
        return new ZugriffEintrag(Optional.of(akteur), Optional.empty(), ProtokollAktion.ROLLE_ZUWEISEN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }

    private static ZugriffEintrag eintrag(UUID id, UUID ziel, String akteur, String rolle,
                                          ProtokollDetails.Verwaltungsphase phase,
                                          ProtokollDetails.Verwaltungsvorgang vorgang) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel, vorgang, null, null, List.of());
        return new ZugriffEintrag(Optional.of(akteur), Optional.of(rolle), ProtokollAktion.BENUTZER_AENDERN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }

    private List<String> phasen(UUID id) {
        return phasen("m_urinet", id);
    }

    private List<String> phasen(String schema, UUID id) {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details->>'phase' from " + schema + ".zugriff_protokoll "
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

    private MandantCode mandantFuerAnlage() throws Exception {
        return mandantFuerAnlage("probe-verwaltungsvorgang");
    }

    private MandantCode mandantFuerAnlage(String code) throws Exception {
        MandantCode mandant = new MandantCode(code);
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(mandant.wert(), null, null);
                }
            }
        }
        return mandant;
    }

    private <T> T in(java.util.concurrent.ExecutorService thread, String code, Supplier<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(code), arbeit)).get(30, TimeUnit.SECONDS);
    }
}
