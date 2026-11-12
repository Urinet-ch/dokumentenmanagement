package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.dokument.application.Bestaetigung;
import ch.urinet.archiv.dokument.application.DokumentGenerator;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * Generator {@code POST /dokumente/generator} (A1 mit Scope {@code generator}, Req 11; design.md «Generator-Pfad»):
 * eine PDF in {@code datei} und die Metadaten als JSON-Text in {@code metadaten}. Nur API-Tokens vom Scope
 * {@code generator} (Req 11.1): Ein Benutzer kommt durch den Filter (A1), erhält hier aber 403, weil die Metadaten
 * Vertraulichkeit und Person setzen und das Dokument ohne Bestätigung archivieren (Req 2.23, A7, A8).
 */
@Path("/dokumente/generator")
@Tag(name = "Dokumente")
public class DokumentGeneratorResource {

    private final DokumentGenerator generator;
    private final SecurityIdentity identitaet;
    private final Berechtigung berechtigung;
    private final ObjectReader leser;

    public DokumentGeneratorResource(DokumentGenerator generator, SecurityIdentity identitaet,
                                     Berechtigung berechtigung, ObjectMapper mapper) {
        this.generator = generator;
        this.identitaet = identitaet;
        this.berechtigung = berechtigung;
        // ein Tippfehler im Feldnamen soll nicht still verloren gehen
        this.leser = mapper.readerFor(GeneratorMetadatenDto.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Schema(name = "GeneratorPartner", description = "Partner aus 21.CRM; massgeblich ist nur crmId (Req 11.7)")
    public record PartnerDto(@Schema(description = "ID des Kontakts aus 21.CRM") String crmId,
                             @Schema(description = "Nur zur Lesbarkeit; das Archiv übernimmt den Namen des Kontakts")
                             String name) {
    }

    @Schema(name = "GeneratorFinanz", description = "Nur bei Arten mit der Gruppe finanz; sonst ignoriert")
    public record FinanzDto(String belegnummer,
                            @Schema(enumeration = {"EINNAHME", "AUSGABE"}) String richtung,
                            @Schema(type = SchemaType.STRING, pattern = "^-?\\d{1,10}(\\.\\d{1,2})?$",
                                    examples = "408.00") BigDecimal betrag,
                            @Schema(pattern = "^[A-Z]{3}$", description = "Standard CHF") String waehrung,
                            LocalDate faelligkeit, String qrReferenz, String konto) {
    }

    @Schema(name = "GeneratorVertrag", description = "Nur bei Arten mit der Gruppe vertrag; sonst ignoriert")
    public record VertragDto(LocalDate gueltigAb, LocalDate gueltigBis, String kuendigungsfrist,
                             LocalDate naechsteFrist) {
    }

    @Schema(name = "GeneratorMetadaten", description = "Metadaten des Generators (design.md, Beispiele). Pflicht sind "
            + "art und die Pflichtfelder der Art; bei Arten mit Standard PERSONAL zusätzlich vertraulichkeit und "
            + "personBenutzer (Req 11.10), sonst gilt ohne Angabe die Standard-Vertraulichkeit der Art.")
    public record GeneratorMetadatenDto(@Schema(required = true) String art, String titel, LocalDate datum,
                                        @Schema(enumeration = {"NORMAL", "INTERN", "PERSONAL"}) String vertraulichkeit,
                                        @Schema(description = "Keycloak-ID (sub) des betroffenen Mitarbeiters, "
                                                + "nicht der Benutzername") String personBenutzer,
                                        PartnerDto partner, FinanzDto finanz, VertragDto vertrag,
                                        List<String> tags, String projekt,
                                        @Schema(description = "Ohne Angabe Vorschlag aus Belegdatum (Req 9.6)")
                                        Integer geschaeftsjahr,
                                        @Schema(type = SchemaType.OBJECT,
                                                description = "Zusatzfelder nach dem Schema der Art")
                                        JsonNode zusatz) {
    }

    @Schema(name = "GeneratorAntwort")
    public record GeneratorAntwortDto(@Schema(required = true) UUID id,
                                      @Schema(required = true, description = "NEU bei 202, sonst der aktuelle Status "
                                              + "des vorhandenen Dokuments") String status) {
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A1)
    @ErlaubtScope(ApiTokenScope.GENERATOR)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_GENERATOR})
    @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
    @Transactional
    @TransactionConfiguration(timeoutFromConfigProperty = "archiv.upload.transaktion-zeitgrenze")
    @Operation(summary = "PDF mit Metadaten aus einem Generator ablegen",
            description = "Nur mit API-Token vom Scope generator (Req 11.1). Das Dokument wird ohne KI verarbeitet "
                    + "und archiviert (Req 11.2); 202 heisst angenommen, Status und Fehler zeigt das Dokument.")
    @APIResponse(responseCode = "202", description = "Angenommen; Verarbeitung und Archivierung folgen im Worker",
            headers = @Header(name = "Location", description = "URL des Dokuments"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = GeneratorAntwortDto.class)))
    @APIResponse(responseCode = "200", description = "Dieselbe PDF liegt schon im Archiv (Req 11.8)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = GeneratorAntwortDto.class)))
    @APIResponse(responseCode = "400", description = "Metadaten kein gültiges JSON, unbekanntes Feld, unbekannte "
            + "Art oder ungültiger Wert (Richtung, Währung, Betrag, Vertraulichkeit)")
    @APIResponse(responseCode = "403", description = "Kein API-Token vom Scope generator")
    @APIResponse(responseCode = "409", description = "BELEGNUMMER_VERGEBEN (Req 11.3) oder FRIST_ABGELAUFEN "
            + "(Req 9.8); nichts angelegt, nicht erneut senden (Req 11.9)")
    @APIResponse(responseCode = "413", description = "Datei über 50 MB oder Metadaten über 4 KB")
    @APIResponse(responseCode = "415", description = "Keine PDF")
    @APIResponse(responseCode = "422", description = "PFLICHTFELD_FEHLT mit Liste (Req 11.6, 11.10), "
            + "PARTNER_UNBEKANNT (Req 11.7), ZUSATZ_UNGUELTIG oder Datei leer bzw. beschädigt")
    @APIResponse(responseCode = "503", description = "Speicher nicht erreichbar; nichts wurde angelegt",
            headers = @Header(name = "Retry-After", description = "Sekunden bis zum nächsten Versuch"))
    public Response ablegen(@RestForm("datei") @Schema(description = "PDF bis 50 MB") FileUpload datei,
                            @RestForm("metadaten") @Schema(description = "GeneratorMetadaten als JSON-Text")
                            String metadaten,
                            @Context UriInfo uri) {
        String principal = identitaet.getPrincipal().getName();
        if (identitaet.getAttribute(TokenIdentitaet.SCOPE) == null) {
            berechtigung.ohneZugang(Optional.of(principal), Rolle.ausRealmRollen(identitaet.getRoles()),
                    Optional.of(Aktion.A1));
        }
        if (datei == null || metadaten == null || metadaten.isBlank()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        // die Metadaten erst nach der Prüfsumme lesen (Regel 3)
        DokumentGenerator.Ergebnis ergebnis = generator.annehmen(principal, datei.uploadedFile(),
                () -> anfrage(metadaten));
        GeneratorAntwortDto antwort = new GeneratorAntwortDto(ergebnis.id(), ergebnis.status().name());
        if (!ergebnis.neu()) {
            return Response.ok(antwort).type(MediaType.APPLICATION_JSON_TYPE).build();
        }
        return Response.accepted(antwort).type(MediaType.APPLICATION_JSON_TYPE)
                .location(uri.getBaseUriBuilder().path("dokumente").path(ergebnis.id().toString()).build())
                .build();
    }

    private DokumentGenerator.Anfrage anfrage(String metadaten) {
        GeneratorMetadatenDto m;
        try {
            m = leser.readValue(metadaten);
        } catch (JacksonException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (m == null || m.zusatz() != null && !m.zusatz().isNull() && !m.zusatz().isObject()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        FinanzDto f = m.finanz() != null ? m.finanz() : new FinanzDto(null, null, null, null, null, null, null);
        VertragDto v = m.vertrag() != null ? m.vertrag() : new VertragDto(null, null, null, null);
        Bestaetigung angaben = new Bestaetigung(m.art(), m.titel(), m.datum(), null, m.projekt(), m.tags(),
                m.vertraulichkeit(), m.personBenutzer(), m.geschaeftsjahr(), f.belegnummer(), f.richtung(), f.betrag(),
                f.waehrung(), f.faelligkeit(), f.qrReferenz(), f.konto(), v.gueltigAb(), v.gueltigBis(),
                v.kuendigungsfrist(), v.naechsteFrist(), false, false);
        JsonNode zusatz = m.zusatz() == null || m.zusatz().isNull() ? null : m.zusatz();
        return new DokumentGenerator.Anfrage(angaben, m.partner() == null ? null : m.partner().crmId(),
                zusatz == null ? null : zusatz.toString(), zusatzFelder(zusatz));
    }

    /** Schlüssel mit Wert, wie {@code DokumentStatusPort.Stand#zusatzFelder}: ohne null, "", [] und {}. */
    private static Set<String> zusatzFelder(JsonNode zusatz) {
        Set<String> felder = new LinkedHashSet<>();
        if (zusatz == null) {
            return felder;
        }
        zusatz.properties().forEach(feld -> {
            JsonNode wert = feld.getValue();
            boolean leer = wert.isNull() || wert.isTextual() && wert.asText().isEmpty()
                    || wert.isContainerNode() && wert.isEmpty();
            if (!leer) {
                felder.add(feld.getKey());
            }
        });
        return felder;
    }
}
