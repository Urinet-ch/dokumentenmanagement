package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.S3Stoerung;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.adapter.out.persistence.PanacheDokumentSuche;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.application.Seite;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.integration.application.webseite.WebseiteLesen;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.WebseiteReihe;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;
import ch.urinet.archiv.kontakt.application.KontaktSeite;
import ch.urinet.archiv.kontakt.application.KontaktSuche;
import ch.urinet.archiv.kontakt.application.KontaktVerwaltung;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.builder.MultiPartSpecBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Reale REST-Anfragen mit gezielt simulierten Ausfällen; keine fremden Datenbanken werden angehalten. */
@QuarkusTest
class FehlerInjektionTest {
    private static final String MARKER = "SYNTHETISCHER_INHALT_7F42";

    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;
    @Inject SuchePort suche;
    @Inject WebseiteLesen webseiteLesen;
    @Inject WebseitenSchluesselVerwaltung schluesselVerwaltung;
    @Inject KontaktVerwaltung kontaktVerwaltung;
    @Inject DokumentLesen dokumentLesen;

    enum Ausfall { DATENBANK, ZEITUEBERSCHREITUNG }

    @BeforeEach
    void mandanten() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection c = datenbank.getConnection();
                 PreparedStatement s = c.prepareStatement("select 1 from public.mandant where code = ?")) {
                s.setString(1, code);
                try (var zeilen = s.executeQuery()) {
                    if (!zeilen.next()) provisionierung.anlegen(code, null, null);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void sucheBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        for (String token : tokens) {
            given().auth().oauth2(token).queryParam("modus", "volltext").queryParam("q", MARKER)
                    .when().get("/api/v1/dokumente").then().statusCode(200);
        }
        SuchePort original = ClientProxy.unwrap(suche);
        AtomicInteger getroffen = new AtomicInteger();
        QuarkusMock.installMockForType(new PanacheDokumentSuche(Duration.ofSeconds(1), new ObjectMapper()) {
            @Override
            public Suchergebnis suchen(Suchanfrage anfrage, Sichtregel sichtregel, boolean mitDateiname) {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }
        }, SuchePort.class);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            for (String token : tokens) {
                var antwort = given().auth().oauth2(token).queryParam("modus", "volltext").queryParam("q", MARKER)
                        .when().get("/api/v1/dokumente");
                assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
            }
            assertThat(getroffen).hasValue(2);
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            QuarkusMock.installMockForType(original, SuchePort.class);
        }
    }

    @Test
    void speicherausfallBeimUploadOhneDokumentinhalt() throws Exception {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        ByteArrayOutputStream datei = new ByteArrayOutputStream();
        datei.write(Testdateien.erzeugen("pdf"));
        datei.write(("\n% " + MARKER).getBytes(StandardCharsets.UTF_8));
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            S3Stoerung.ab(0, S3Stoerung.Art.NETZ);
            for (String token : tokens) {
                var antwort = given().auth().oauth2(token).contentType("multipart/form-data")
                        .multiPart(new MultiPartSpecBuilder(datei.toByteArray()).controlName("dateien")
                                .fileName("probe.pdf").mimeType("application/octet-stream").build())
                        .when().post("/api/v1/dokumente");
                assertThat(ProblemDetails.pruefen(antwort, 503, "ergebnisse"))
                        .isEqualTo("SPEICHER_NICHT_ERREICHBAR");
                assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
            }
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            S3Stoerung.aus();
        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void webseitenlisteBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) throws Exception {
        List<String> tokens = new ArrayList<>();
        List<String> pfade = List.of("/public/v1/dokumente", "/public/v1/filter");
        try {
            for (String mandant : List.of("urinet", "vhf-gsk")) {
                tokens.add(schluesselVerwaltung.erstellen(mandant, "qa-fehlerantwort",
                        "lesen", "oeffentlich", false).token().text());
            }
            for (String token : tokens) {
                for (String pfad : pfade) {
                    given().auth().oauth2(token).when().get(pfad).then().statusCode(200);
                }
            }
            WebseiteLesen original = ClientProxy.unwrap(webseiteLesen);
            AtomicInteger getroffen = new AtomicInteger();
            QuarkusMock.installMockForType(new WebseiteLesen(null) {
                @Override
                public Set<String> oeffentlicheKlassifikationsCodes() {
                    return Set.of();
                }

                @Override
                public WebseiteSeite liste(Lesezugriff zugriff, ListenFilter filter, WebseiteSortierung sortierung,
                                           int seite, int proSeite) {
                    getroffen.incrementAndGet();
                    throw fehler(ausfall);
                }

                @Override
                public List<WebseiteReihe> reihen(Lesezugriff zugriff) {
                    getroffen.incrementAndGet();
                    throw fehler(ausfall);
                }
            }, WebseiteLesen.class);
            try (LogMitschnitt log = LogMitschnitt.starten()) {
                for (String token : tokens) {
                    for (String pfad : pfade) {
                        var antwort = given().auth().oauth2(token).when().get(pfad);
                        assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                        assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
                    }
                }
                assertThat(getroffen).hasValue(4);
                assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
                for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
            } finally {
                QuarkusMock.installMockForType(original, WebseiteLesen.class);
            }
        } finally {
            try (Connection c = datenbank.getConnection();
                 PreparedStatement s = c.prepareStatement("delete from public.api_client where id = ?")) {
                for (String token : tokens) {
                    s.setString(1, ApiToken.lesen(token).orElseThrow().id());
                    s.executeUpdate();
                }
            }

        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void kontakteBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        List<String> pfade = List.of("/api/v1/kontakte", "/api/v1/kontakte/liste");
        for (String token : tokens) {
            for (String pfad : pfade) {
                given().auth().oauth2(token).queryParam("q", MARKER).when().get(pfad).then().statusCode(200);
            }
        }
        KontaktVerwaltung original = ClientProxy.unwrap(kontaktVerwaltung);
        AtomicInteger getroffen = new AtomicInteger();
        QuarkusMock.installMockForType(new KontaktVerwaltung(null, null, null, null) {
            @Override
            public List<Kontakt> suchen(String text) {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }

            @Override
            public KontaktSeite liste(String text, KontaktSuche.Status status, String quelle, Boolean nichtImExport,
                                     int seite, int groesse) {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }
        }, KontaktVerwaltung.class);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            for (String token : tokens) {
                for (String pfad : pfade) {
                    var antwort = given().auth().oauth2(token).queryParam("q", MARKER).when().get(pfad);
                    assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                    assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
                }
            }
            assertThat(getroffen).hasValue(4);
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            QuarkusMock.installMockForType(original, KontaktVerwaltung.class);
        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void eingangskorbBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        for (String token : tokens) {
            given().auth().oauth2(token).when().get("/api/v1/eingangskorb").then().statusCode(200);
        }
        DokumentLesen original = ClientProxy.unwrap(dokumentLesen);
        AtomicInteger getroffen = new AtomicInteger();
        QuarkusMock.installMockForType(new DokumentLesen(null, null, null, null, null, null, null, null, null) {
            @Override
            public Seite<Dokument> eingangskorb(Benutzer benutzer, int seite, int groesse) {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }
        }, DokumentLesen.class);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            for (String token : tokens) {
                var antwort = given().auth().oauth2(token).when().get("/api/v1/eingangskorb");
                assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
            }
            assertThat(getroffen).hasValue(2);
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            QuarkusMock.installMockForType(original, DokumentLesen.class);
        }
    }

    private static IllegalStateException fehler(Ausfall ausfall) {
        Exception ursache = switch (ausfall) {
            case DATENBANK -> new SQLTransientConnectionException(MARKER);
            case ZEITUEBERSCHREITUNG -> new SocketTimeoutException(MARKER);
        };
        return new IllegalStateException(MARKER, ursache);
    }
}
