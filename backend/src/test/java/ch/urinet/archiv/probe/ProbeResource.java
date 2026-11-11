package ch.urinet.archiv.probe;

import ch.urinet.archiv.dokument.application.DokumentZusatzSpeichern;
import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

/**
 * Probe-Endpunkte nur für Tests (Aufgabe 1.4): liegen unter {@code src/test} und damit nie im Produktionsartefakt
 * (ArchUnit-Regel {@code ArchitekturTest.probeNurInTests}). Sie belegen Mandantentrennung und Rechteprüfung, bevor es
 * echte Endpunkte gibt. Die Probe-Dokumente liegen seit Aufgabe 2.2 als Zeilen in {@code dokument}
 * ({@code ch.urinet.archiv.dokument.Testdokumente}).
 */
@Path("/probe")
public class ProbeResource {

    @Inject
    SecurityIdentity identitaet;

    @Inject
    DokumentZusatzSpeichern zusatzSpeichern;

    /** Körper von {@link #zusatz}: {@code zusatz} als JSON-Text, damit die Probe ihn unverändert weitergibt. */
    public record ZusatzAenderung(String zusatz, String grund) {
    }

    /** Liest eine Dokumentart im Schema des angemeldeten Mandanten; fremde Codes antworten 404 (Req 1.4). */
    @GET
    @Path("/dokumentarten/{code}")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Transactional
    public Map<String, String> dokumentart(@PathParam("code") String code) {
        DokumentartEintrag art = DokumentartEintrag.findById(code);
        if (art == null) {
            throw new NotFoundException();
        }
        return Map.of("code", art.code, "name", art.name);
    }

    /** Steht für Aktion A1 «Erfassen» (Req 2.6); ohne Wirkung. */
    @POST
    @Path("/erfassen")
    @ErfordertAktion(Aktion.A1)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
    public void erfassen() {
        // nur die Rechteprüfung zählt
    }

    /** Liest ein Probe-Dokument (A2; INTERN und PERSONAL schränkt die Sichtbarkeit ein, Req 2.3). */
    @GET
    @Path("/dokumente/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
    public Map<String, String> dokument(@PathParam("id") String id) {
        return Map.of("id", id);
    }

    /** Steht für Aktion A7 «Bestätigen» auf einem Dokument: 404 vor 403 (Req 2.6); ohne Wirkung. */
    @POST
    @Path("/dokumente/{id}/bestaetigen")
    @ErfordertAktion(Aktion.A7)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.BESTAETIGEN)
    public void bestaetigen(@PathParam("id") String id) {
        // nur die Rechteprüfung zählt
    }

    /**
     * Steht für «Metadaten ändern» (A6, {@code PATCH /dokumente/{id}}) mit Protokoll (Req 10.8): legt als Wirkung die
     * Dokumentart {@code art} an. Scheitert das Protokoll, ist auch die Dokumentart nicht angelegt.
     */
    @PATCH
    @Path("/dokumente/{id}")
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.AENDERN)
    @Transactional
    public void aendern(@PathParam("id") String id, @QueryParam("art") String code) {
        DokumentartEintrag art = new DokumentartEintrag();
        art.code = code;
        art.name = code;
        art.vertraulichkeit = "NORMAL";
        art.aktiv = true;
        art.persist();
    }

    /**
     * Steht für «Metadaten ändern» (A6) an den Zusatzfeldern, bis 3.5 den {@code PATCH /dokumente/{id}} bringt: prüft
     * das Schema der Art (Req 9.5) und sichert bei {@code ARCHIVIERT} die vorherige Fassung mit Grund (Req 10.1).
     */
    @PATCH
    @Path("/dokumente/{id}/zusatz")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.AENDERN)
    public Map<String, Integer> zusatz(@PathParam("id") UUID id, @HeaderParam("If-Match") String version,
                                       ZusatzAenderung aenderung) {
        Benutzer benutzer = new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
        int neu = zusatzSpeichern.speichern(benutzer, id, Integer.parseInt(version.replace("\"", "")),
                aenderung.zusatz(), aenderung.grund());
        return Map.of("version", neu);
    }

    /** Unerwarteter Fehler mit vertraulichem Text in der Meldung (Req 16.7): Er darf weder in Antwort noch Log stehen. */
    @GET
    @Path("/fehler/{text}")
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public void fehler(@PathParam("text") String text) {
        throw new IllegalStateException("Beleg " + text);
    }

    /** Benutzer-ID der Anmeldung (Claim {@code sub}, design.md «Sicherheit»). */
    @GET
    @Path("/ich")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, String> ich() {
        return Map.of("id", identitaet.getPrincipal().getName());
    }
}
