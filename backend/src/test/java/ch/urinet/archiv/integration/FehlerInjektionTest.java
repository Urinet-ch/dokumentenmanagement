package ch.urinet.archiv.integration;

import ch.urinet.archiv.ablage.S3Stoerung;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.adapter.out.persistence.PanacheDokumentSuche;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.ProblemDetails;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Reale REST-Anfragen mit einem gezielt ausfallenden Such-Port; keine fremden Datenbanken werden angehalten. */
@QuarkusTest
class FehlerInjektionTest {
    private static final String MARKER = "SYNTHETISCHER_INHALT_7F42";

    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;
    @Inject SuchePort suche;

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
                Exception ursache = switch (ausfall) {
                    case DATENBANK -> new SQLTransientConnectionException(MARKER);
                    case ZEITUEBERSCHREITUNG -> new SocketTimeoutException(MARKER);
                };
                throw new IllegalStateException(MARKER, ursache);
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
}
