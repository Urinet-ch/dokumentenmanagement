package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.application.NichtDokumentbezogen;
import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import ch.urinet.archiv.sicherheit.application.WebseitenErfassungAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wächter für Req 2.6: Jede Ressourcen-Methode erklärt ihre Aktion ({@link ErfordertAktion}) oder ist ausdrücklich als
 * {@link AlleArchivRollen} bzw. {@link Oeffentlich} markiert. {@code @RolesAllowed} nennt bei dokumentbezogenen
 * Aktionen (A2–A10) alle Archiv-Rollen, damit der {@code ZugangFilter} 404 vor 403 prüfen kann; bei den übrigen Aktionen
 * genau die Rollen, für die die Rechtematrix nicht 403 ergibt. Eine dokumentbezogene Aktion mit Pfad-Parameter muss das
 * Dokument als {@code {id}} führen, sonst fiele die Sichtbarkeitsprüfung weg (fail closed).
 * <p>
 * Req 10.2: Jede Methode, deren Aktion in der Tabelle «Protokoll und Manifest» (design.md) steht, trägt
 * {@link Protokolliert} mit der passenden Protokoll-Aktion ({@link #erwartetesProtokoll}); ausgenommen sind Listen und
 * Suchen ohne {@code {id}} (A2–A4; bei A6 und A8 nur lesend per {@code GET}, z. B. die Partnerauswahl
 * {@code GET /kontakte}), der Eingangskorb (A5), A11–A14 (dort protokolliert nur {@code VERWEIGERT}) und
 * {@code POST /trennblaetter} (A1 ohne Dokument, Spec 09, Req 6.1).
 * <p>
 * Req 2.5: {@link ErlaubtScope} steht nur an {@code POST /dokumente} ({@code import}) und
 * {@code POST /dokumente/generator} ({@code generator}), beide mit A1; {@code @RolesAllowed} nennt dort zusätzlich die
 * Scope-Rolle, sonst nirgends.
 * <p>
 * Spec 06, Req 4.8: Die Verwaltung deklariert keine direkten Request-Parameter {@code mandant} oder {@code X-Mandant}
 * und kein {@code {mandant}} im Pfad; der Mandant kommt aus der Anmeldung.
 * <p>
 * Geprüft werden alle konkreten Klassen (main und test), in deren Hierarchie (Oberklassen, Interfaces) ein
 * {@code @Path} steht. Das HTTP-Verb und {@code @Path} der Methode dürfen aus Interface oder Oberklasse stammen, die
 * Sicherheitsannotationen werden an der Implementierung gelesen (dort liest sie der Filter). Eine Methode mit
 * {@code @Path}, aber ohne HTTP-Verb (Sub-Resource-Locator), ist ein Verstoss.
 */
class EndpunktWaechterTest {

    private static final List<Class<? extends Annotation>> HTTP =
            List.of(GET.class, POST.class, PUT.class, PATCH.class, DELETE.class, HEAD.class, OPTIONS.class);
    private static final Set<String> ALLE = Arrays.stream(Rolle.values()).map(Rolle::code).collect(Collectors.toSet());
    // Attrappen mit @Path in der Hierarchie registrierte Quarkus sonst als echte Ressourcen; die Eigenschaft ist nie gesetzt
    static final String ATTRAPPE = "archiv.test.waechter-attrappe";
    private static final Pattern PARAMETER = Pattern.compile("\\{");
    private static final Pattern MANDANT_IM_PFAD =
            Pattern.compile("\\{\\s*mandant\\s*(?::[^}]*)?}", Pattern.CASE_INSENSITIVE);
    private static final List<String> VERWALTUNG = List.of("/rollen", "/benutzer", "/mandant/einstellungen",
            "/wissensbasis/einstellungen", "/konfiguration/versionen");
    // {id} mit optionalem Regex, der selbst Klammern enthalten darf, z. B. {id:[0-9a-f-]{36}}
    private static final String ID = "\\{\\s*id\\s*(:([^{}]|\\{[^{}]*})*)?}";
    private static final Pattern DOKUMENT_ID = Pattern.compile(ID);
    private static final Pattern HERUNTERLADEN = Pattern.compile(".*" + ID + "/datei/?$");
    private static final java.nio.file.Path OPENAPI = java.nio.file.Path.of("../frontend/openapi/openapi.json");
    private static final java.nio.file.Path MATRIX = java.nio.file.Path.of("src/test/resources/rechtematrix.json");
    private static final List<String> HTTP_NAMEN = List.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final String TRENNBLAETTER = "/trennblaetter";
    private static final Map<ApiTokenScope, String> SCOPE_PFAD =
            Map.of(ApiTokenScope.IMPORT, "/dokumente", ApiTokenScope.GENERATOR, "/dokumente/generator");
    /**
     * Ausdrücklich gelistete Ausnahmen für den Scope {@code import} (Spec 09, 10.9, Aufgabe 27.6): Prüfsummen-Abfrage
     * mit A1 sowie Start und Abschluss von Quellläufen mit A28, je nur {@code POST}.
     */
    static final Map<String, Aktion> IMPORT_AUSNAHMEN = Map.of(
            "/dokumente/pruefsummen", Aktion.A1,
            "/quellen/{code}/laeufe", Aktion.A28,
            "/quellen/laeufe/{laufId}/abschliessen", Aktion.A28);

    @Test
    void jederEndpunktFragtDieRegelAb() {
        List<Class<?>> ressourcen = new ClassFileImporter().importPackages("ch.urinet.archiv").stream()
                .filter(k -> !k.getName().contains("EndpunktWaechterTest") && !k.isInterface()
                        && !k.getModifiers().contains(JavaModifier.ABSTRACT) && hatPfad(k))
                .map(JavaClass::reflect).toList();

        assertThat(ressourcen).as("mindestens die Probe-Ressource").isNotEmpty();
        assertThat(ressourcen.stream().flatMap(k -> verstoesse(k).stream())).isEmpty();
    }

    @Test
    void openApiOperationenSindDurchDieRechtematrixAbgedeckt() throws IOException {
        ObjectMapper json = new ObjectMapper();
        JsonNode openApi = json.readTree(OPENAPI.toFile());
        JsonNode matrix = json.readTree(MATRIX.toFile());
        Map<String, JsonNode> zeilen = new LinkedHashMap<>();
        matrix.path("aktionen").forEach(zeile -> zeilen.put(zeile.path("id").asText(), zeile));

        Map<String, Method> codeOperationen = new LinkedHashMap<>();
        new ClassFileImporter().importPackages("ch.urinet.archiv").stream()
                .filter(k -> !k.getName().contains("EndpunktWaechterTest"))
                .filter(k -> k.getName().startsWith("ch.urinet.archiv.") && !k.isInterface()
                        && !k.getModifiers().contains(JavaModifier.ABSTRACT) && hatPfad(k))
                .map(JavaClass::reflect)
                .forEach(ressource -> {
                    String klassenPfad = hierarchie(ressource).stream().map(k -> k.getAnnotation(Path.class))
                            .filter(Objects::nonNull).map(Path::value).findFirst().orElse("");
                    endpunktMethoden(ressource).values().forEach(deklarationen -> {
                        Optional<Method> methode = umsetzung(ressource, deklarationen.getFirst());
                        Optional<Method> verb = deklarationen.stream()
                                .filter(m -> HTTP.stream().anyMatch(m::isAnnotationPresent)).findFirst();
                        if (methode.isEmpty() || verb.isEmpty()
                                || methode.get().isAnnotationPresent(WebseitenZugang.class)) {
                            return;
                        }
                        String methodenPfad = deklarationen.stream().map(m -> m.getAnnotation(Path.class))
                                .filter(Objects::nonNull).map(Path::value).findFirst().orElse("");
                        String pfad = normalisiert("/api/v1" + klassenPfad + "/" + methodenPfad);
                        if (!pfad.startsWith("/api/v1/")) {
                            return;
                        }
                        String httpMethode = HTTP.stream().filter(verb.get()::isAnnotationPresent)
                                .findFirst().orElseThrow().getSimpleName().toLowerCase();
                        if (!openApi.path("paths").path(pfad).has(httpMethode)) {
                            return;
                        }
                        String schluessel = httpMethode + " " + pfad;
                        assertThat(codeOperationen.put(schluessel, methode.get()))
                                .as("doppelte REST-Operation " + schluessel).isNull();
                    });
                });

        Map<String, JsonNode> openApiOperationen = new LinkedHashMap<>();
        openApi.path("paths").fields().forEachRemaining(pfad -> pfad.getValue().fields().forEachRemaining(operation -> {
            if (HTTP_NAMEN.contains(operation.getKey())) {
                openApiOperationen.put(operation.getKey() + " " + pfad.getKey(), operation.getValue());
            }
        }));
        assertThat(openApiOperationen).as("alle Operationen im OpenAPI-Vertrag müssen aus REST-Ressourcen stammen")
                .containsOnlyKeys(codeOperationen.keySet().toArray(String[]::new));
        assertThat(codeOperationen.keySet()).as("jede REST-Ressource muss im OpenAPI-Vertrag stehen")
                .containsExactlyInAnyOrderElementsOf(openApiOperationen.keySet());

        List<String> abweichungen = new ArrayList<>();
        openApiOperationen.forEach((schluessel, operation) -> {
            Method methode = codeOperationen.get(schluessel);
            Set<String> rollenImCode = methode.isAnnotationPresent(RolesAllowed.class)
                    ? Set.of(methode.getAnnotation(RolesAllowed.class).value()) : Set.of();
            Set<String> rollenImVertrag = rollenImVertrag(operation.path("security"), schluessel);
            if (!rollenImVertrag.equals(rollenImCode)) {
                abweichungen.add(schluessel + ": OpenAPI-Rollen " + rollenImVertrag + " statt Code-Rollen "
                        + rollenImCode);
            }
            Set<String> erwarteteRollen = new LinkedHashSet<>();
            ErfordertAktion erklaerung = methode.getAnnotation(ErfordertAktion.class);
            if (erklaerung != null) {
                JsonNode zeile = zeilen.get(erklaerung.value().name());
                if (zeile == null) {
                    abweichungen.add(schluessel + ": Matrixzeile fehlt für " + erklaerung.value());
                    return;
                }
                boolean dokumentbezogen = erklaerung.value().dokumentbezogen()
                        && !methode.isAnnotationPresent(NichtDokumentbezogen.class)
                        && DOKUMENT_ID.matcher(schluessel).find();
                for (Rolle rolle : Rolle.values()) {
                    JsonNode zelle = zeile.path("rollen").path(rolle.code());
                    Ergebnis ergebnis = RechtematrixZelle.ergebnis(zelle.path("ergebnis").asText());
                    List<String> erwarteteStatus = new ArrayList<>();
                    zelle.path("http").forEach(status -> erwarteteStatus.add(status.asText()));
                    assertThat(erwarteteStatus).as(schluessel + " " + rolle.code() + " HTTP")
                            .isEqualTo(RechtematrixSpecTest.httpErgebnisse(ergebnis));
                    if (ergebnis.zugaenglich() || dokumentbezogen) {
                        erwarteteRollen.add(rolle.code());
                    }
                    if (ergebnis == Ergebnis.VERBOTEN) {
                        if (!operation.path("responses").has("403")) {
                            abweichungen.add(schluessel + " " + rolle.code() + ": 403-Antwort fehlt");
                        }
                    }
                    if ((ergebnis == Ergebnis.NICHT_SICHTBAR || ergebnis == Ergebnis.NUR_EIGENE)
                            && DOKUMENT_ID.matcher(schluessel).find()) {
                        if (!operation.path("responses").has("404")) {
                            abweichungen.add(schluessel + " " + rolle.code() + ": 404-Antwort fehlt");
                        }
                    }
                }
            } else if (methode.isAnnotationPresent(AlleArchivRollen.class)) {
                erwarteteRollen.addAll(ALLE);
            }
            ErlaubtScope scope = methode.getAnnotation(ErlaubtScope.class);
            if (scope != null) {
                erwarteteRollen.add(scope.value().rolle());
            }
            if (!rollenImVertrag.equals(erwarteteRollen)) {
                abweichungen.add(schluessel + ": Rollen aus Matrix/Scope " + erwarteteRollen
                        + " statt OpenAPI-Rollen " + rollenImVertrag);
            }
            if (!rollenImCode.isEmpty()) {
                if (!operation.path("responses").has("401")) {
                    abweichungen.add(schluessel + ": 401-Antwort fehlt");
                }
                if (!operation.path("responses").has("403")) {
                    abweichungen.add(schluessel + ": 403-Antwort fehlt");
                }
            }
        });
        assertThat(abweichungen).as("Abweichungen zwischen Rechtematrix, REST-Code und OpenAPI").isEmpty();
    }

    private static Set<String> rollenImVertrag(JsonNode security, String operation) {
        if (!security.isArray() || security.isEmpty()) {
            return Set.of();
        }
        assertThat(security).as(operation + " Sicherheitsalternativen").hasSize(1);
        JsonNode anforderung = security.get(0);
        assertThat(anforderung.size()).as(operation + " Sicherheitsschema").isEqualTo(1);
        JsonNode scopes = anforderung.elements().next();
        Set<String> rollen = new LinkedHashSet<>();
        scopes.forEach(scope -> rollen.add(scope.asText()));
        return rollen;
    }

    @Test
    void gegenprobeErkenntVerstoesse() {
        assertThat(verstoesse(OhneErklaerung.class)).singleElement().asString().contains("keine Erklärung");
        assertThat(verstoesse(FalscheRollen.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(ZweiErklaerungen.class)).singleElement().asString().contains("genau eine");
        assertThat(verstoesse(OeffentlichMitRollen.class)).singleElement().asString().contains("@PermitAll");
        assertThat(verstoesse(AlleOhneTreuhaender.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(DokumentMitEngenRollen.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(DokumentOhneId.class)).singleElement().asString().contains("{id}");
        assertThat(verstoesse(DokumentIdNurAnKlasse.class)).singleElement().asString().contains("{id}");
        assertThat(verstoesse(ImplementierungOhneErklaerung.class)).singleElement().asString()
                .contains("keine Erklärung");
        assertThat(verstoesse(UeberschriebenOhneErklaerung.class)).singleElement().asString()
                .contains("keine Erklärung");
        assertThat(verstoesse(GeerbtVonOberklasse.class)).singleElement().asString().contains("keine Erklärung");
        assertThat(verstoesse(MitSubRessource.class)).singleElement().asString().contains("Sub-Resource-Locator");
        assertThat(verstoesse(OhneProtokoll.class)).singleElement().asString()
                .contains("A7 verlangt @Protokolliert(BESTAETIGEN) statt keiner");
        assertThat(verstoesse(FalschesProtokoll.class)).singleElement().asString()
                .contains("A2 verlangt @Protokolliert(HERUNTERLADEN) statt ANZEIGEN");
        assertThat(verstoesse(ScopeFalscherPfad.class)).singleElement().asString()
                .contains("@ErlaubtScope(IMPORT) nur an POST /dokumente");
        assertThat(verstoesse(ScopeFalscheAktion.class)).singleElement().asString()
                .contains("@ErlaubtScope(GENERATOR) nur an POST /dokumente/generator");
        // Spec 09, 10.9: die gelisteten Ausnahmen gelten nur mit ihrer Aktion
        assertThat(verstoesse(ScopeAusnahmeFalscheAktion.class)).singleElement().asString()
                .contains("@ErlaubtScope(IMPORT) nur an POST /dokumente");
        assertThat(verstoesse(KorrekteScopeAusnahme.class)).isEmpty();
        assertThat(verstoesse(ScopeOhneRolle.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(ScopeRolleOhneErklaerung.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(IdMitRegex.class)).singleElement().asString()
                .contains("A2 verlangt @Protokolliert(HERUNTERLADEN) statt ANZEIGEN");
        assertThat(verstoesse(AendernOhneIdOhneProtokoll.class)).singleElement().asString()
                .contains("A6 verlangt @Protokolliert(AENDERN) statt keiner");
        assertThat(verstoesse(Korrekt.class)).isEmpty();
        assertThat(verstoesse(KorrektNichtDokumentbezogen.class)).isEmpty();
        assertThat(verstoesse(KorrektMitScope.class)).isEmpty();
        assertThat(verstoesse(KorrektUeberInterface.class)).isEmpty();
        assertThat(verstoesse(VerwaltungMitMandant.class)).singleElement().asString()
                .contains("kein Mandant aus Parametern");
        assertThat(verstoesse(VerwaltungMitMandantenHeader.class)).singleElement().asString()
                .contains("kein Mandant aus Parametern");
        assertThat(verstoesse(VerwaltungMitMandantImPfad.class)).singleElement().asString()
                .contains("kein Mandant aus Parametern");
        assertThat(verstoesse(EinstellungenMitMandant.class)).singleElement().asString()
                .contains("kein Mandant aus Parametern");
        assertThat(verstoesse(WebseiteOhneWebseitenZugang.class)).singleElement().asString()
                .contains("jede Methode unter /public/ verlangt @WebseitenZugang");
        assertThat(verstoesse(WebseitenZugangAusserhalbPublic.class)).singleElement().asString()
                .contains("@WebseitenZugang nur unter /public/");
        assertThat(verstoesse(WebseitenErfassungOhneAktion.class)).singleElement().asString()
                .contains("@WebseitenZugang(ERFASSUNG) verlangt @WebseitenErfassungAktion");
        assertThat(verstoesse(WebseitenErfassungMitVerbotenerAktion.class)).singleElement().asString()
                .contains("Webseiten-Erfassungsaktion A9 ist nicht freigegeben");
    }

    @Test
    void gegenprobeFindetRessourcenUeberDieHierarchie() {
        assertThat(hatPfad(new ClassFileImporter().importClass(ImplementierungOhneErklaerung.class))).isTrue();
        assertThat(hatPfad(new ClassFileImporter().importClass(UeberschriebenOhneErklaerung.class))).isTrue();
        assertThat(hatPfad(new ClassFileImporter().importClass(OhneErklaerung.class))).isFalse();
    }

    private static boolean hatPfad(JavaClass klasse) {
        return Stream.of(Stream.of(klasse), klasse.getAllRawSuperclasses().stream(),
                        klasse.getAllRawInterfaces().stream()).flatMap(s -> s)
                .anyMatch(k -> k.isAnnotatedWith(Path.class));
    }

    static List<String> verstoesse(Class<?> ressource) {
        List<String> verstoesse = new ArrayList<>();
        String klassenPfad = hierarchie(ressource).stream().map(k -> k.getAnnotation(Path.class))
                .filter(Objects::nonNull).map(Path::value).findFirst().orElse("");
        for (Map.Entry<String, List<Method>> eintrag : endpunktMethoden(ressource).entrySet()) {
            List<Method> deklarationen = eintrag.getValue();
            Optional<Method> umsetzung = umsetzung(ressource, deklarationen.getFirst());
            if (umsetzung.isEmpty()) {
                continue;
            }
            Method methode = umsetzung.get();
            String name = ressource.getSimpleName() + "." + methode.getName();
            if (deklarationen.stream().noneMatch(m -> HTTP.stream().anyMatch(m::isAnnotationPresent))) {
                verstoesse.add(name + ": @Path ohne HTTP-Verb (Sub-Resource-Locator) ist nicht erlaubt");
                continue;
            }
            String pfad = klassenPfad + "/" + deklarationen.stream().map(m -> m.getAnnotation(Path.class))
                    .filter(Objects::nonNull).map(Path::value).findFirst().orElse("");
            String normalerPfad = normalisiert(pfad);
            if (VERWALTUNG.stream().anyMatch(p -> normalerPfad.equals(p) || normalerPfad.startsWith(p + "/"))
                    && (MANDANT_IM_PFAD.matcher(pfad).find()
                    || deklarationen.stream().flatMap(m -> Arrays.stream(m.getParameterAnnotations()))
                            .flatMap(Arrays::stream).anyMatch(EndpunktWaechterTest::mandantParameter))) {
                verstoesse.add(name + ": kein Mandant aus Parametern der Verwaltung");
            }
            boolean unterPublic = normalisiert(klassenPfad).equals("/public")
                    || normalisiert(klassenPfad).startsWith("/public/");
            WebseitenZugang webseitenZugang = methode.getAnnotation(WebseitenZugang.class);
            if (webseitenZugang != null) {
                if (!unterPublic) {
                    verstoesse.add(name + ": @WebseitenZugang nur unter /public/");
                }

                WebseitenErfassungAktion webseitenAktion = methode.getAnnotation(WebseitenErfassungAktion.class);
                if (webseitenZugang.value() == WebseitenZugang.Art.ERFASSUNG) {
                    if (webseitenAktion == null) {
                        verstoesse.add(name + ": @WebseitenZugang(ERFASSUNG) verlangt @WebseitenErfassungAktion");
                    } else if (!Berechtigung.webseitenErfassungErlaubt(webseitenAktion.value())) {
                        verstoesse.add(name + ": Webseiten-Erfassungsaktion " + webseitenAktion.value()
                                + " ist nicht freigegeben");
                    }
                } else if (webseitenAktion != null) {
                    verstoesse.add(name + ": @WebseitenErfassungAktion nur mit @WebseitenZugang(ERFASSUNG)");
                }
                if (methode.isAnnotationPresent(ErfordertAktion.class)
                        || methode.isAnnotationPresent(AlleArchivRollen.class)
                        || methode.isAnnotationPresent(Oeffentlich.class)
                        || methode.isAnnotationPresent(ErlaubtScope.class)
                        || methode.isAnnotationPresent(RolesAllowed.class)
                        || methode.isAnnotationPresent(PermitAll.class)) {
                    verstoesse.add(name + ": @WebseitenZugang darf nicht mit anderen Zugangserklärungen kombiniert werden");
                }
                if (!methode.isAnnotationPresent(Authenticated.class)) {
                    verstoesse.add(name + ": @WebseitenZugang verlangt @Authenticated");
                }
                continue;
            }
            if (unterPublic) {
                verstoesse.add(name + ": jede Methode unter /public/ verlangt @WebseitenZugang");
                continue;
            }
            ErfordertAktion aktion = methode.getAnnotation(ErfordertAktion.class);
            boolean alle = methode.isAnnotationPresent(AlleArchivRollen.class);
            boolean oeffentlich = methode.isAnnotationPresent(Oeffentlich.class);
            long erklaerungen = Stream.of(aktion != null, alle, oeffentlich).filter(b -> b).count();
            if (erklaerungen == 0) {
                verstoesse.add(name + ": keine Erklärung (@ErfordertAktion, @AlleArchivRollen oder @Oeffentlich)");
                continue;
            }
            if (erklaerungen > 1) {
                verstoesse.add(name + ": genau eine Erklärung erlaubt");
                continue;
            }
            RolesAllowed rollen = methode.getAnnotation(RolesAllowed.class);
            if (oeffentlich) {
                if (rollen != null || !methode.isAnnotationPresent(PermitAll.class)) {
                    verstoesse.add(name + ": @Oeffentlich verlangt @PermitAll ohne @RolesAllowed");
                }
                continue;
            }
            boolean dokumentbezogen = aktion != null && aktion.value().dokumentbezogen()
                    && !methode.isAnnotationPresent(NichtDokumentbezogen.class);
            if (dokumentbezogen && PARAMETER.matcher(pfad).find() && !DOKUMENT_ID.matcher(pfad).find()) {
                verstoesse.add(name + ": dokumentbezogene Aktion " + aktion.value() + " führt das Dokument nicht als {id} ("
                        + pfad + ")");
                continue;
            }
            Optional<ProtokollAktion> protokoll = aktion == null ? Optional.empty()
                    : erwartetesProtokoll(aktion.value(), pfad,
                    deklarationen.stream().anyMatch(m -> m.isAnnotationPresent(GET.class)));
            Protokolliert erklaert = Optional.ofNullable(methode.getAnnotation(Protokolliert.class))
                    .orElse(ressource.getAnnotation(Protokolliert.class));
            if (protokoll.isPresent() && (erklaert == null || erklaert.aktion() != protokoll.get())) {
                verstoesse.add(name + ": " + aktion.value() + " verlangt @Protokolliert(" + protokoll.get() + ") statt "
                        + (erklaert == null ? "keiner" : erklaert.aktion()));
            }
            Set<String> erwartet = new LinkedHashSet<>(alle || dokumentbezogen ? ALLE
                    : Rechtematrix.zugelassen(aktion.value()).stream().map(Rolle::code).collect(Collectors.toSet()));
            ErlaubtScope scope = methode.getAnnotation(ErlaubtScope.class);
            if (scope != null) {
                boolean post = deklarationen.stream().anyMatch(m -> m.isAnnotationPresent(POST.class));
                boolean ausnahme = scope.value() == ApiTokenScope.IMPORT && post && aktion != null
                        && IMPORT_AUSNAHMEN.get(normalisiert(pfad)) == aktion.value();
                if (!ausnahme && (aktion == null || aktion.value() != Aktion.A1 || !post
                        || !normalisiert(pfad).equals(SCOPE_PFAD.get(scope.value())))) {
                    verstoesse.add(name + ": @ErlaubtScope(" + scope.value() + ") nur an POST "
                            + SCOPE_PFAD.get(scope.value()) + " mit A1 (" + pfad + ")");
                    continue;
                }
                erwartet.add(scope.value().rolle());
            }
            Set<String> ist = rollen == null ? Set.of() : Set.of(rollen.value());
            if (!ist.equals(erwartet)) {
                verstoesse.add(name + ": @RolesAllowed " + ist + " statt " + erwartet);
            }
        }
        return verstoesse;
    }

    /** Pfad ohne doppelte und abschliessende Schrägstriche. */
    private static boolean mandantParameter(Annotation annotation) {
        String parameter = switch (annotation) {
            case QueryParam p -> p.value();
            case PathParam p -> p.value();
            case HeaderParam p -> p.value();
            case CookieParam p -> p.value();
            case FormParam p -> p.value();
            case MatrixParam p -> p.value();
            default -> "";
        };
        return parameter.equalsIgnoreCase("mandant") || parameter.equalsIgnoreCase("x-mandant");
    }

    private static String normalisiert(String pfad) {
        String ohneDoppelte = pfad.replaceAll("/{2,}", "/");
        return ohneDoppelte.length() > 1 && ohneDoppelte.endsWith("/")
                ? ohneDoppelte.substring(0, ohneDoppelte.length() - 1) : ohneDoppelte;
    }

    /**
     * Protokoll-Aktion nach der Tabelle in design.md «Protokoll und Manifest»; leer, wo nicht einzeln protokolliert
     * (Listen und Suchen ohne Dokumentbezug, auch {@code GET} ohne {@code {id}} bei A6/A8).
     */
    static Optional<ProtokollAktion> erwartetesProtokoll(Aktion aktion, String pfad, boolean lesend) {
        boolean mitId = DOKUMENT_ID.matcher(pfad).find();
        return Optional.ofNullable(switch (aktion) {
            // Trennblätter erzeugen und die Prüfsummen-Abfrage gehören zu A1 (Spec 09, 15.1 und 10.6), legen aber
            // kein Dokument an und nennen keines
            case A1 -> normalisiert(pfad).equals(TRENNBLAETTER) || normalisiert(pfad).equals("/dokumente/pruefsummen")
                    ? null : ProtokollAktion.ERFASSEN;
            case A2, A3, A4 -> !mitId ? null
                    : HERUNTERLADEN.matcher(pfad).matches() ? ProtokollAktion.HERUNTERLADEN
                    : ProtokollAktion.ANZEIGEN;
            case A6, A8 -> !mitId && lesend ? null : ProtokollAktion.AENDERN;
            case A7 -> ProtokollAktion.BESTAETIGEN;
            case A9 -> ProtokollAktion.VERWERFEN;
            case A10 -> ProtokollAktion.WIEDERHOLEN;
            case A5, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23, A24, A25, A26, A27, A28, A29 ->
                    null;
        });
    }

    /** Klasse, Oberklassen und alle Interfaces, die nächstliegenden zuerst. */
    private static List<Class<?>> hierarchie(Class<?> klasse) {
        Set<Class<?>> alle = new LinkedHashSet<>();
        for (Class<?> k = klasse; k != null && k != Object.class; k = k.getSuperclass()) {
            alle.add(k);
        }
        List<Class<?>> offen = new ArrayList<>(alle);
        for (int i = 0; i < offen.size(); i++) {
            for (Class<?> schnittstelle : offen.get(i).getInterfaces()) {
                if (alle.add(schnittstelle)) {
                    offen.add(schnittstelle);
                }
            }
        }
        return List.copyOf(alle);
    }

    /** Signatur → alle Deklarationen mit HTTP-Verb oder {@code @Path} in der Hierarchie. */
    private static Map<String, List<Method>> endpunktMethoden(Class<?> ressource) {
        Map<String, List<Method>> methoden = new LinkedHashMap<>();
        for (Class<?> k : hierarchie(ressource)) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge() || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                methoden.computeIfAbsent(signatur(m), s -> new ArrayList<>()).add(m);
            }
        }
        methoden.values().removeIf(liste -> liste.stream().noneMatch(m -> m.isAnnotationPresent(Path.class)
                || HTTP.stream().anyMatch(m::isAnnotationPresent)));
        return methoden;
    }

    private static Optional<Method> umsetzung(Class<?> ressource, Method deklaration) {
        for (Class<?> k = ressource; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(deklaration.getName(), deklaration.getParameterTypes());
                if (!Modifier.isAbstract(m.getModifiers())) {
                    return Optional.of(m);
                }
            } catch (NoSuchMethodException e) {
                // weiter in der Oberklasse
            }
        }
        return Arrays.stream(ressource.getMethods()).filter(m -> m.isDefault() && signatur(m).equals(signatur(deklaration)))
                .findFirst();
    }

    private static String signatur(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes());
    }

    static class OhneErklaerung {
        @GET
        @RolesAllowed("inhaber")
        public void lesen() {
        }
    }

    static class FalscheRollen {
        @POST
        @ErfordertAktion(Aktion.A1)
        @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void erfassen() {
        }
    }

    static class ZweiErklaerungen {
        @GET
        @AlleArchivRollen
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void lesen() {
        }
    }

    static class OeffentlichMitRollen {
        @GET
        @Oeffentlich
        @RolesAllowed("inhaber")
        public void lesen() {
        }
    }

    static class AlleOhneTreuhaender {
        @GET
        @AlleArchivRollen
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
        public void lesen() {
        }
    }

    /** Enge Rollen bei A7: Quarkus antwortete 403 vor der Sichtbarkeit und verriete so das Dokument. */
    static class DokumentMitEngenRollen {
        @POST
        @Path("/dokumente/{id}/bestaetigen")
        @ErfordertAktion(Aktion.A7)
        @RolesAllowed("inhaber")
        @Protokolliert(aktion = ProtokollAktion.BESTAETIGEN)
        public void bestaetigen() {
        }
    }

    static class DokumentOhneId {
        @POST
        @Path("/dokumente/{dokumentId}/bestaetigen")
        @ErfordertAktion(Aktion.A7)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void bestaetigen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/dokumente/{nr}")
    static class DokumentIdNurAnKlasse {
        @GET
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void lesen() {
        }
    }

    @Path("/schnittstelle")
    interface Schnittstelle {
        @GET
        @Path("/{id}")
        void lesen(String id);
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    static class ImplementierungOhneErklaerung implements Schnittstelle {
        @Override
        public void lesen(String id) {
        }
    }

    @Path("/oberklasse")
    abstract static class Oberklasse {
        @POST
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    static class UeberschriebenOhneErklaerung extends Oberklasse {
        @Override
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    static class GeerbtVonOberklasse extends Oberklasse {
    }

    static class MitSubRessource {
        @Path("/unter")
        public Object unterRessource() {
            return null;
        }
    }

    static class OhneProtokoll {
        @POST
        @Path("/dokumente/{id}/bestaetigen")
        @ErfordertAktion(Aktion.A7)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void bestaetigen() {
        }
    }

    static class FalschesProtokoll {
        @GET
        @Path("/dokumente/{id}/datei")
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
        public void herunterladen() {
        }
    }

    static class AendernOhneIdOhneProtokoll {
        @PATCH
        @Path("/kontakte")
        @ErfordertAktion(Aktion.A6)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void aendern() {
        }
    }

    static class Korrekt {
        @PUT
        @ErfordertAktion(Aktion.A11)
        @RolesAllowed("inhaber")
        public void dokumentartAendern() {
        }

        @POST
        @Path("/dokumente/{id}/bestaetigen")
        @ErfordertAktion(Aktion.A7)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        @Protokolliert(aktion = ProtokollAktion.BESTAETIGEN)
        public void bestaetigen() {
        }

        @GET
        @Path("/dokumente")
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void liste() {
        }

        @GET
        @Path("/dokumente/{id}/datei")
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        @Protokolliert(aktion = ProtokollAktion.HERUNTERLADEN)
        public void herunterladen() {
        }

        @GET
        @Path("/kontakte")
        @ErfordertAktion(Aktion.A6)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void partnerSuchen() {
        }

        @GET
        @Oeffentlich
        @PermitAll
        public void oeffentlich() {
        }

        public void keinEndpunkt() {
        }
    }

    static class VerwaltungMitMandant {
        @GET
        @Path("/rollen")
        @ErfordertAktion(Aktion.A21)
        @RolesAllowed("inhaber")
        public void lesen(@QueryParam("mandant") String mandant) {
        }
    }

    static class VerwaltungMitMandantImPfad {
        @GET
        @Path("/benutzer/{mandant}")
        @ErfordertAktion(Aktion.A21)
        @RolesAllowed("inhaber")
        public void lesen() {
        }
    }

    static class VerwaltungMitMandantenHeader {
        @GET
        @Path("/benutzer")
        @ErfordertAktion(Aktion.A21)
        @RolesAllowed("inhaber")
        public void lesen(@HeaderParam("X-Mandant") String mandant) {
        }
    }

    static class EinstellungenMitMandant {
        @GET
        @Path("/mandant/einstellungen")
        @ErfordertAktion(Aktion.A20)
        @RolesAllowed("inhaber")
        public void lesen(@QueryParam("mandant") String mandant) {
        }
    }

    static class KorrektNichtDokumentbezogen {
        @GET
        @Path("/kontakte/{kontaktId}")
        @ErfordertAktion(Aktion.A6)
        @NichtDokumentbezogen
        @RolesAllowed({"inhaber", "geschaeftsstelle"})
        public void kontaktDetail() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    static class KorrektUeberInterface implements Schnittstelle {
        @Override
        @ErfordertAktion(Aktion.A2)
        @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void lesen(String id) {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/quellen")
    static class ScopeAusnahmeFalscheAktion {
        @POST
        @Path("/{code}/laeufe")
        @ErfordertAktion(Aktion.A27)
        @ErlaubtScope(ApiTokenScope.IMPORT)
        @RolesAllowed({"inhaber", "treuhaender", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
        public void starten() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/quellen")
    static class KorrekteScopeAusnahme {
        @POST
        @Path("/laeufe/{laufId}/abschliessen")
        @ErfordertAktion(Aktion.A28)
        @ErlaubtScope(ApiTokenScope.IMPORT)
        @RolesAllowed({"inhaber", ApiTokenScope.ROLLE_IMPORT})
        public void abschliessen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/dokumente")
    static class ScopeFalscherPfad {
        @POST
        @Path("/hochladen")
        @ErfordertAktion(Aktion.A1)
        @ErlaubtScope(ApiTokenScope.IMPORT)
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
        @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/dokumente")
    static class ScopeFalscheAktion {
        @POST
        @Path("/generator")
        @ErfordertAktion(Aktion.A11)
        @ErlaubtScope(ApiTokenScope.GENERATOR)
        @RolesAllowed({"inhaber", ApiTokenScope.ROLLE_GENERATOR})
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/dokumente")
    static class ScopeOhneRolle {
        @POST
        @ErfordertAktion(Aktion.A1)
        @ErlaubtScope(ApiTokenScope.IMPORT)
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
        @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/dokumente")
    static class ScopeRolleOhneErklaerung {
        @POST
        @ErfordertAktion(Aktion.A1)
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
        @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/public/v1/probe")
    static class WebseiteOhneWebseitenZugang {
        @GET
        @AlleArchivRollen
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void lesen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/api/v1/probe")
    static class WebseitenZugangAusserhalbPublic {
        @GET
        @Authenticated
        @WebseitenZugang(WebseitenZugang.Art.LESEN)
        public void lesen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/public/v1/probe")
    static class WebseitenErfassungOhneAktion {
        @POST
        @Authenticated
        @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
        public void erfassen() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/public/v1/probe")
    static class WebseitenErfassungMitVerbotenerAktion {
        @POST
        @Authenticated
        @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
        @WebseitenErfassungAktion(Aktion.A9)
        public void erfassen() {
        }
    }

    static class IdMitRegex {
        @GET
        @Path("/dokumente/{id:[0-9a-f-]{36}}/datei")
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
        public void datei() {
        }
    }

    @IfBuildProperty(name = ATTRAPPE, stringValue = "an")
    @Path("/dokumente/")
    static class KorrektMitScope {
        @POST
        @ErfordertAktion(Aktion.A1)
        @ErlaubtScope(ApiTokenScope.IMPORT)
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
        @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
        public void erfassen() {
        }

        @POST
        @Path("/generator/")
        @ErfordertAktion(Aktion.A1)
        @ErlaubtScope(ApiTokenScope.GENERATOR)
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_GENERATOR})
        @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
        public void generator() {
        }
    }
}
