package ch.urinet.archiv.integration;

import ch.urinet.archiv.ArchivMain;
import ch.urinet.archiv.integration.adapter.in.rest.webseite.gegenprobe.GegenprobeAdapterResource;
import ch.urinet.archiv.integration.webseite.gegenprobe.GegenprobeResource;
import ch.urinet.archiv.probe.ProbeWebseiteResource;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 03, design.md «Herkunft der Beschreibung»: {@code mp.openapi.scan.exclude.packages} aus
 * {@code application.properties} hält die Webseiten-Schnittstelle aus der App-OpenAPI ({@code frontend/openapi/}),
 * auch in Unterpaketen; andere Pakete bleiben drin. Gescannt wird wie in {@link PublicVertragTest} ohne Quarkus-Start.
 */
class OpenApiAusschlussTest {

    @Test
    void ausschlussGiltAuchFuerUnterpakete() throws Exception {
        Path klassen = Path.of(ArchivMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, String> einstellungen = PublicVertrag.appEinstellungen(klassen.resolve("application.properties"),
                true);
        assertThat(einstellungen.get("mp.openapi.scan.exclude.packages").split(","))
                .contains("ch.urinet.archiv.integration.webseite", "ch.urinet.archiv.integration.adapter.in.rest.webseite");

        JsonNode app = PublicVertrag.ausCode(PublicVertrag.mit(PublicVertrag.index(klassen), GegenprobeResource.class,
                GegenprobeResource.Eintrag.class, GegenprobeAdapterResource.class, ProbeWebseiteResource.class, Kontrolle.class), einstellungen);

        List<String> pfade = new ArrayList<>();
        app.path("paths").fieldNames().forEachRemaining(pfade::add);
        assertThat(pfade).contains("/public/v1/kontrolle", "/dokumente")
                .doesNotContain("/public/v1/gegenprobe/{id}", "/public/v1/gegenprobe-adapter", "/public/v1/probe");
    }

    /** Kontrolle ausserhalb der ausgeschlossenen Pakete: bleibt in der Beschreibung. */
    @IfBuildProperty(name = GegenprobeResource.SCHALTER, stringValue = "an")
    @jakarta.ws.rs.Path("/public/v1/kontrolle")
    static class Kontrolle {
        @GET
        @AlleArchivRollen
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public String lesen() {
            return "kontrolle";
        }
    }
}
