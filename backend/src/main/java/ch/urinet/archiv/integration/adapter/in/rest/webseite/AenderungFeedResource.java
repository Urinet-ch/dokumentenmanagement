package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.application.webseite.AenderungFeed;
import ch.urinet.archiv.integration.domain.webseite.ErfassungAenderung;
import ch.urinet.archiv.integration.domain.webseite.FeedSeite;
import ch.urinet.archiv.integration.domain.webseite.LeseAenderung;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.WebseitenErfassungAktion;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Änderungs-Feed der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.9, Req 14) unter {@code /public/v1}: der Lese-Feed
 * für Lese-Schlüssel und der Erfassungs-Feed für Erfassungs-Schlüssel. Beide sind Abfragen der Webseite; das Archiv ruft
 * nie eine Webseite auf (Req 14.5). Kein Eintrag nennt Titel, Dateiname oder Inhalt. Der Lese-Feed gilt für die Stufe
 * des Schlüssels ({@link FeedZugriff}), nicht für eine gemeldete Stufe.
 */
@Path("/public/v1")
@Produces(MediaType.APPLICATION_JSON)
public class AenderungFeedResource {

    private final AenderungFeed feed;
    private final LeseAnfrage anfrage;
    private final SecurityIdentity identitaet;

    public AenderungFeedResource(AenderungFeed feed, LeseAnfrage anfrage, SecurityIdentity identitaet) {
        this.feed = feed;
        this.anfrage = anfrage;
        this.identitaet = identitaet;
    }

    @Schema(name = "WebseiteAenderung", description = "Änderung eines Dokuments, gesehen mit der Stufe des Schlüssels")
    public record AenderungDto(
            @Schema(required = true) UUID dokumentId,
            @Schema(required = true, enumeration = {"SICHTBAR", "GEAENDERT", "NICHT_SICHTBAR"},
                    description = "SICHTBAR: vorher nicht, nachher bis zur Stufe des Schlüssels sichtbar; GEAENDERT: "
                            + "vorher und nachher sichtbar; NICHT_SICHTBAR: vorher sichtbar, nachher nicht mehr "
                            + "(auch bei Wechsel auf eine höhere Stufe). Ohne Inhalt: Details holt die Webseite bei "
                            + "Bedarf.",
                    extensions = @org.eclipse.microprofile.openapi.annotations.extensions.Extension(
                            name = "x-erweiterbar", value = "true", parseValue = true)) String art,
            @Schema(required = true, enumeration = {"oeffentlich", "mitglieder", "vorstand"},
                    description = "Die tiefere der sichtbaren Stufen vor und nach der Änderung, nie über der Stufe des "
                            + "Schlüssels; ab dieser Stufe aufwärts verwirft die Webseite ihren Cache") String stufe,
            @Schema(required = true) Instant zeit) {
    }

    @Schema(name = "WebseiteAenderungen", description = "Änderungen ab dem Cursor")
    public record AenderungenDto(
            @Schema(required = true, description = "Höchstens max Einträge, nach Eintrag geordnet") List<AenderungDto> eintraege,
            @Schema(required = true, description = "Opak; für den nächsten Abruf als seit übergeben, unverändert "
                    + "und ohne ihn zu deuten") String cursor) {
    }

    @Schema(name = "WebseiteErfassungAenderung", description = "Status- oder Metadatenänderung eines Dokuments")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErfassungAenderungDto(
            @Schema(required = true) UUID dokumentId,
            @Schema(nullable = true, description = "Referenz der Webseite des Schlüssels; leer bei Dokumenten ohne "
                    + "Referenz dieser Webseite") String referenz,
            @Schema(nullable = true, description = "Status des Dokuments nach der Änderung; leer bei physischem "
                    + "Löschen") String status,
            @Schema(nullable = true, description = "Stabiler Fehlercode bei einem Fehlerstatus, sonst leer")
            String fehlercode,
            @Schema(nullable = true, description = "Version des Dokuments nach der Änderung (ETag); leer bei "
                    + "physischem Löschen") Integer version,
            @Schema(required = true) Instant zeit) {
    }

    @Schema(name = "WebseiteErfassungAenderungen", description = "Änderungen der Dokumente des Mandanten ab dem Cursor")
    public record ErfassungAenderungenDto(
            @Schema(required = true, description = "Höchstens max Einträge, nach Eintrag geordnet")
            List<ErfassungAenderungDto> eintraege,
            @Schema(required = true, description = "Opak; für den nächsten Abruf als seit übergeben, unverändert "
                    + "und ohne ihn zu deuten") String cursor) {
    }

    @GET
    @Path("/aenderungen")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @FeedZugriff
    @Operation(summary = "Änderungen veröffentlichter Dokumente",
            description = "Änderungen ab dem Cursor, durch die ein Dokument bis zur Stufe des Schlüssels sichtbar "
                    + "wurde, sich als sichtbares Dokument änderte oder nicht mehr sichtbar ist; sichtbar heisst wie im "
                    + "festen Filter (archiviert, veröffentlicht, Vertraulichkeit NORMAL). Es gilt die Stufe des "
                    + "Schlüssels; X-Archiv-Zugriff und X-Archiv-Client werden nicht gelesen. Jeder Eintrag erscheint "
                    + "genau einmal, nie vor dem Ende seiner Transaktion. Ohne seit liefert der Feed keine Einträge, "
                    + "nur den aktuellen Cursor; erst den Cursor holen, dann den Bestand laden, dann ab dem Cursor "
                    + "lesen. Ein Cursor älter als die Aufbewahrung (90 Tage) ergibt 410 CURSOR_ABGELAUFEN; die "
                    + "Webseite verwirft dann alles und beginnt ohne Cursor.")
    @APIResponse(responseCode = "200", description = "Änderungen und nächster Cursor",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AenderungenDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "410", ref = "CursorAbgelaufen")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public AenderungenDto aenderungen(
            @QueryParam("seit") @Parameter(description = "Cursor der letzten Antwort; fehlt er, kommen keine Einträge, "
                    + "nur der aktuelle Cursor") String seit,
            @QueryParam("max") @Parameter(description = "Höchstzahl der Einträge, 1 bis 500 (Standard 100)",
                    schema = @Schema(type = SchemaType.INTEGER, format = "int32", minimum = "1", maximum = "500"))
            String max) {
        FeedSeite<LeseAenderung> seite = feed.lesen(anfrage.zugriff(), cursor(seit), AenderungFeed.max(zahl(max)));
        return new AenderungenDto(seite.eintraege().stream()
                .map(a -> new AenderungDto(a.dokumentId(), a.art().name(), a.stufe().code(), a.zeit())).toList(),
                seite.cursor().kodieren());
    }

    @GET
    @Path("/erfassung/aenderungen")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A2)
    @Operation(summary = "Änderungen der Dokumente des Mandanten (Erfassung)",
            description = "Jede Status- und Metadatenänderung der Dokumente des Mandanten ab dem Cursor mit ID, "
                    + "Referenz, Status, Fehlercode und Version; die Referenz nur für Dokumente der Webseite des "
                    + "Schlüssels, sonst leer. Cursor, Reihenfolge, max und 410 wie beim Feed der Lese-Schlüssel. Kein "
                    + "Titel, kein Dateiname, kein Inhalt.")
    @APIResponse(responseCode = "200", description = "Änderungen und nächster Cursor",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErfassungAenderungenDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "410", ref = "CursorAbgelaufen")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public ErfassungAenderungenDto erfassungAenderungen(
            @QueryParam("seit") @Parameter(description = "Cursor der letzten Antwort; fehlt er, kommen keine Einträge, "
                    + "nur der aktuelle Cursor") String seit,
            @QueryParam("max") @Parameter(description = "Höchstzahl der Einträge, 1 bis 500 (Standard 100)",
                    schema = @Schema(type = SchemaType.INTEGER, format = "int32", minimum = "1", maximum = "500"))
            String max) {
        String webseite = Optional.ofNullable(identitaet.getAttribute(TokenIdentitaet.WEBSEITE)).map(Object::toString)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_ERLAUBT));
        FeedSeite<ErfassungAenderung> seite = feed.erfassung(webseite, cursor(seit), AenderungFeed.max(zahl(max)));
        return new ErfassungAenderungenDto(seite.eintraege().stream()
                .map(a -> new ErfassungAenderungDto(a.dokumentId(), a.referenz(), a.status(), a.fehlercode(),
                        a.version(), a.zeit())).toList(),
                seite.cursor().kodieren());
    }

    /** Ein leerer Wert gilt wie ein fehlender: kein Cursor, nur der aktuelle Horizont. */
    private static Optional<String> cursor(String seit) {
        return seit == null || seit.isEmpty() ? Optional.empty() : Optional.of(seit);
    }

    private static Integer zahl(String wert) {
        if (wert == null) {
            return null;
        }
        try {
            return Integer.parseInt(wert);
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
