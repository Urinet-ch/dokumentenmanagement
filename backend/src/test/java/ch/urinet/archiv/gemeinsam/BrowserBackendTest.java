package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Echtes Backend für die Browser-Szenarien (frontend/e2e): startet das Archiv im Testmodus mit Dev Services
 * (PostgreSQL, Keycloak mit Test-Realm, MinIO) und dem Test-Handler für {@code VERARBEITEN}, legt die Mandanten
 * {@code urinet} und {@code vhf-gsk} an und schreibt {@code target/browser-backend.json} mit den Adressen von API und
 * Keycloak. Danach wartet er, bis {@code target/browser-backend.stop} erscheint (höchstens 60 Minuten).
 * <p>
 * Testhilfe für den Status (Aufgabe 4.10): Im Testmodus läuft kein Worker, hochgeladene Dokumente bleiben in
 * {@code NEU}. Während des Wartens führt er Anfragedateien {@code target/browser-backend-anfragen/<uuid>.json}
 * ({@code {"mandant": …, "dokument": …, "status": "EINGANGSKORB" | "FEHLER"}}) per SQL im Schema des Mandanten aus,
 * wie die Java-Szenarien diese Status herstellen, und quittiert jede mit {@code <uuid>.antwort.json}. Kein eigener
 * HTTP-Endpunkt; das Dokument bleibt ohne Titel und ohne Vorschlag.
 * <p>
 * Nur mit {@code -Darchiv.browser-backend=true}; in {@code mvn verify} läuft er nicht.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "archiv.browser-backend", matches = "true")
class BrowserBackendTest {

    private static final Path BEREIT = Path.of("target", "browser-backend.json");
    private static final Path STOPP = Path.of("target", "browser-backend.stop");
    private static final Path ANFRAGEN = Path.of("target", "browser-backend-anfragen");
    private static final Pattern ANFRAGE = Pattern.compile("[0-9a-f-]{36}\\.json");
    private static final Set<String> MANDANTEN = Set.of("urinet", "vhf-gsk");
    private static final Set<String> ZIELSTATUS = Set.of("EINGANGSKORB", "FEHLER");
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Inject
    AblagePort ablage;

    @Inject
    ObjectMapper json;

    @Test
    void bereitstellen() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        var config = ConfigProvider.getConfig();
        String api = "http://localhost:" + config.getValue("quarkus.http.test-port", Integer.class);
        String keycloak = config.getValue("quarkus.oidc.auth-server-url", String.class);
        Files.deleteIfExists(STOPP);
        Files.writeString(BEREIT, "{\"api\":\"" + api + "\",\"keycloak\":\"" + keycloak + "\"}\n");
        try {
            Instant ende = Instant.now().plus(Duration.ofMinutes(60));
            while (!Files.exists(STOPP) && Instant.now().isBefore(ende)) {
                anfragenBearbeiten();
                Thread.sleep(200);
            }
        } finally {
            Files.deleteIfExists(BEREIT);
            Files.deleteIfExists(STOPP);
        }
    }

    private boolean imRegister(String code) throws Exception {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    /** Bearbeitet die Anfragen der Testhilfe für den Status; jede bekommt eine Antwort, auch bei einem Fehler. */
    private void anfragenBearbeiten() throws IOException {
        if (!Files.isDirectory(ANFRAGEN)) {
            return;
        }
        List<Path> anfragen;
        try (Stream<Path> dateien = Files.list(ANFRAGEN)) {
            anfragen = dateien.filter(p -> ANFRAGE.matcher(p.getFileName().toString()).matches()).sorted().toList();
        }
        for (Path anfrage : anfragen) {
            String antwort;
            try {
                statusSetzen(json.readTree(anfrage.toFile()));
                antwort = "{\"ok\":true}";
            } catch (Exception e) {
                antwort = json.writeValueAsString(Map.of("ok", false, "fehler", e.getClass().getSimpleName() + ": "
                        + e.getMessage()));
            }
            String name = anfrage.getFileName().toString().replace(".json", "");
            Path entwurf = ANFRAGEN.resolve(name + ".antwort.tmp");
            Files.writeString(entwurf, antwort);
            Files.move(entwurf, ANFRAGEN.resolve(name + ".antwort.json"), StandardCopyOption.ATOMIC_MOVE);
            Files.delete(anfrage);
        }
    }

    /**
     * Setzt ein hochgeladenes Dokument von {@code NEU} auf den Zielstatus, wie die Java-Szenarien: {@code EINGANGSKORB}
     * mit synthetischer PDF/A-Fassung in der Ablage und {@code seiten = 1} ({@code BestaetigenSchritte}),
     * {@code FEHLER} mit Fehlertext ({@code GeneratorSchritte}); der offene Auftrag {@code VERARBEITEN} entfällt.
     */
    private void statusSetzen(JsonNode anfrage) throws Exception {
        String mandant = anfrage.path("mandant").asText();
        String dokument = anfrage.path("dokument").asText();
        String status = anfrage.path("status").asText();
        if (!MANDANTEN.contains(mandant)) {
            throw new IllegalArgumentException("Mandant nicht zulässig");
        }
        UUID id = UUID.fromString(dokument);
        if (!id.toString().equals(dokument)) {
            throw new IllegalArgumentException("Dokument-ID keine UUID");
        }
        if (!ZIELSTATUS.contains(status)) {
            throw new IllegalArgumentException("Zielstatus nicht zulässig");
        }
        MandantCode code = new MandantCode(mandant);
        String schema = code.schema();
        try (Connection verbindung = datenbank.getConnection()) {
            Instant eingang;
            try (PreparedStatement abfrage = verbindung.prepareStatement("select eingang from " + schema
                    + ".dokument where id = ? and status = 'NEU'")) {
                abfrage.setObject(1, id);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Kein Dokument in NEU: " + id);
                    }
                    eingang = zeile.getTimestamp(1).toInstant();
                }
            }
            try (PreparedStatement loeschen = verbindung.prepareStatement("delete from public.auftrag "
                    + "where typ = 'VERARBEITEN' and nutzlast ->> 'dokumentId' = ?")) {
                loeschen.setString(1, id.toString());
                loeschen.executeUpdate();
            }
            PreparedStatement aendern;
            if (status.equals("EINGANGSKORB")) {
                byte[] pdfa = ("%PDF-1.4 synthetisch PDF/A " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
                DateiSchluessel schluessel = DateiSchluessel.pdfa(eingang.atZone(ZUERICH).getYear(), id);
                AbgelegteDatei abgelegt = ablage.ablegenEingang(code, schluessel, new ByteArrayInputStream(pdfa),
                        pdfa.length);
                aendern = verbindung.prepareStatement("update " + schema + ".dokument set status = 'EINGANGSKORB', "
                        + "titel = null, ki_vorschlag = null, sha256_pdfa = ?, s3_pdfa = ?, s3_version_pdfa = ?, "
                        + "seiten = 1 where id = ? and status = 'NEU'");
                aendern.setString(1, abgelegt.sha256());
                aendern.setString(2, schluessel.pfad(Ablageort.EINGANG));
                aendern.setString(3, abgelegt.versionId());
                aendern.setObject(4, id);
            } else {
                aendern = verbindung.prepareStatement("update " + schema + ".dokument set status = 'FEHLER', "
                        + "titel = null, ki_vorschlag = null, fehler = 'Probe: Verarbeitung gescheitert' "
                        + "where id = ? and status = 'NEU'");
                aendern.setObject(1, id);
            }
            try (aendern) {
                if (aendern.executeUpdate() != 1) {
                    throw new IllegalStateException("Status nicht gesetzt: " + id);
                }
            }
        }
    }
}
