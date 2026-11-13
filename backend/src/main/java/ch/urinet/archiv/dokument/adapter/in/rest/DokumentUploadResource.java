package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.DateiName;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAbbildung;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAntwort;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;
import org.jboss.resteasy.reactive.server.core.multipart.FormData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload {@code POST /dokumente} (A1, Req 3, 4.7; design.md «Upload»): Originale in {@code dateien}, von der Handy-App
 * dazu die Arbeitsfassungen in {@code arbeitsfassungen} in derselben Reihenfolge. Quarkus legt jeden Teil als temporäre
 * Datei ab ({@code quarkus.http.body.uploads-directory}) und löscht sie am Ende der Anfrage. Ein API-Token mit Scope
 * {@code import} (Drive-Import) erreicht nur diesen Endpunkt (Req 2.5).
 */
@Path("/dokumente")
@Tag(name = "Dokumente")
public class DokumentUploadResource {

    private static final Logger LOG = Logger.getLogger(DokumentUploadResource.class);

    static final String QUELLE_HANDY = DokumentErfassen.QUELLE_SCAN;

    private final DokumentErfassen erfassen;
    private final SecurityIdentity identitaet;

    public DokumentUploadResource(DokumentErfassen erfassen, SecurityIdentity identitaet) {
        this.erfassen = erfassen;
        this.identitaet = identitaet;
    }

    @Schema(name = "UploadFehler", description = "Fehler einer einzelnen Datei als Auszug aus Problem Details")
    public record UploadFehlerDto(@Schema(required = true) int status,
                                  @Schema(required = true) String fehlercode,
                                  @Schema(required = true) String detail) {
    }

    @Schema(name = "UploadErgebnis", description = "Ergebnis einer Datei (Req 3.2, 3.4, 3.8)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UploadErgebnisDto(
            @Schema(required = true, description = "Dateiname ohne Pfad, nur zur Anzeige") String datei,
            @Schema(required = true, enumeration = {"NEU", "DUBLETTE", "ABGELEHNT"}) String status,
            @Schema(description = "Neues Dokument (bei NEU)") UUID id,
            @Schema(description = "Vorhandenes Dokument (bei DUBLETTE); fehlt ohne Leserecht (Req 2.3)")
            UUID vorhandenId,
            @Schema(description = "«bereits vorhanden», wenn vorhandenId fehlt") String meldung,
            @Schema(description = "Grund der Ablehnung (bei ABGELEHNT)") UploadFehlerDto fehler) {
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A1)
    @ErlaubtScope(ApiTokenScope.IMPORT)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
    @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
    @Transactional
    @TransactionConfiguration(timeoutFromConfigProperty = "archiv.upload.transaktion-zeitgrenze")
    @Operation(summary = "Dateien hochladen",
            description = "PDF, JPEG, PNG, HEIC/HEIF und TIFF bis 50 MB je Datei; je Datei ein Ergebnis (Req 3). "
                    + "Sind alle Dateien abgelehnt, folgt Problem Details mit dem Status der ersten Ablehnung und "
                    + "der Erweiterung «ergebnisse».")
    @APIResponse(responseCode = "202", description = "Mindestens eine Datei angenommen oder als Dublette erkannt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = UploadErgebnisDto.class)))
    @APIResponse(responseCode = "400", description = "Anfrage ungültig (keine Datei, Hinweis zu lang, Anzahl "
            + "Arbeitsfassungen passt nicht) oder alle Dateien abgelehnt, erste mit Steuerzeichen im Namen")
    @APIResponse(responseCode = "413", description = "Anfrage über archiv.upload.max-anfrage oder alle Dateien "
            + "abgelehnt, erste über 50 MB")
    @APIResponse(responseCode = "415", description = "Alle Dateien abgelehnt, erste mit unbekanntem Typ")
    @APIResponse(responseCode = "422", description = "Alle Dateien abgelehnt, erste leer oder beschädigt")
    @APIResponse(responseCode = "503", description = "Speicher nicht erreichbar; nichts wurde angelegt",
            headers = @Header(name = "Retry-After", description = "Sekunden bis zum nächsten Versuch"))
    public Response hochladen(@RestForm("dateien") List<FileUpload> dateien,
                              @RestForm("arbeitsfassungen") List<FileUpload> arbeitsfassungen,
                              @RestForm("zusammenfassen") boolean zusammenfassen,
                              @RestForm("hinweis") String hinweis) {
        List<UploadErgebnis> ergebnisse = new ArrayList<>(erfassen(dateien, arbeitsfassungen, zusammenfassen,
                hinweis));
        List<UploadErgebnisDto> antwort = ergebnisse.stream().map(DokumentUploadResource::dto).toList();
        if (ergebnisse.stream().allMatch(UploadErgebnis.Abgelehnt.class::isInstance)) {
            Fehlercode erster = ((UploadErgebnis.Abgelehnt) ergebnisse.getFirst()).fehler();
            return ProblemAbbildung.antwort(ProblemAntwort.mitErweiterung(erster, "ergebnisse", antwort));
        }
        return Response.accepted(antwort).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    /**
     * Ein Teil {@code dateien} ohne Dateinamen mit einem Typ ausserhalb von
     * {@code quarkus.http.body.multipart.file-content-types} kommt bei Quarkus als Textfeld an, nicht in
     * {@code List<FileUpload>}. Er erhält an seiner Stelle ein eigenes Ergebnis 415 {@code DATEITYP_UNBEKANNT}, die
     * Nachbarn werden angenommen (Req 3.3, 3.8); mit Arbeitsfassungen ist die Zuordnung nicht eindeutig (400).
     */
    private List<UploadErgebnis> erfassen(List<FileUpload> dateien, List<FileUpload> arbeitsfassungen,
                                          boolean zusammenfassen, String hinweis) {
        List<FileUpload> originale = dateien == null ? List.of() : dateien;
        List<FileUpload> arbeit = arbeitsfassungen == null ? List.of() : arbeitsfassungen;
        List<Boolean> teile = dateiTeile("dateien", originale.size());
        if (!arbeit.isEmpty() && (teile.contains(false) || dateiTeile("arbeitsfassungen", arbeit.size())
                .contains(false) || arbeit.size() != originale.size())) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        List<UploadErgebnis> ergebnisse = List.of();
        if (!originale.isEmpty() || !teile.contains(false)) {
            List<UploadAnfrage.Seite> seiten = new ArrayList<>();
            for (int i = 0; i < originale.size(); i++) {
                FileUpload original = originale.get(i);
                Optional<java.nio.file.Path> fassung = arbeit.isEmpty()
                        ? Optional.empty() : Optional.of(arbeit.get(i).uploadedFile());
                seiten.add(new UploadAnfrage.Seite(original.uploadedFile(), original.fileName(), fassung));
            }
            ergebnisse = erfassen.erfassen(new UploadAnfrage(seiten, zusammenfassen, Optional.ofNullable(hinweis),
                    erfasser(!arbeit.isEmpty())));
        }
        if (!teile.contains(false)) {
            return ergebnisse;
        }
        LOG.infof("Teil ohne Dateinamen abgelehnt: teile=%d ergebnis=%s", teile.stream().filter(t -> !t).count(),
                Fehlercode.DATEITYP_UNBEKANNT);
        List<UploadErgebnis> alle = new ArrayList<>();
        int naechste = 0;
        for (boolean datei : teile) {
            alle.add(datei ? ergebnisse.get(naechste++)
                    : new UploadErgebnis.Abgelehnt(DateiName.ERSATZ, Fehlercode.DATEITYP_UNBEKANNT));
        }
        return alle;
    }

    /** Je Teil des Formularfelds in der gesendeten Reihenfolge, ob er als Datei ankam. */
    private static List<Boolean> dateiTeile(String name, int dateien) {
        FormData formular = CurrentRequestManager.get().getFormData();
        var teile = formular == null ? null : formular.get(name);
        if (teile == null) {
            return Collections.nCopies(dateien, true);
        }
        List<Boolean> arten = teile.stream().map(t -> t.isFileItem()).toList();
        if (arten.stream().filter(t -> t).count() != dateien) {
            throw new IllegalStateException("Teile und Dateien passen nicht zusammen");
        }
        return arten;
    }

    private UploadAnfrage.Erfasser erfasser(boolean mitArbeitsfassung) {
        String id = identitaet.getPrincipal().getName();
        if (identitaet.getAttribute(TokenIdentitaet.SCOPE) != null) {
            return new UploadAnfrage.Erfasser(id, Optional.empty(), DokumentErfassen.QUELLE_DRIVE);
        }
        return new UploadAnfrage.Erfasser(id, Optional.of(DokumentResource.benutzer(identitaet)),
                mitArbeitsfassung ? QUELLE_HANDY : DokumentErfassen.QUELLE_UPLOAD);
    }

    static UploadErgebnisDto dto(UploadErgebnis ergebnis) {
        return switch (ergebnis) {
            case UploadErgebnis.Angenommen a -> new UploadErgebnisDto(a.datei(), "NEU", a.id(), null, null, null);
            case UploadErgebnis.Dublette d -> new UploadErgebnisDto(d.datei(), "DUBLETTE", null,
                    d.vorhandenId().orElse(null), d.vorhandenId().isPresent() ? null : "bereits vorhanden", null);
            case UploadErgebnis.Abgelehnt a -> new UploadErgebnisDto(a.datei(), "ABGELEHNT", null, null, null,
                    new UploadFehlerDto(a.fehler().status(), a.fehler().name(), a.fehler().detail()));
        };
    }
}
