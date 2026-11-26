package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.S3Stoerung;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.dokument.adapter.out.persistence.PanacheDokumentSuche;
import ch.urinet.archiv.dokument.application.Aenderung;
import ch.urinet.archiv.dokument.application.DokumentAendern;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.application.Seite;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.integration.application.webseite.WebseiteLesen;
import ch.urinet.archiv.integration.application.webseite.WebseiteSuche;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.WebseiteReihe;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;
import ch.urinet.archiv.kontakt.application.KontaktSeite;
import ch.urinet.archiv.kontakt.application.KontaktSuche;
import ch.urinet.archiv.kontakt.application.KontaktVerwaltung;
import ch.urinet.archiv.kontakt.application.KontaktVerweise;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.konfiguration.application.KlassifikationVerwaltung;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
    @Inject WebseiteSuche webseiteSuche;
    @Inject WebseitenSchluesselVerwaltung schluesselVerwaltung;
    @Inject KontaktVerwaltung kontaktVerwaltung;
    @Inject DokumentLesen dokumentLesen;
    @Inject DokumentAendern dokumentAendern;
    @Inject DokumentartVerwaltung dokumentarten;
    @Inject KonfigurationVerwaltung konfiguration;
    @Inject KlassifikationVerwaltung klassifikationen;

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
    void webseitenLesepfadeBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) throws Exception {
        List<String> tokens = new ArrayList<>();
        List<String> pfade = List.of("/public/v1/dokumente", "/public/v1/filter",
                "/public/v1/suche?q=" + MARKER);
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
            WebseiteSuche originalSuche = ClientProxy.unwrap(webseiteSuche);
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
            try {
                QuarkusMock.installMockForType(new WebseiteSuche(null, null, null, null) {
                    @Override
                    public Ergebnis suchen(Lesezugriff zugriff, String begriff, ListenFilter filter) {
                        getroffen.incrementAndGet();
                        throw fehler(ausfall);
                    }
                }, WebseiteSuche.class);
                try (LogMitschnitt log = LogMitschnitt.starten()) {
                    for (String token : tokens) {
                        for (String pfad : pfade) {
                            var antwort = given().auth().oauth2(token).when().get(pfad);
                            assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                            assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
                        }
                    }
                    assertThat(getroffen).hasValue(6);
                    assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
                    for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
                }
            } finally {
                QuarkusMock.installMockForType(original, WebseiteLesen.class);
                QuarkusMock.installMockForType(originalSuche, WebseiteSuche.class);
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
    void klassifikationenBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        List<String> arten = new ArrayList<>();
        for (String token : tokens) {
            var liste = given().auth().oauth2(token).when().get("/api/v1/dokumentarten");
            liste.then().statusCode(200);
            String art = liste.jsonPath().getString("[0].code");
            assertThat(art).isNotBlank();
            arten.add(art);
            given().auth().oauth2(token).when().get("/api/v1/klassifikationen").then().statusCode(200);
            given().auth().oauth2(token).when().get("/api/v1/dokumentarten/" + art + "/klassifikationen")
                    .then().statusCode(200);
        }
        KlassifikationVerwaltung original = ClientProxy.unwrap(klassifikationen);
        AtomicInteger getroffen = new AtomicInteger();
        QuarkusMock.installMockForType(new KlassifikationVerwaltung(null, null, null) {
            @Override
            public List<Klassifikation> alle() {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }

            @Override
            public List<ArtKlassifikation> artKlassifikationen(String art) {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }
        }, KlassifikationVerwaltung.class);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                for (String pfad : List.of("/api/v1/klassifikationen",
                        "/api/v1/dokumentarten/" + arten.get(i) + "/klassifikationen")) {
                    var antwort = given().auth().oauth2(token).when().get(pfad);
                    assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                    assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
                }
            }
            assertThat(getroffen).hasValue(4);
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            QuarkusMock.installMockForType(original, KlassifikationVerwaltung.class);
        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void konfigurationsversionenBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        List<Integer> versionen = new ArrayList<>();
        for (String token : tokens) {
            var liste = given().auth().oauth2(token).when().get("/api/v1/konfiguration/versionen");
            liste.then().statusCode(200);
            int version = liste.jsonPath().getInt("[0].version");
            assertThat(version).isPositive();
            given().auth().oauth2(token).when().get("/api/v1/konfiguration/versionen/" + version)
                    .then().statusCode(200);
            versionen.add(version);
        }
        KonfigurationVerwaltung original = ClientProxy.unwrap(konfiguration);
        AtomicInteger getroffen = new AtomicInteger();
        QuarkusMock.installMockForType(new KonfigurationVerwaltung(null, null, null, null, null, null,
                null, null, null, null, null) {
            @Override
            public List<KonfigurationsVersion> versionen() {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }

            @Override
            public KonfigurationsVersion version(int nummer) {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }
        }, KonfigurationVerwaltung.class);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                for (String pfad : List.of("/api/v1/konfiguration/versionen",
                        "/api/v1/konfiguration/versionen/" + versionen.get(i))) {
                    var antwort = given().auth().oauth2(token).when().get(pfad);
                    assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                    assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
                }
            }
            assertThat(getroffen).hasValue(4);
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            QuarkusMock.installMockForType(original, KonfigurationVerwaltung.class);
        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void dokumentartenBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) {
        List<String> tokens = List.of(Testanmeldung.token("inhaber-urinet"), Testanmeldung.token("inhaber-vhf-gsk"));
        for (String token : tokens) {
            given().auth().oauth2(token).when().get("/api/v1/dokumentarten").then().statusCode(200);
        }
        DokumentartVerwaltung original = ClientProxy.unwrap(dokumentarten);
        AtomicInteger getroffen = new AtomicInteger();
        QuarkusMock.installMockForType(new DokumentartVerwaltung(null, null, null, null, null, null) {
            @Override
            public List<Dokumentart> alle() {
                getroffen.incrementAndGet();
                throw fehler(ausfall);
            }
        }, DokumentartVerwaltung.class);
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            for (String token : tokens) {
                var antwort = given().auth().oauth2(token).when().get("/api/v1/dokumentarten");
                assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                assertThat(antwort.asString()).doesNotContain(MARKER, token, "Exception", "\tat ", "Caused by:");
            }
            assertThat(getroffen).hasValue(2);
            assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
            for (String token : tokens) assertThat(log.volltext()).doesNotContain(token);
        } finally {
            QuarkusMock.installMockForType(original, DokumentartVerwaltung.class);
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

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void kontaktBearbeitungBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) throws Exception {
        record Probe(String mandant, String token, UUID id, String etag) { }
        List<Probe> proben = new ArrayList<>();
        String neuerKontakt = "{\"typ\":\"PERSON\",\"name\":\"" + MARKER + "\"}";
        try {
            for (String mandant : List.of("urinet", "vhf-gsk")) {
                String token = Testanmeldung.token("inhaber-" + mandant);
                var erstellt = given().auth().oauth2(token).contentType("application/json").body(neuerKontakt)
                        .when().post("/api/v1/kontakte");
                assertThat(erstellt.statusCode()).isEqualTo(201);
                UUID id = UUID.fromString(erstellt.jsonPath().getString("id"));
                proben.add(new Probe(mandant, token, id, erstellt.header("ETag")));
                String pfad = "/api/v1/kontakte/" + id;
                given().auth().oauth2(token).when().get(pfad).then().statusCode(200);
                given().auth().oauth2(token).when().get(pfad + "/verweise").then().statusCode(200);
                var geaendert = given().auth().oauth2(token).header("If-Match", erstellt.header("ETag"))
                        .contentType("application/json").body("{\"vorname\":\"Probe\"}")
                        .when().patch(pfad);
                assertThat(geaendert.statusCode()).isEqualTo(200);
                proben.set(proben.size() - 1, new Probe(mandant, token, id, geaendert.header("ETag")));
            }
            KontaktVerwaltung original = ClientProxy.unwrap(kontaktVerwaltung);
            AtomicInteger getroffen = new AtomicInteger();
            QuarkusMock.installMockForType(new KontaktVerwaltung(null, null, null, null) {
                @Override
                public Detail detail(UUID id) {
                    getroffen.incrementAndGet();
                    throw fehler(ausfall);
                }

                @Override
                public KontaktVerweise.Zahlen verweise(UUID id) {
                    getroffen.incrementAndGet();
                    throw fehler(ausfall);
                }

                @Override
                public Kontakt anlegen(KontaktAngaben angaben) {
                    getroffen.incrementAndGet();
                    throw fehler(ausfall);
                }

                @Override
                public Kontakt aendern(UUID id, int version, KontaktAngaben angaben) {
                    getroffen.incrementAndGet();
                    throw fehler(ausfall);
                }
            }, KontaktVerwaltung.class);
            try (LogMitschnitt log = LogMitschnitt.starten()) {
                for (Probe probe : proben) {
                    String pfad = "/api/v1/kontakte/" + probe.id();
                    var detail = given().auth().oauth2(probe.token()).when().get(pfad);
                    var verweise = given().auth().oauth2(probe.token()).when().get(pfad + "/verweise");
                    var anlegen = given().auth().oauth2(probe.token()).contentType("application/json")
                            .body(neuerKontakt).when().post("/api/v1/kontakte");
                    var aendern = given().auth().oauth2(probe.token()).header("If-Match", probe.etag())
                            .contentType("application/json").body("{\"vorname\":\"" + MARKER + "\"}")
                            .when().patch(pfad);
                    for (var antwort : List.of(detail, verweise, anlegen, aendern)) {
                        assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                        assertThat(antwort.asString())
                                .doesNotContain(MARKER, probe.token(), "Exception", "\tat ", "Caused by:");
                    }
                }
                assertThat(getroffen).hasValue(8);
                assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
                for (Probe probe : proben) assertThat(log.volltext()).doesNotContain(probe.token());
            } finally {
                QuarkusMock.installMockForType(original, KontaktVerwaltung.class);
            }
        } finally {
            for (Probe probe : proben) {
                try (Connection c = datenbank.getConnection();
                     PreparedStatement s = c.prepareStatement("delete from "
                             + new MandantCode(probe.mandant()).schema() + ".kontakt where id = ?")) {
                    s.setObject(1, probe.id());
                    s.executeUpdate();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Ausfall.class)
    void dokumentBeiAusfallOhneInhaltUndStacktrace(Ausfall ausfall) throws Exception {
        record Probe(String mandant, String token, UUID id, String etag) { }
        List<Probe> proben = new ArrayList<>();
        try {
            for (String mandant : List.of("urinet", "vhf-gsk")) {
                String token = Testanmeldung.token("inhaber-" + mandant);
                UUID id = Testdokumente.anlegen(datenbank, new Testdokumente.Angaben(
                        mandant, null, "NORMAL", false, "probe", null, "EINGANGSKORB"));
                proben.add(new Probe(mandant, token, id, null));
                String pfad = "/api/v1/dokumente/" + id;
                var gelesen = given().auth().oauth2(token).when().get(pfad);
                assertThat(gelesen.statusCode()).isEqualTo(200);
                var geaendert = given().auth().oauth2(token).header("If-Match", gelesen.header("ETag"))
                        .contentType("application/json").body("{\"titel\":\"Probe\"}").when().patch(pfad);
                assertThat(geaendert.statusCode()).isEqualTo(200);
                proben.set(proben.size() - 1, new Probe(mandant, token, id, geaendert.header("ETag")));
            }
            DokumentLesen originalesLesen = ClientProxy.unwrap(dokumentLesen);
            DokumentAendern originalesAendern = ClientProxy.unwrap(dokumentAendern);
            AtomicInteger gelesen = new AtomicInteger();
            AtomicInteger geaendert = new AtomicInteger();
            QuarkusMock.installMockForType(new DokumentLesen(null, null, null, null, null, null, null, null, null) {
                @Override
                public Ansicht ansicht(Benutzer benutzer, UUID id) {
                    gelesen.incrementAndGet();
                    throw fehler(ausfall);
                }
            }, DokumentLesen.class);
            try {
                QuarkusMock.installMockForType(new DokumentAendern(null, null, null, null, null, null, null,
                        null, null, null, null, null, null) {
                    @Override
                    public int aendern(Benutzer benutzer, UUID id, int version, Supplier<Aenderung> angaben) {
                        geaendert.incrementAndGet();
                        throw fehler(ausfall);
                    }
                }, DokumentAendern.class);
                try (LogMitschnitt log = LogMitschnitt.starten()) {
                    for (Probe probe : proben) {
                        String pfad = "/api/v1/dokumente/" + probe.id();
                        var detail = given().auth().oauth2(probe.token()).when().get(pfad);
                        var patch = given().auth().oauth2(probe.token()).header("If-Match", probe.etag())
                                .contentType("application/json").body("{\"titel\":\"" + MARKER + "\"}")
                                .when().patch(pfad);
                        for (var antwort : List.of(detail, patch)) {
                            assertThat(ProblemDetails.pruefen(antwort, 500)).isEqualTo("INTERNER_FEHLER");
                            assertThat(antwort.asString())
                                    .doesNotContain(MARKER, probe.token(), "Exception", "\tat ", "Caused by:");
                        }
                    }
                    assertThat(gelesen).hasValue(2);
                    assertThat(geaendert).hasValue(2);
                    assertThat(log.volltext()).doesNotContain(MARKER, "\tat ", "Caused by:");
                    for (Probe probe : proben) assertThat(log.volltext()).doesNotContain(probe.token());
                } finally {
                    QuarkusMock.installMockForType(originalesAendern, DokumentAendern.class);
                }
            } finally {
                QuarkusMock.installMockForType(originalesLesen, DokumentLesen.class);
            }
        } finally {
            for (Probe probe : proben) Testdokumente.loeschen(datenbank, probe.mandant(), List.of(probe.id()));
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
