package ch.urinet.archiv.konfiguration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MandantenIsolationOpenApiTest {

    private static final Path OPENAPI = Path.of("..", "frontend", "openapi", "openapi.yaml");
    private static final Set<String> HTTP_METHODEN = Set.of("get", "post", "put", "patch", "delete");

    private static final Map<String, String> OEFFENTLICHE_AUSNAHMEN = Map.of(
            "/.well-known/webauthn", "liefert nur den konfigurierten Passkey-Origin",
            "/public/v1/chat", "ist durch Webseiten-Schlüssel und eigene Isolationstests geschützt",
            "/public/v1/wissen/chat", "ist durch Webseiten-Schlüssel und eigene Isolationstests geschützt");

    private record Nachweis(String klasse, String methode) { }

    @Test
    @SuppressWarnings("unchecked")
    void jedeParametrisierteOpenApiOperationIstEinemIsolationstestZugeordnet() throws Exception {
        Map<String, Object> spezifikation = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(OPENAPI, StandardCharsets.UTF_8));
        Map<String, Object> pfade = (Map<String, Object>) spezifikation.get("paths");
        Map<String, Nachweis> nachweise = nachweise();
        Set<String> openApiOperationen = openApiOperationen(pfade);

        assertThat(pfade.keySet()).doesNotContainAnyElementsOf(OEFFENTLICHE_AUSNAHMEN.keySet());
        assertThat(openApiOperationen).containsExactlyInAnyOrderElementsOf(nachweise.keySet());
        nachweise.values().stream().distinct().forEach(this::testmethodeExistiert);
    }

    @SuppressWarnings("unchecked")
    private Set<String> openApiOperationen(Map<String, Object> pfade) {
        var operationen = new java.util.LinkedHashSet<String>();
        pfade.forEach((pfad, wert) -> {
            if (!pfad.contains("{")) {
                return;
            }
            ((Map<String, Object>) wert).keySet().stream()
                    .filter(HTTP_METHODEN::contains)
                    .map(methode -> methode.toUpperCase(java.util.Locale.ROOT) + " " + pfad)
                    .forEach(operationen::add);
        });
        return Set.copyOf(operationen);
    }

    private Map<String, Nachweis> nachweise() {
        var nachweise = new LinkedHashMap<String, Nachweis>();
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "apiTokenAblaufUndWiderrufSindMandantengetrennt",
                "DELETE /api/v1/api-tokens/{tokenId}",
                "PUT /api/v1/api-tokens/{tokenId}/gueltig-bis",
                "DELETE /api/v1/api-tokens/{tokenId}/gueltig-bis");
        belege(nachweise, "ch.urinet.archiv.benutzer.VerwaltungIsolationIT",
                "fremdeAntraegeUndProtokolleBleibenImEigenenMandanten",
                "POST /api/v1/benutzer/antraege/{antragId}/ablehnen",
                "POST /api/v1/benutzer/antraege/{antragId}/bestaetigen",
                "POST /api/v1/benutzer/antraege/{antragId}/zurueckziehen");
        belege(nachweise, "ch.urinet.archiv.benutzer.VerwaltungIsolationIT",
                "fremdeBenutzerSindBeiJederZielaktionNichtAuffindbar",
                "GET /api/v1/benutzer/{benutzerId}",
                "PATCH /api/v1/benutzer/{benutzerId}",
                "POST /api/v1/benutzer/{benutzerId}/deaktivieren",
                "POST /api/v1/benutzer/{benutzerId}/einladung",
                "POST /api/v1/benutzer/{benutzerId}/entsperren",
                "DELETE /api/v1/benutzer/{benutzerId}/google-verknuepfung",
                "POST /api/v1/benutzer/{benutzerId}/loeschen",
                "GET /api/v1/benutzer/{benutzerId}/loeschvorschau",
                "POST /api/v1/benutzer/{benutzerId}/reaktivieren",
                "GET /api/v1/benutzer/{benutzerId}/rechte",
                "PUT /api/v1/benutzer/{benutzerId}/rolle",
                "POST /api/v1/benutzer/{benutzerId}/sperren",
                "POST /api/v1/benutzer/{benutzerId}/zugang-zuruecksetzen");
        belege(nachweise, "ch.urinet.archiv.ki.chat.ChatSchritte", "verlaufFehlerGegenprobe",
                "GET /api/v1/chat/gespraeche/{id}");
        belege(nachweise, "ch.urinet.archiv.ki.chat.ChatBewertungSchritte", "fremdeBewertungWieUnbekannt",
                "DELETE /api/v1/chat/nachrichten/{id}/bewertung",
                "GET /api/v1/chat/nachrichten/{id}/bewertung",
                "PUT /api/v1/chat/nachrichten/{id}/bewertung");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "konfigurationsRessourcenSindWieUnbekannteIDsIsoliert",
                "GET /api/v1/dokumentarten/{art}/klassifikationen",
                "POST /api/v1/dokumentarten/{art}/klassifikationen",
                "DELETE /api/v1/dokumentarten/{art}/klassifikationen/{code}",
                "PATCH /api/v1/dokumentarten/{art}/klassifikationen/{code}",
                "DELETE /api/v1/dokumentarten/{code}",
                "PATCH /api/v1/dokumentarten/{code}",
                "DELETE /api/v1/klassifikationen/{code}",
                "PATCH /api/v1/klassifikationen/{code}",
                "POST /api/v1/klassifikationen/{code}/werte",
                "DELETE /api/v1/klassifikationen/{code}/werte/{wert}",
                "PATCH /api/v1/klassifikationen/{code}/werte/{wert}",
                "GET /api/v1/konfiguration/versionen/{nummer}",
                "DELETE /api/v1/regeln/{code}",
                "PATCH /api/v1/regeln/{code}");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "fremdeDokumenteSindWieUnbekannteDokumenteNichtSichtbar",
                "GET /api/v1/dokumente/{id}",
                "PATCH /api/v1/dokumente/{id}",
                "POST /api/v1/dokumente/{id}/bestaetigen",
                "GET /api/v1/dokumente/{id}/datei",
                "POST /api/v1/dokumente/{id}/verwerfen",
                "GET /api/v1/dokumente/{id}/vorschau",
                "POST /api/v1/dokumente/{id}/wiederholen",
                "POST /api/v1/dokumente/{id}/wissen-ingest",
                "POST /api/v1/dokumente/{id}/wissensquelle",
                "DELETE /api/v1/dokumente/{id}/wissensquelle");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "jahresexportIdsSindMandantengetrennt", "GET /api/v1/exporte/{id}");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "importUndWissensRessourcenSindMandantengetrennt",
                "GET /api/v1/importe/{id}",
                "POST /api/v1/importe/{id}/uebernehmen",
                "GET /api/v1/wissensbasis/exporte/{id}",
                "GET /api/v1/wissensentwuerfe/{id}",
                "POST /api/v1/wissensentwuerfe/{id}/neu-pruefen",
                "POST /api/v1/wissensentwuerfe/{entwurfId}/aenderungen/{nr}/annehmen",
                "POST /api/v1/wissensentwuerfe/{entwurfId}/aenderungen/{nr}/neu-erzeugen",
                "POST /api/v1/wissensentwuerfe/{entwurfId}/aenderungen/{nr}/verwerfen");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "fremdeKontakteSindBeiAllenRessourcenaktionenWieUnbekannteKontakte",
                "GET /api/v1/kontakte/{kontaktId}",
                "PATCH /api/v1/kontakte/{kontaktId}",
                "POST /api/v1/kontakte/{kontaktId}/aktiv",
                "POST /api/v1/kontakte/{kontaktId}/inaktiv",
                "GET /api/v1/kontakte/{kontaktId}/verweise",
                "POST /api/v1/kontakte/{kontaktId}/zusammenfuehren");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "austrittEinerFremdenBenutzerkennungIstWieEineUnbekannteKennung",
                "POST /api/v1/protokoll/benutzer/{benutzer}/austritt");
        belege(nachweise, "ch.urinet.archiv.konfiguration.MandantenIsolationIT",
                "wissensseitenBleibenBeimMandantenAusDerAnmeldung",
                "GET /api/v1/wissensseiten/{id}",
                "GET /api/v1/wissensseiten/{id}/diff",
                "GET /api/v1/wissensseiten/{id}/eingehend",
                "POST /api/v1/wissensseiten/{id}/quelle-geprueft",
                "GET /api/v1/wissensseiten/{id}/versionen",
                "GET /api/v1/wissensseiten/{id}/versionen/{nummer}",
                "POST /api/v1/wissensseiten/{id}/webseitenfreigabe",
                "GET /api/v1/wissensseiten/{id}/webseitenfreigabe");
        return Map.copyOf(nachweise);
    }

    private void belege(Map<String, Nachweis> nachweise, String klasse, String methode, String... operationen) {
        Nachweis nachweis = new Nachweis(klasse, methode);
        for (String operation : operationen) {
            assertThat(nachweise.put(operation, nachweis)).as("Doppelter Isolationsnachweis für %s", operation)
                    .isNull();
        }
    }

    private void testmethodeExistiert(Nachweis nachweis) {
        try {
            var methode = Class.forName(nachweis.klasse()).getDeclaredMethod(nachweis.methode());
            boolean testOderSchritt = methode.isAnnotationPresent(Test.class)
                    || Arrays.stream(methode.getDeclaredAnnotations())
                    .anyMatch(annotation -> annotation.annotationType().getPackageName().equals("io.cucumber.java.de"));
            assertThat(testOderSchritt).as("Test- oder Cucumber-Schritt für %s", nachweis).isTrue();
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new AssertionError("Isolationsnachweis fehlt: " + nachweis, e);
        }
    }
}
