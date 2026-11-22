package ch.urinet.archiv.benutzer.adapter.out.keycloak;

import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.KonfliktException;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Beschränkt den Zugriff des Dienstkontos auf die ausdrücklich freigegebene Keycloak-Admin-API (Spec 06, Req 5).
 */
@IfBuildProfile("prod")
@ApplicationScoped
@Typed({IdentitaetsDienst.class, AbgleichIdentitaet.class})
public class KeycloakAdminAdapter implements IdentitaetsDienst, AbgleichIdentitaet {
    static final int MAX_ANTWORT = 1024 * 1024;
    static final Duration WARTEZEIT_PARALLEL = Duration.ofMillis(200);
    static final Duration WIEDERHOLUNGSABSTAND = Duration.ofMillis(200);
    static final Duration FEHLERFENSTER = Duration.ofSeconds(30);
    static final Duration SCHUTZSCHALTER_DAUER = Duration.ofSeconds(30);
    static final Duration TOKEN_PUFFER = Duration.ofSeconds(30);
    static final Duration KOMPENSATION_WARTEZEIT = Duration.ofSeconds(2);
    static final int MAX_PARALLEL = 2;
    static final int MAX_FEHLER = 3;

    private static final Logger LOG = Logger.getLogger(KeycloakAdminAdapter.class);
    private static final Set<String> ARCHIV_ROLLEN = Set.of(
            "inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle");
    private static final Pattern ID = Pattern.compile("(?!\\.+$)[A-Za-z0-9._~-]+");

    private final ObjectMapper json;
    private final Optional<URI> basis;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String einladungsClientId;
    private final String einladungsRedirectUri;
    private final Duration anfrageZeitgrenze;
    private final HttpClient http;
    private final Semaphore plaetze = new Semaphore(MAX_PARALLEL);
    private final Clock clock;
    private final Object tokenSperre = new Object();
    private final Object schutzschalterSperre = new Object();
    private volatile Token token;
    private Instant fehlerfensterBeginn;
    private Instant schutzschalterOffenBis;
    private int fehlerInFolge;

    @Inject
    public KeycloakAdminAdapter(
            ObjectMapper json,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.basis-url") Optional<String> basisUrl,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.realm", defaultValue = "urinet-archiv") String realm,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.client-id",
                    defaultValue = "archiv-verwaltung") String clientId,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.client-secret") Optional<String> clientSecret,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.einladungs-client-id",
                    defaultValue = "archiv-pwa") String einladungsClientId,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.einladungs-redirect-uri")
                    Optional<String> einladungsRedirectUri,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.verbindungs-zeitgrenze",
                    defaultValue = "PT2S") Duration verbindungsZeitgrenze,
            @ConfigProperty(name = "archiv.verwaltung.keycloak.zeitgrenze",
                    defaultValue = "PT5S") Duration anfrageZeitgrenze) {
        this(json, basisUrl, realm, clientId, clientSecret, einladungsClientId, einladungsRedirectUri.orElse(""),
                verbindungsZeitgrenze, anfrageZeitgrenze, Clock.systemUTC());
    }

    KeycloakAdminAdapter(ObjectMapper json, String basisUrl, String realm, String clientId, String clientSecret,
                         String einladungsClientId, String einladungsRedirectUri, Duration verbindungsZeitgrenze,
                         Duration anfrageZeitgrenze, Clock clock) {
        this(json, Optional.ofNullable(basisUrl), realm, clientId, Optional.ofNullable(clientSecret),
                einladungsClientId, einladungsRedirectUri, verbindungsZeitgrenze, anfrageZeitgrenze, clock);
    }

    private KeycloakAdminAdapter(ObjectMapper json, Optional<String> basisUrl, String realm, String clientId,
                                 Optional<String> clientSecret, String einladungsClientId,
                                 String einladungsRedirectUri, Duration verbindungsZeitgrenze,
                                 Duration anfrageZeitgrenze, Clock clock) {
        this.json = json;
        this.basis = basisUrl.filter(url -> !url.isBlank()).map(KeycloakAdminAdapter::basisAdresse);
        this.realm = segment(realm);
        this.clientId = clientId;
        this.clientSecret = clientSecret.filter(secret -> !secret.isBlank()).orElse("");
        this.einladungsClientId = einladungsClientId;
        this.einladungsRedirectUri = einladungsRedirectUri;
        this.anfrageZeitgrenze = anfrageZeitgrenze;
        this.clock = clock;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(verbindungsZeitgrenze).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public List<Benutzer> benutzerSuchen(String mandant, String suche, int seite, int groesse) {
        Objects.requireNonNull(mandant);
        int start = IdentitaetsDienst.seitenanfang(suche, seite, groesse);
        List<Benutzer> ergebnis = new ArrayList<>();
        long offset = 0;
        long passende = 0;
        while (true) {
            // Keycloak ignoriert «q», wenn zugleich «search» gesetzt ist; erst Mandant, dann Suche und Paginierung.
            String query = "q=" + query("mandant:" + mandant) + "&max=" + MAX_SEITENGROESSE + "&first=" + offset;
            JsonNode treffer = json(request("GET", admin("/users"), query, null, true, Set.of()));
            if (!treffer.isArray() || treffer.size() > MAX_SEITENGROESSE) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            for (JsonNode eintrag : treffer) {
                if (mandantPasst(eintrag, mandant) && IdentitaetsDienst.passtSuche(suche,
                        text(eintrag, "username"), text(eintrag, "email"),
                        text(eintrag, "firstName"), text(eintrag, "lastName"))) {
                    if (passende++ >= start) {
                        ergebnis.add(benutzer(eintrag, mandant));
                        if (ergebnis.size() == groesse) {
                            return List.copyOf(ergebnis);
                        }
                    }
                }
            }
            if (treffer.size() < MAX_SEITENGROESSE) {
                return List.copyOf(ergebnis);
            }
            offset += treffer.size();
            if (offset > Integer.MAX_VALUE) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        }
    }

    @Override
    public Benutzer benutzerLesen(String mandant, String id) {
        return benutzer(benutzerAbrufen(mandant, id), mandant);
    }

    @Override
    public List<RealmBenutzer> seite(int seite) {
        int start = IdentitaetsDienst.seitenanfang(null, seite, MAX_SEITENGROESSE);
        String query = "max=" + MAX_SEITENGROESSE + "&first=" + start;
        JsonNode treffer = json(request("GET", admin("/users"), query, null, true, Set.of()));
        if (treffer == null || !treffer.isArray() || treffer.size() > MAX_SEITENGROESSE) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        List<RealmBenutzer> ergebnis = new ArrayList<>();
        for (JsonNode eintrag : treffer) {
            UUID id;
            try {
                String text = eintrag.path("id").asText();
                id = UUID.fromString(text);
                if (!id.toString().equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("Ungültige Kennung");
                }
            } catch (IllegalArgumentException e) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            JsonNode attribut = eintrag.path("attributes").path("mandant");
            List<String> mandanten = new ArrayList<>();
            if (!attribut.isMissingNode() && !attribut.isNull()) {
                if (!attribut.isArray()) {
                    throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                }
                for (JsonNode wert : attribut) {
                    if (!wert.isTextual()) {
                        throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                    }
                    mandanten.add(wert.textValue());
                }
            }
            ergebnis.add(new RealmBenutzer(id, mandanten, rollen(id.toString())));
        }
        return List.copyOf(ergebnis);
    }

    @Override
    public String anlegen(String mandant, Neuanlage neu, Rolle rolle) {
        pruefeRolle(rolle);
        return anlegenIntern(mandant, neu, rolle);
    }

    @Override
    public String anlegenOhneRolle(String mandant, Neuanlage neu) {
        return anlegenIntern(mandant, neu, null);
    }

    private String anlegenIntern(String mandant, Neuanlage neu, Rolle rolle) {
        ObjectNode neuJson = json.createObjectNode()
                .put("username", neu.benutzername())
                .put("email", neu.email())
                .put("firstName", neu.vorname())
                .put("lastName", neu.nachname())
                .put("enabled", true);
        neuJson.set("attributes", json.valueToTree(Map.of("mandant", List.of(mandant))));
        neuJson.set("requiredActions", json.valueToTree(List.of(
                ErforderlicheAktion.VERIFY_EMAIL.name(),
                ErforderlicheAktion.UPDATE_PASSWORD.name(),
                ErforderlicheAktion.CONFIGURE_TOTP.name())));
        Antwort angelegt = request("POST", admin("/users"), null, neuJson, false, Set.of());
        if (angelegt.status() != 201) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        String id;
        try {
            id = letztePfadkomponente(angelegt.header("Location"));
        } catch (IllegalArgumentException e) {
            LOG.errorf("Keycloak-Benutzeranlage unklar: Fehler-ID %s, Ursache %s, HTTP-Status %d.",
                    java.util.UUID.randomUUID(), e.getClass().getSimpleName(), angelegt.status());
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        try {
            if (rolle != null) {
                rolleSetzen(mandant, id, rolle);
            }
            return id;
        } catch (RuntimeException fehler) {
            boolean entfernt = false;
            RuntimeException kompensationsFehler = null;
            try {
                // Kompensation umgeht den Schutzschalter: Sie ist gerade dann nötig, wenn der Dienst ausfällt.
                Antwort loeschung = request("DELETE", user(id), null, null, false, Set.of(204, 404), true);
                if (loeschung.status() != 204 && loeschung.status() != 404) {
                    throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                }
                entfernt = true;
            } catch (RuntimeException kompensation) {
                LOG.error("Kompensation nach Benutzeranlage fehlgeschlagen; Benutzerzustand ist unklar.");
                kompensationsFehler = kompensation;
            }
            AnlageException anlageFehler = new AnlageException(id, entfernt, fehler);
            if (kompensationsFehler != null) {
                anlageFehler.addSuppressed(kompensationsFehler);
            }
            throw anlageFehler;
        }
    }

    @Override
    public void aktualisieren(String mandant, String id, Aenderung aenderung) {
        ObjectNode benutzer = (ObjectNode) benutzerAbrufen(mandant, id);
        if (aenderung.email() != null) {
            if (!aenderung.email().equals(benutzer.path("email").asText(null))) {
                benutzer.put("emailVerified", false);
            }
            benutzer.put("email", aenderung.email());
        }
        if (aenderung.vorname() != null) {
            benutzer.put("firstName", aenderung.vorname());
        }
        if (aenderung.nachname() != null) {
            benutzer.put("lastName", aenderung.nachname());
        }
        nur204(request("PUT", user(id), null, benutzer, false, Set.of(204)));
    }

    @Override
    public void rolleSetzen(String mandant, String id, Rolle rolle) {
        pruefeRolle(rolle);
        benutzerAbrufen(mandant, id);
        // Zielrolle zuerst holen und hinzufügen, erst danach die übrigen Archiv-Rollen entfernen:
        // Bei einem Fehler dazwischen behält der Benutzer mindestens eine gültige Rolle.
        JsonNode ziel = json(request("GET", admin("/roles/" + segment(rolle.code())), null, null, true, Set.of()));
        JsonNode vorher = json(request("GET", user(id) + "/role-mappings/realm", null, null, true, Set.of()));
        if (ziel == null || vorher == null || !vorher.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        List<JsonNode> uebrige = new ArrayList<>();
        boolean zielVorhanden = false;
        for (JsonNode role : vorher) {
            String name = role.path("name").asText();
            if (rolle.code().equals(name)) {
                zielVorhanden = true;
            } else if (ARCHIV_ROLLEN.contains(name)) {
                uebrige.add(role);
            }
        }
        if (!zielVorhanden) {
            nur204(request("POST", user(id) + "/role-mappings/realm", null, json.createArrayNode().add(ziel),
                    false, Set.of(204)));
        }
        if (!uebrige.isEmpty()) {
            nur204(request("DELETE", user(id) + "/role-mappings/realm", null, json.valueToTree(uebrige),
                    false, Set.of(204)));
        }
    }

    @Override
    public void aktivieren(String mandant, String id, boolean aktiv) {
        ObjectNode benutzer = (ObjectNode) benutzerAbrufen(mandant, id);
        benutzer.put("enabled", aktiv);
        nur204(request("PUT", user(id), null, benutzer, false, Set.of(204)));
    }

    @Override
    public void aktionenHinzufuegen(String mandant, String id, Set<ErforderlicheAktion> aktionen) {
        Objects.requireNonNull(aktionen);
        ObjectNode benutzer = (ObjectNode) benutzerAbrufen(mandant, id);
        JsonNode bisher = benutzer.get("requiredActions");
        if (bisher != null && !bisher.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        ArrayNode alle = bisher == null ? json.createArrayNode() : (ArrayNode) bisher;
        for (ErforderlicheAktion aktion : aktionen) {
            if (StreamSupport.stream(alle.spliterator(), false)
                    .noneMatch(wert -> wert.isTextual() && aktion.name().equals(wert.textValue()))) {
                alle.add(aktion.name());
            }
        }
        benutzer.set("requiredActions", alle);
        nur204(request("PUT", user(id), null, benutzer, false, Set.of(204)));
    }

    @Override
    public void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit) {
        benutzerAbrufen(mandant, id);
        String query = "lifespan=" + gueltigkeit.toSeconds()
                + "&client_id=" + query(einladungsClientId);
        if (!einladungsRedirectUri.isBlank()) {
            query += "&redirect_uri=" + query(einladungsRedirectUri);
        }
        nur204(request("PUT", user(id) + "/execute-actions-email", query,
                json.valueToTree(aktionen.stream().map(Enum::name).toList()), false, Set.of(204)));
    }

    @Override
    public void sitzungenBeenden(String mandant, String id) {
        benutzerAbrufen(mandant, id);
        nur204(request("POST", user(id) + "/logout", null, null, false, Set.of(204)));
    }

    @Override
    public void otpEntfernen(String mandant, String id) {
        benutzerAbrufen(mandant, id);
        JsonNode credentials = json(request("GET", user(id) + "/credentials", null, null, true, Set.of()));
        if (!credentials.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        for (JsonNode credential : credentials) {
            if ("otp".equals(credential.path("type").asText())) {
                nur204(request("DELETE", user(id) + "/credentials/" + segment(credential.path("id").asText()),
                        null, null, false, Set.of(204)));
            }
        }
    }

    @Override
    public void sperreAufheben(String mandant, String id) {
        benutzerAbrufen(mandant, id);
        nur204(request("DELETE", admin("/attack-detection/brute-force/users/" + segment(id)),
                null, null, false, Set.of(204)));
    }

    @Override
    public boolean anmeldungGesperrt(String mandant, String id) {
        benutzerAbrufen(mandant, id);
        JsonNode antwort = json(request("GET", admin("/attack-detection/brute-force/users/" + segment(id)),
                null, null, true, Set.of()));
        if (antwort == null || !antwort.path("disabled").isBoolean()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        return antwort.path("disabled").booleanValue();
    }

    @Override
    public void googleLoesen(String mandant, String id) {
        benutzerAbrufen(mandant, id);
        Antwort antwort = request("DELETE", user(id) + "/federated-identity/google", null, null, false, Set.of(204, 404));
        if (antwort.status() != 404) {
            nur204(antwort);
        }
    }

    @Override
    public void loeschen(String mandant, String id) {
        benutzerAbrufen(mandant, id);
        request("DELETE", user(id), null, null, false, Set.of(204));
    }

    @Override
    public void anlageRueckgaengig(String mandant, String id) {
        JsonNode benutzer = json(request("GET", user(id), null, null, true, Set.of(404), true));
        if (benutzer == null || !mandantPasst(benutzer, mandant)) {
            throw fehler(Fehlercode.NICHT_GEFUNDEN);
        }
        nur204(request("DELETE", user(id), null, null, false, Set.of(204), true));
    }

    private static void nur204(Antwort antwort) {
        if (antwort.status() != 204) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
        }
    }

    @Override
    public long benutzerZaehlen(String mandant) {
        return zaehlen(mandant, (id, eintrag) -> {
            JsonNode user = eintrag.path("attributes").has("mandant") ? eintrag : benutzerDirekt(id);
            JsonNode werte = user.path("attributes").path("mandant");
            if (!werte.isMissingNode() && (!werte.isArray() || werte.size() != 1 || !werte.get(0).isTextual())) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            return mandantPasst(user, mandant);
        });
    }

    @Override
    public long inhaberZaehlen(String mandant) {
        return zaehlen(mandant, (id, eintrag) -> {
            JsonNode user = eintrag.has("attributes") && eintrag.has("enabled")
                    && eintrag.has("requiredActions") ? eintrag : benutzerDirekt(id);
            if (!mandantPasst(user, mandant)) {
                return false;
            }
            if (!user.path("enabled").isBoolean()) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            return user.path("enabled").asBoolean() && rollen(id).contains(Rolle.INHABER)
                    && erforderlicheAktionen(user).isEmpty();
        });
    }

    private long zaehlen(String mandant, BiPredicate<String, JsonNode> zaehlt) {
        Objects.requireNonNull(mandant);
        long anzahl = 0;
        long offset = 0;
        while (true) {
            String query = "q=" + query("mandant:" + mandant) + "&max=" + MAX_SEITENGROESSE + "&first=" + offset;
            JsonNode treffer = json(request("GET", admin("/users"), query, null, true, Set.of()));
            if (!treffer.isArray() || treffer.size() > MAX_SEITENGROESSE) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            for (JsonNode eintrag : treffer) {
                String id;
                try {
                    id = segment(eintrag.path("id").asText());
                } catch (IllegalArgumentException e) {
                    throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                }
                if (zaehlt.test(id, eintrag)) {
                    anzahl++;
                }
            }
            if (treffer.size() < MAX_SEITENGROESSE) {
                return anzahl;
            }
            offset += treffer.size();
            if (offset > Integer.MAX_VALUE) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        }
    }

    @Override
    public void verbindungPruefen() {
        pruefeSchutzschalter();
        boolean erlaubt = false;
        try {
            erlaubt = plaetze.tryAcquire(WARTEZEIT_PARALLEL.toMillis(), TimeUnit.MILLISECONDS);
            if (!erlaubt) {
                throw fehler(Fehlercode.UEBERLASTET);
            }
            // Echter Tokenabruf: ein gültiger Cache-Token würde einen Ausfall bis zu rund 270 s verdecken.
            synchronized (tokenSperre) {
                token = null;
            }
            token(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fehler(Fehlercode.UEBERLASTET);
        } finally {
            if (erlaubt) {
                plaetze.release();
            }
        }
    }

    private JsonNode benutzerDirekt(String id) {
        JsonNode benutzer = json(request("GET", user(id), null, null, true, Set.of(404)));
        if (benutzer == null) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        return benutzer;
    }

    private JsonNode benutzerAbrufen(String mandant, String id) {
        JsonNode benutzer = json(request("GET", user(id), null, null, true, Set.of(404)));
        if (benutzer == null) {
            throw fehler(Fehlercode.NICHT_GEFUNDEN);
        }
        if (!mandantPasst(benutzer, mandant)) {
            throw fehler(Fehlercode.NICHT_GEFUNDEN);
        }
        return benutzer;
    }

    private Benutzer benutzer(JsonNode user, String mandant) {
        if (!mandantPasst(user, mandant)) {
            throw fehler(Fehlercode.NICHT_GEFUNDEN);
        }
        String id;
        try {
            id = segment(user.path("id").asText());
        } catch (IllegalArgumentException e) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        return new Benutzer(id, user.path("username").asText(), text(user, "email"),
                text(user, "firstName"), text(user, "lastName"), user.path("enabled").asBoolean(), rollen(id),
                erforderlicheAktionen(user), hatOtp(id), hatGoogle(id));
    }

    private Set<Rolle> rollen(String id) {
        Set<Rolle> rollen = EnumSet.noneOf(Rolle.class);
        JsonNode mappings = json(request("GET", user(id) + "/role-mappings/realm", null, null, true, Set.of()));
        if (!mappings.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        for (JsonNode mapping : mappings) {
            for (Rolle rolle : Rolle.values()) {
                if (rolle.code().equals(mapping.path("name").asText())) {
                    rollen.add(rolle);
                }
            }
        }
        return rollen;
    }

    private Set<ErforderlicheAktion> erforderlicheAktionen(JsonNode user) {
        JsonNode required = user.path("requiredActions");
        if (!required.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        Set<ErforderlicheAktion> aktionen = EnumSet.noneOf(ErforderlicheAktion.class);
        for (JsonNode action : required) {
            if (!action.isTextual()) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            try {
                aktionen.add(ErforderlicheAktion.valueOf(action.textValue()));
            } catch (IllegalArgumentException e) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        }
        return aktionen;
    }

    private boolean hatOtp(String id) {
        JsonNode credentials = json(request("GET", user(id) + "/credentials", null, null, true, Set.of()));
        if (!credentials.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        for (JsonNode credential : credentials) {
            if ("otp".equals(credential.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hatGoogle(String id) {
        JsonNode identitaeten = json(request("GET", user(id) + "/federated-identity",
                null, null, true, Set.of(404)));
        if (identitaeten == null) {
            return false;
        }
        if (!identitaeten.isArray()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        for (JsonNode identitaet : identitaeten) {
            if ("google".equals(identitaet.path("identityProvider").asText())) {
                return true;
            }
        }
        return false;
    }

    private Antwort request(String method, String path, String query, JsonNode body,
                            boolean lesend, Set<Integer> weitereErfolge) {
        return request(method, path, query, body, lesend, weitereErfolge, false);
    }

    /**
     * Zählen für den Schutzschalter nur Transportfehler, Zeitüberschreitungen, 5xx sowie 401/403 des Dienstkontos.
     * Andere 4xx sind Fehler des Aufrufs; 409 wird als {@link KonfliktException} weitergereicht.
     */
    private Antwort request(String method, String path, String query, JsonNode body,
                            boolean lesend, Set<Integer> weitereErfolge, boolean kompensation) {
        pruefePositivliste(method, path);
        if (!kompensation) {
            pruefeSchutzschalter();
        }
        boolean erlaubt = false;
        try {
            long wartezeit = (kompensation ? KOMPENSATION_WARTEZEIT : WARTEZEIT_PARALLEL).toMillis();
            erlaubt = plaetze.tryAcquire(wartezeit, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!erlaubt) {
            throw fehler(Fehlercode.UEBERLASTET);
        }
        try {
            if (!kompensation) {
                pruefeSchutzschalter();
            }
            URI uri = basis().resolve(path + (query == null || query.isBlank() ? "" : "?" + query));
            boolean mitToken = !path.equals(tokenPath());
            boolean erneuert = false;
            while (true) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(anfrageZeitgrenze)
                        .header("Accept", "application/json");
                if (body != null) {
                    builder.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
                } else {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                }
                String verwendet = null;
                if (mitToken) {
                    verwendet = token(!kompensation);
                    builder.header("Authorization", "Bearer " + verwendet);
                }
                HttpResponse<byte[]> response = senden(builder.build(), lesend);
                int status = response.statusCode();
                if (status >= 200 && status < 300 || weitereErfolge.contains(status)) {
                    erfolg();
                    return new Antwort(status, response.body(), response.headers().firstValue("Location").orElse(null));
                }
                if (status >= 500) {
                    fehlgeschlagen();
                    throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
                }
                if (status == 401 && mitToken) {
                    // Gecachter Token ungültig (Neustart, Schlüsselrotation, Widerruf): verwerfen.
                    tokenVerwerfen(verwendet);
                    if (lesend && !erneuert) {
                        erneuert = true;
                        continue;
                    }
                }
                if (status == 401 || status == 403) {
                    fehlgeschlagen();
                } else if (status == 409) {
                    throw new KonfliktException();
                }
                LOG.errorf("Keycloak-Dienstkonto abgelehnt: Fehler-ID %s, Ursache %s, HTTP-Status %d.",
                        java.util.UUID.randomUUID(), "KeycloakAntwort", status);
                throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fehlgeschlagen();
            throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
        } finally {
            plaetze.release();
        }
    }

    private void tokenVerwerfen(String verwendet) {
        synchronized (tokenSperre) {
            Token aktuell = token;
            if (aktuell != null && aktuell.wert().equals(verwendet)) {
                token = null;
            }
        }
    }

    private HttpResponse<byte[]> senden(HttpRequest request, boolean lesend)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response;
        boolean wiederholt = false;
        try {
            response = http.send(request, info -> new BegrenzterBody());
        } catch (IOException e) {
            if (!lesend) {
                throw e;
            }
            wartenVorWiederholung();
            response = http.send(request, info -> new BegrenzterBody());
            wiederholt = true;
        }
        if (lesend && !wiederholt && response.statusCode() >= 500) {
            wartenVorWiederholung();
            response = http.send(request, info -> new BegrenzterBody());
        }
        return response;
    }

    private void wartenVorWiederholung() throws InterruptedException {
        Thread.sleep(WIEDERHOLUNGSABSTAND.toMillis());
    }

    private String token(boolean schalterPruefen) {
        URI adminBasis = basis();
        if (clientSecret.isBlank()) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
        }
        Token aktuell = token;
        if (aktuell != null && aktuell.gueltigBis().isAfter(clock.instant())) {
            return aktuell.wert();
        }
        synchronized (tokenSperre) {
            aktuell = token;
            if (aktuell != null && aktuell.gueltigBis().isAfter(clock.instant())) {
                return aktuell.wert();
            }
            String form = "grant_type=client_credentials&client_id=" + query(clientId)
                    + "&client_secret=" + query(clientSecret);
            HttpRequest request = HttpRequest.newBuilder(adminBasis.resolve(tokenPath()))
                    .timeout(anfrageZeitgrenze)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            try {
                if (schalterPruefen) {
                    pruefeSchutzschalter();
                }
                pruefePositivliste("POST", tokenPath());
                HttpResponse<byte[]> antwort = http.send(request, info -> new BegrenzterBody());
                if (antwort.statusCode() < 200 || antwort.statusCode() >= 300) {
                    fehlgeschlagen();
                    if (antwort.statusCode() >= 500) {
                        throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
                    }
                    LOG.errorf("Keycloak-Dienstkonto konnte kein Token beziehen: Fehler-ID %s, Ursache %s, HTTP-Status %d.",
                            java.util.UUID.randomUUID(), "KeycloakAntwort", antwort.statusCode());
                    throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                }
                JsonNode inhalt = parse(antwort.body());
                if (inhalt == null) {
                    fehlgeschlagen();
                    throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                }
                String accessToken = inhalt.path("access_token").asText();
                long ablauf = inhalt.path("expires_in").asLong();
                if (accessToken.isBlank() || ablauf <= 0) {
                    fehlgeschlagen();
                    throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
                }
                erfolg();
                token = new Token(accessToken, clock.instant().plusSeconds(
                        Math.max(0, ablauf - TOKEN_PUFFER.toSeconds())));
                return accessToken;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                fehlgeschlagen();
                throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
            }
        }
    }

    private JsonNode json(Antwort antwort) {
        return antwort.status() == 404 ? null : parse(antwort.inhalt());
    }

    private JsonNode parse(byte[] inhalt) {
        try {
            return json.readTree(inhalt);
        } catch (IOException e) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
    }

    private byte[] bytes(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
    }

    private boolean mandantPasst(JsonNode user, String mandant) {
        JsonNode werte = user.path("attributes").path("mandant");
        return werte.isArray() && werte.size() == 1 && mandant.equals(werte.get(0).asText());
    }

    private static String text(JsonNode node, String feld) {
        return node.path(feld).isNull() || node.path(feld).isMissingNode() ? null : node.path(feld).asText();
    }

    private String user(String id) {
        return admin("/users/" + segment(id));
    }

    private String admin(String pfad) {
        return "/admin/realms/" + realm + pfad;
    }

    private URI basis() {
        return basis.orElseThrow(() -> fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));
    }

    private static URI basisAdresse(String basisUrl) {
        URI adresse = URI.create(basisUrl.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(adresse.getScheme()) || adresse.getHost() == null
                || adresse.getRawQuery() != null || adresse.getRawFragment() != null
                || adresse.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Ungültige Keycloak-Basisadresse");
        }
        return adresse;
    }

    private String tokenPath() {
        return "/realms/" + realm + "/protocol/openid-connect/token";
    }

    void pruefePositivliste(String method, String path) {
        for (String teil : path.split("/", -1)) {
            String klein = teil.toLowerCase(java.util.Locale.ROOT);
            if (klein.matches("\\.+") || klein.contains("%2e") || klein.contains("%2f") || klein.contains("%5c")) {
                throw new IllegalArgumentException("Nicht freigegebener Keycloak-Aufruf");
            }
        }
        String rootPath = "/admin/realms/" + realm;
        String rootMuster = "/admin/realms/" + Pattern.quote(realm);
        String id = "[A-Za-z0-9._~%-]+";
        String users = rootPath + "/users";
        String usersMuster = rootMuster + "/users";
        boolean erlaubt = path.equals(tokenPath()) && method.equals("POST")
                || path.equals(users) && Set.of("GET", "POST").contains(method)
                || path.matches(usersMuster + "/" + id) && Set.of("GET", "PUT", "DELETE").contains(method)
                || path.matches(usersMuster + "/" + id + "/role-mappings/realm")
                && Set.of("GET", "POST", "DELETE").contains(method)
                || path.matches(rootMuster + "/roles/" + id + "/users") && method.equals("GET")
                || path.matches(usersMuster + "/" + id + "/execute-actions-email") && method.equals("PUT")
                || path.matches(usersMuster + "/" + id + "/logout") && method.equals("POST")
                || path.matches(usersMuster + "/" + id + "/credentials") && method.equals("GET")
                || path.matches(usersMuster + "/" + id + "/credentials/" + id)
                && method.equals("DELETE")
                || path.matches(usersMuster + "/" + id + "/federated-identity") && method.equals("GET")
                || path.matches(usersMuster + "/" + id + "/federated-identity/google")
                && method.equals("DELETE")
                || path.matches(rootMuster + "/attack-detection/brute-force/users/" + id)
                && Set.of("GET", "DELETE").contains(method)
                || path.matches(rootMuster + "/roles/" + id) && method.equals("GET");
        if (!erlaubt) {
            throw new IllegalArgumentException("Nicht freigegebener Keycloak-Aufruf");
        }
    }

    private void pruefeSchutzschalter() {
        synchronized (schutzschalterSperre) {
            Instant jetzt = clock.instant();
            if (schutzschalterOffenBis != null && jetzt.isBefore(schutzschalterOffenBis)) {
                throw fehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
            }
            if (schutzschalterOffenBis != null) {
                schutzschalterOffenBis = null;
                fehlerfensterBeginn = null;
                fehlerInFolge = 0;
            }
        }
    }

    private void fehlgeschlagen() {
        synchronized (schutzschalterSperre) {
            Instant jetzt = clock.instant();
            if (fehlerfensterBeginn == null || jetzt.isAfter(fehlerfensterBeginn.plus(FEHLERFENSTER))) {
                fehlerfensterBeginn = jetzt;
                fehlerInFolge = 0;
            }
            if (++fehlerInFolge >= MAX_FEHLER) {
                schutzschalterOffenBis = jetzt.plus(SCHUTZSCHALTER_DAUER);
            }
        }
    }

    private void erfolg() {
        synchronized (schutzschalterSperre) {
            fehlerfensterBeginn = null;
            fehlerInFolge = 0;
            // schutzschalterOffenBis bleibt unberührt: Ein parallel gestarteter, erfolgreicher Aufruf schliesst
            // den Schalter nicht; das Schliessen übernimmt allein pruefeSchutzschalter() nach Ablauf.
        }
    }

    static String segment(String wert) {
        if (wert == null || !ID.matcher(wert).matches()) {
            throw new IllegalArgumentException("Ungültiger Keycloak-Pfadwert");
        }
        return wert;
    }

    private static String query(String wert) {
        return URLEncoder.encode(wert, StandardCharsets.UTF_8);
    }

    private static String letztePfadkomponente(String location) {
        if (location == null) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
        try {
            String path = URI.create(location).getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            return segment(id);
        } catch (IllegalArgumentException e) {
            throw fehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
    }

    private static void pruefeRolle(Rolle rolle) {
        if (rolle == null || !ARCHIV_ROLLEN.contains(rolle.code())) {
            throw new IllegalArgumentException("Ungültige Archiv-Rolle");
        }
    }

    private static FachFehler fehler(Fehlercode code) {
        return new FachFehler(code);
    }

    private record Token(String wert, Instant gueltigBis) {
    }

    private record Antwort(int status, byte[] inhalt, String location) {
        String header(String name) {
            return "Location".equals(name) ? location : null;
        }
    }

    private static final class BegrenzterBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> inhalt = new CompletableFuture<>();
        private final ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int groesse;

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return inhalt;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> teile) {
            for (ByteBuffer teil : teile) {
                int laenge = teil.remaining();
                if (groesse + laenge > MAX_ANTWORT) {
                    subscription.cancel();
                    inhalt.completeExceptionally(new IOException("Antwortgrenze überschritten"));
                    return;
                }
                byte[] bytes = new byte[laenge];
                teil.get(bytes);
                ausgabe.writeBytes(bytes);
                groesse += laenge;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable fehler) {
            inhalt.completeExceptionally(fehler);
        }

        @Override
        public void onComplete() {
            inhalt.complete(ausgabe.toByteArray());
        }
    }
}
