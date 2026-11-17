package ch.urinet.archiv.probe;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.adapter.in.rest.webseite.LeseAnfrage;
import ch.urinet.archiv.integration.adapter.in.rest.webseite.WebseitenGrenze;
import ch.urinet.archiv.integration.domain.webseite.Grenzklasse;
import ch.urinet.archiv.integration.application.webseite.FesterFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Probe der Webseiten-Schnittstelle nur für Tests (Spec 03, Aufgabe 11.6): erreichbar unter {@code /public/v1/probe}
 * über den Reroute von {@code WebseitenRoute}; liegt unter {@code src/test} im Paket {@code probe} und damit weder in der
 * App-OpenAPI noch im Vertrag {@code public-v1.yaml}. Belegt Problem Details, {@code Cache-Control} und die Header der
 * Abkündigung, bevor es echte Endpunkte gibt.
 * <p>
 * Alle Endpunkte verlangen den passenden Scope; auch die Test-Resource lässt keine OIDC-Rolle zu.
 */
@Path("/public/v1/probe")
public class ProbeWebseiteResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    public Map<String, String> lesen() {
        return Map.of("probe", "ok");
    }

    @POST
    @Path("/erfassung")
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    public Map<String, String> erfassen() {
        return Map.of("probe", "erfasst");
    }

    /** Aufgabe 11.11: stuft wie die Suche ein (teure Anfrage), ohne Datenbank. */
    @GET
    @Path("/teuer")
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @WebseitenGrenze(Grenzklasse.SUCHE)
    public Map<String, String> teuer() {
        return Map.of("probe", "teuer");
    }

    /** Ungültiger Parameter → 400 {@code ANFRAGE_UNGUELTIG}, ohne den Wert zu nennen. */
    @GET
    @Path("/zahl")
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    public Map<String, Integer> zahl(@QueryParam("wert") String wert) {
        try {
            return Map.of("wert", Integer.parseInt(wert));
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    /** Unerwarteter Fehler mit dem Text in der Meldung: er darf nicht in der Antwort stehen. */
    @GET
    @Path("/fehler/{text}")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    public void fehler(@PathParam("text") String text) {
        throw new IllegalStateException("Probe " + text);
    }

    /** Nimmt eine Anfrage mit Inhalt an; belegt 413 vor der Resource. */
    @POST
    @Path("/inhalt")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    public void inhalt(String inhalt) {
        // nur die Grenze der Anfrage zählt
    }

    @Inject
    LeseAnfrage anfrage;

    @Inject
    Instance<EntityManager> emQuelle;

    /** Aufgabe 11.1: gemeldete und wirksame Stufe der Anfrage und die Stufen für den Filter. */
    @GET
    @Path("/stufe")
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    public Map<String, Object> stufe() {
        Lesezugriff z = anfrage.zugriff();
        return Map.of("gemeldet", z.gemeldeteStufe().code(), "hoechste", z.hoechsteStufe().code(),
                "wirksam", z.wirksameStufe().code(), "stufen", z.stufen(), "webseite", z.webseite(),
                "clientHash", z.clientHash().orElse(""));
    }

    /** Aufgabe 11.1: Titel der Dokumente, die der feste Filter für die Anfrage liefert (nur Testdokumente mit Titel). */
    @GET
    @Path("/dokumente")
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Transactional
    @SuppressWarnings("unchecked")
    public List<String> dokumente() {
        return emQuelle.get().createNativeQuery("select d.titel from dokument d where d.titel like 'stufentest-%' and "
                        + FesterFilter.bedingung("d") + " order by d.titel")
                .setParameter(FesterFilter.PARAMETER, FesterFilter.stufen(anfrage.zugriff())).getResultList();
    }

    /** Aufgabe 11.1: ein Dokument über den festen Filter; was er nicht liefert, ist 404 {@code NICHT_GEFUNDEN}. */
    @GET
    @Path("/dokumente/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, String> dokument(@PathParam("id") String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        List<String> titel = emQuelle.get().createNativeQuery("select d.titel from dokument d where d.id = :id and "
                        + FesterFilter.bedingung("d"))
                .setParameter("id", uuid)
                .setParameter(FesterFilter.PARAMETER, FesterFilter.stufen(anfrage.zugriff())).getResultList();
        if (titel.isEmpty()) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        return Map.of("titel", titel.getFirst());
    }
}
