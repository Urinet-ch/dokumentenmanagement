package ch.urinet.archiv.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.openapi.api.SmallRyeOpenAPI;
import org.eclipse.microprofile.config.Config;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Beschreibung der Webseiten-Schnittstelle für {@code PublicVertragTest} (Spec 03, Req 11.2): aus dem Code mit dem
 * Scanner von smallrye-open-api direkt auf einem Jandex-Index, ohne Quarkus-Start und ohne den Ausschluss der
 * App-OpenAPI, und aus der Datei {@code public-v<n>.yaml} mit demselben Parser. Verglichen wird eine normalisierte Form
 * der Pfade unter {@code /public/}: Parameter ({@code in}, Name, Pflicht, Schema), Anfrage-Inhalt, Statuscodes und
 * Schemas je Medientyp, mit aufgelösten Schema-Referenzen. Beschreibungen, Beispiele, Erweiterungen {@code x-…} und
 * Grenzen wie {@code pattern} zählen nicht; Antworten, die auf {@code #/components/responses/…} verweisen, vergleicht
 * der Test über den Namen der Referenz.
 */
final class PublicVertrag {

    static final String PRAEFIX = "/public/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final List<String> VERBEN = List.of("get", "put", "post", "delete", "options", "head", "patch",
            "trace");
    private static final Set<String> SCHEMA_SCHLUESSEL = Set.of("type", "format", "enum", "const", "items",
            "properties", "required", "additionalProperties", "allOf", "anyOf", "oneOf");
    private static final Set<String> OBERSTE_EBENE = Set.of("openapi", "info", "jsonSchemaDialect", "servers", "paths",
            "webhooks", "components", "security", "tags", "externalDocs");
    private static final Pattern STATUS = Pattern.compile("^([1-5][0-9]{2}|[1-5]XX|default)$");
    private static final Pattern KOMPONENTE = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private PublicVertrag() {
    }

    /** Index der Klassen unter den Verzeichnissen (z. B. {@code target/classes}), ohne weitere Filter. */
    static Index index(Path... verzeichnisse) {
        Indexer indexer = new Indexer();
        for (Path verzeichnis : verzeichnisse) {
            try (Stream<Path> dateien = Files.walk(verzeichnis)) {
                for (Path datei : dateien.filter(p -> p.toString().endsWith(".class")).toList()) {
                    try (InputStream ein = Files.newInputStream(datei)) {
                        indexer.index(ein);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return indexer.complete();
    }

    /** Index mit zusätzlichen Klassen (Gegenproben). */
    static IndexView mit(IndexView basis, Class<?>... klassen) throws IOException {
        return CompositeIndex.create(basis, Index.of(klassen));
    }

    /**
     * Beschreibung aus dem Code: Einstellungen {@code mp.openapi.*} der App ohne {@code mp.openapi.scan.*} (also ohne
     * Ausschluss), damit Schemas wie in der App entstehen.
     */
    static JsonNode ausCode(IndexView index, Map<String, String> einstellungen) {
        Config konfiguration = new SmallRyeConfigBuilder().withDefaultValues(einstellungen).build();
        String json = SmallRyeOpenAPI.builder().withConfig(konfiguration).withIndex(index)
                .enableModelReader(false).enableStandardStaticFiles(false).enableStandardFilter(false)
                .build().toJSON();
        return lesen(json);
    }

    /** Beschreibung aus der Datei, gelesen mit dem Parser von smallrye-open-api. */
    static JsonNode ausDatei(String yaml) {
        String json = SmallRyeOpenAPI.builder().withConfig(new SmallRyeConfigBuilder().build())
                .withCustomStaticFile(() -> new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .enableAnnotationScan(false).enableModelReader(false).enableStandardStaticFiles(false)
                .enableStandardFilter(false).build().toJSON();
        return lesen(json);
    }

    static JsonNode roh(String yaml) {
        try {
            return YAML.readTree(yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code mp.openapi.*} aus {@code application.properties} ohne Profile und ohne {@code mp.openapi.scan.*}. */
    static Map<String, String> appEinstellungen(Path properties, boolean mitScan) throws IOException {
        Properties p = new Properties();
        try (InputStream ein = Files.newInputStream(properties)) {
            p.load(ein);
        }
        Map<String, String> ergebnis = new TreeMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("mp.openapi.") && (mitScan || !name.startsWith("mp.openapi.scan."))) {
                ergebnis.put(name, p.getProperty(name));
            }
        }
        return ergebnis;
    }

    /** Normalisierte Pfade unter {@code /public/}: Pfad → Verb → Parameter, Anfrage, Antworten. */
    static Map<String, Object> pfade(JsonNode dokument) {
        Map<String, Object> pfade = new TreeMap<>();
        JsonNode alle = dokument.path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = alle.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> pfad = it.next();
            if (!pfad.getKey().startsWith(PRAEFIX)) {
                continue;
            }
            Map<String, Object> verben = new TreeMap<>();
            for (String verb : VERBEN) {
                JsonNode operation = pfad.getValue().get(verb);
                if (operation != null) {
                    verben.put(verb, operation(dokument, pfad.getValue().path("parameters"), operation));
                }
            }
            pfade.put(ohneSchlussStrich(pfad.getKey()), verben);
        }
        return pfade;
    }

    private static Map<String, Object> operation(JsonNode dokument, JsonNode pfadParameter, JsonNode operation) {
        Map<String, Object> ergebnis = new TreeMap<>();
        Map<String, Object> parameter = new TreeMap<>();
        for (JsonNode quelle : List.of(pfadParameter, operation.path("parameters"))) {
            for (JsonNode p : quelle) {
                JsonNode aufgeloest = aufloesen(dokument, p);
                Map<String, Object> eintrag = new TreeMap<>();
                eintrag.put("pflicht", aufgeloest.path("required").asBoolean(false)
                        || "path".equals(aufgeloest.path("in").asText()));
                eintrag.put("schema", schema(dokument, aufgeloest.path("schema"), new HashSet<>()));
                parameter.put(aufgeloest.path("in").asText() + ":" + aufgeloest.path("name").asText(), eintrag);
            }
        }
        ergebnis.put("parameter", parameter);
        if (operation.has("requestBody")) {
            JsonNode inhalt = aufloesen(dokument, operation.get("requestBody"));
            Map<String, Object> anfrage = new TreeMap<>();
            anfrage.put("pflicht", inhalt.path("required").asBoolean(false));
            anfrage.put("inhalt", inhalt(dokument, inhalt.path("content")));
            ergebnis.put("anfrage", anfrage);
        }
        Map<String, Object> antworten = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = operation.path("responses").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> antwort = it.next();
            JsonNode wert = antwort.getValue();
            antworten.put(antwort.getKey(), wert.has("$ref") ? Map.of("$ref", wert.get("$ref").asText())
                    : inhalt(dokument, wert.path("content")));
        }
        ergebnis.put("antworten", antworten);
        return ergebnis;
    }

    private static Map<String, Object> inhalt(JsonNode dokument, JsonNode inhalt) {
        Map<String, Object> ergebnis = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = inhalt.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> typ = it.next();
            ergebnis.put(typ.getKey(), schema(dokument, typ.getValue().path("schema"), new HashSet<>()));
        }
        return ergebnis;
    }

    /** Schema mit aufgelösten Referenzen, nur die Schlüssel der Struktur; ein Zyklus bleibt als Referenz stehen. */
    static Object schema(JsonNode dokument, JsonNode schema, Set<String> unterwegs) {
        if (schema.isMissingNode() || schema.isNull()) {
            return Map.of();
        }
        if (schema.isBoolean()) {
            return schema.asBoolean();
        }
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            if (!unterwegs.add(ref)) {
                return Map.of("$ref", ref);
            }
            Object aufgeloest = schema(dokument, zeiger(dokument, ref), unterwegs);
            unterwegs.remove(ref);
            return aufgeloest;
        }
        Map<String, Object> ergebnis = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> feld = it.next();
            String name = feld.getKey();
            JsonNode wert = feld.getValue();
            if (!SCHEMA_SCHLUESSEL.contains(name)) {
                continue;
            }
            switch (name) {
                case "type", "enum", "required" -> ergebnis.put(name, sortiert(wert));
                case "items", "additionalProperties" -> ergebnis.put(name, schema(dokument, wert, unterwegs));
                case "properties" -> {
                    Map<String, Object> eigenschaften = new TreeMap<>();
                    wert.fields().forEachRemaining(e -> eigenschaften.put(e.getKey(),
                            schema(dokument, e.getValue(), unterwegs)));
                    ergebnis.put(name, eigenschaften);
                }
                case "allOf", "anyOf", "oneOf" -> {
                    List<Object> teile = new ArrayList<>();
                    wert.forEach(t -> teile.add(schema(dokument, t, unterwegs)));
                    ergebnis.put(name, teile);
                }
                default -> ergebnis.put(name, wert.asText());
            }
        }
        return ergebnis;
    }

    private static List<String> sortiert(JsonNode wert) {
        List<String> liste = new ArrayList<>();
        if (wert.isArray()) {
            wert.forEach(w -> liste.add(w.asText()));
        } else {
            liste.add(wert.asText());
        }
        liste.sort(null);
        return liste;
    }

    private static JsonNode aufloesen(JsonNode dokument, JsonNode knoten) {
        JsonNode aktuell = knoten;
        for (int i = 0; i < 10 && aktuell.has("$ref"); i++) {
            aktuell = zeiger(dokument, aktuell.get("$ref").asText());
        }
        return aktuell;
    }

    /** Löst eine lokale Referenz {@code #/…} auf; sonst oder bei fehlendem Ziel ein fehlender Knoten. */
    static JsonNode zeiger(JsonNode dokument, String ref) {
        if (!ref.startsWith("#/")) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        return dokument.at(ref.substring(1));
    }

    /**
     * Mindestprüfung als OpenAPI 3.1 (der Parser von smallrye-open-api ist nachsichtig): Version {@code 3.1.x},
     * {@code info.title} und {@code info.version}, nur bekannte Schlüssel auf oberster Ebene, Pfade unter
     * {@code /public/v<n>/}, jede Operation mit Antworten und gültigen Statuscodes, gültige Namen der Komponenten und
     * jede Referenz {@code $ref} lokal und auflösbar.
     */
    static List<String> befunde(JsonNode roh, int hauptversion) {
        List<String> befunde = new ArrayList<>();
        if (!roh.path("openapi").asText().matches("^3\\.1\\.\\d+$")) {
            befunde.add("openapi ist nicht 3.1.x");
        }
        if (!roh.path("info").path("title").isTextual() || !roh.path("info").path("version").isTextual()) {
            befunde.add("info.title oder info.version fehlt");
        }
        roh.fieldNames().forEachRemaining(n -> {
            if (!OBERSTE_EBENE.contains(n) && !n.startsWith("x-")) {
                befunde.add("unbekannter Schlüssel " + n);
            }
        });
        if (!roh.path("paths").isObject()) {
            befunde.add("paths fehlt");
        }
        String praefix = PRAEFIX + "v" + hauptversion + "/";
        roh.path("paths").fields().forEachRemaining(pfad -> {
            if (!pfad.getKey().startsWith(praefix)) {
                befunde.add("Pfad ausserhalb " + praefix + ": " + pfad.getKey());
            }
            for (String verb : VERBEN) {
                JsonNode operation = pfad.getValue().get(verb);
                if (operation == null) {
                    continue;
                }
                if (!operation.path("responses").isObject() || operation.path("responses").isEmpty()) {
                    befunde.add(pfad.getKey() + " " + verb + ": keine Antworten");
                }
                operation.path("responses").fieldNames().forEachRemaining(code -> {
                    if (!STATUS.matcher(code).matches()) {
                        befunde.add(pfad.getKey() + " " + verb + ": Statuscode " + code);
                    }
                });
            }
        });
        roh.path("components").fields().forEachRemaining(art -> art.getValue().fieldNames().forEachRemaining(n -> {
            if (!KOMPONENTE.matcher(n).matches()) {
                befunde.add("Name der Komponente " + art.getKey() + "/" + n);
            }
        }));
        referenzen(roh, roh, "", befunde);
        return befunde;
    }

    private static void referenzen(JsonNode dokument, JsonNode knoten, String ort, List<String> befunde) {
        if (knoten.isObject()) {
            JsonNode ref = knoten.get("$ref");
            if (ref != null && (!ref.isTextual() || zeiger(dokument, ref.asText()).isMissingNode())) {
                befunde.add("Referenz löst nicht auf: " + ort + "/$ref = " + ref.asText());
            }
            knoten.fields().forEachRemaining(e -> referenzen(dokument, e.getValue(), ort + "/" + e.getKey(), befunde));
        } else if (knoten.isArray()) {
            for (int i = 0; i < knoten.size(); i++) {
                referenzen(dokument, knoten.get(i), ort + "/" + i, befunde);
            }
        }
    }

    /** Stellen, an denen sich zwei normalisierte Beschreibungen unterscheiden (leer = gleich). */
    static List<String> unterschiede(Object code, Object datei) {
        List<String> ergebnis = new ArrayList<>();
        vergleichen("", code, datei, ergebnis);
        return ergebnis;
    }

    @SuppressWarnings("unchecked")
    private static void vergleichen(String ort, Object code, Object datei, List<String> ergebnis) {
        if (code instanceof Map<?, ?> a && datei instanceof Map<?, ?> b) {
            Map<String, Object> links = new LinkedHashMap<>((Map<String, Object>) a);
            Map<String, Object> rechts = (Map<String, Object>) b;
            for (Map.Entry<String, Object> e : links.entrySet()) {
                if (!rechts.containsKey(e.getKey())) {
                    ergebnis.add(unter(ort, e.getKey()) + ": nur im Code");
                } else {
                    vergleichen(unter(ort, e.getKey()), e.getValue(), rechts.get(e.getKey()), ergebnis);
                }
            }
            for (String schluessel : rechts.keySet()) {
                if (!links.containsKey(schluessel)) {
                    ergebnis.add(unter(ort, schluessel) + ": nur in der Datei");
                }
            }
        } else if (!java.util.Objects.equals(code, datei)) {
            ergebnis.add(ort + ": Code " + code + ", Datei " + datei);
        }
    }

    /** Ort im Vergleich; Pfade beginnen schon mit «/». */
    private static String unter(String ort, String schluessel) {
        return ort.isEmpty() ? schluessel : ort + "/" + schluessel;
    }

    private static String ohneSchlussStrich(String pfad) {
        return pfad.length() > 1 && pfad.endsWith("/") ? pfad.substring(0, pfad.length() - 1) : pfad;
    }

    private static JsonNode lesen(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
