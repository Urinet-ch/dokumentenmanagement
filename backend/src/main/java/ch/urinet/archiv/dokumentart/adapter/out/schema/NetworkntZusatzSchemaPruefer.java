package ch.urinet.archiv.dokumentart.adapter.out.schema;

import ch.urinet.archiv.dokumentart.adapter.StrengesJson;
import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressions;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link ZusatzSchemaPruefer} mit networknt json-schema-validator (Draft 2020-12, {@code format} als Prüfung, Req 9.5).
 * <p>
 * Schutz gegen SSRF und übergrosse Eingaben: Schemas haben höchstens {@value #MAX_SCHEMA_BYTES} Byte und
 * {@value #MAX_TIEFE} Ebenen; {@code $id}, {@code $vocabulary} und Verweise, die nicht mit {@code #} beginnen, sind
 * ungültig; {@code $schema} ist nur der Dialekt 2020-12. Die Bibliothek lädt zudem keine entfernten Ressourcen; das
 * Meta-Schema kommt aus dem JAR. Fehlertexte der Bibliothek gehen nicht nach aussen (Req 16.7).
 * <p>
 * Schutz gegen Rechenzeit (Review PR #40): Reguläre Ausdrücke ({@code pattern}, {@code patternProperties},
 * {@code pattern} unter {@code propertyNames}) laufen mit der JDK-Syntax gegen eine Frist; ein Aufruf von
 * {@link #schemaPruefen} oder {@link #wertePruefen} hat dafür zusammen höchstens
 * {@code archiv.dokumentart.muster-zeitgrenze}, danach ist das Ergebnis {@code ZUSATZ_UNGUELTIG}. Verweise, die ohne
 * Fortschritt in den Daten auf sich selbst zurückführen ({@code {"$ref":"#"}} an der Wurzel), sind ungültig.
 */
@Startup
@ApplicationScoped
public class NetworkntZusatzSchemaPruefer implements ZusatzSchemaPruefer {

    static final int MAX_SCHEMA_BYTES = 16 * 1024;
    static final int MAX_WERTE_BYTES = 64 * 1024;
    static final int MAX_TIEFE = 10;
    private static final int CACHE_GROESSE = 32;
    private static final String DIALEKT = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> VERWEISE = Set.of("$ref", "$dynamicRef", "$recursiveRef");
    private static final Set<String> VERBOTEN = Set.of("$id", "$vocabulary", "$anchor", "$dynamicAnchor",
            "$recursiveAnchor");
    /** Schlüssel, deren Teilschema auf dieselbe Stelle der Daten wirkt (ohne Fortschritt in den Daten). */
    private static final Set<String> AM_ORT_EINZELN = Set.of("not", "if", "then", "else");
    private static final Set<String> AM_ORT_LISTE = Set.of("allOf", "anyOf", "oneOf");

    private final long zeitgrenzeNanos;
    private final ThreadLocal<Long> frist = new ThreadLocal<>();
    private final SchemaRegistry registry;
    private final Schema metaSchema;
    private final Map<String, Geprueft> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_GROESSE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Geprueft> eldest) {
                    return size() > CACHE_GROESSE;
                }
            });

    @Inject
    public NetworkntZusatzSchemaPruefer(
            @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration zeitgrenze) {
        if (zeitgrenze == null || zeitgrenze.isNegative() || zeitgrenze.isZero()) {
            throw new IllegalArgumentException("archiv.dokumentart.muster-zeitgrenze muss grösser als 0 sein");
        }
        this.zeitgrenzeNanos = zeitgrenze.toNanos();
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                b -> b.schemaLoader(l -> l.fetchRemoteResources(false))
                        .schemaRegistryConfig(SchemaRegistryConfig.builder()
                                .formatAssertionsEnabled(true)
                                .regularExpressionFactory(this::muster)
                                .build()));
        this.metaSchema = registry.getSchema(SchemaLocation.of(DIALEKT));
    }

    private record Geprueft(Schema kompiliert, Set<String> felder) {
    }

    @Override
    public Set<String> schemaPruefen(String schemaJson) {
        return mitFrist(() -> geprueft(schemaJson).felder());
    }

    @Override
    public void wertePruefen(String schemaJson, String werteJson) {
        mitFrist(() -> {
            JsonNode werte = lesen(werteJson, MAX_WERTE_BYTES);
            if (!werte.isObject() || tiefe(werte) > MAX_TIEFE) {
                throw ungueltig();
            }
            Schema schema = geprueft(schemaJson).kompiliert();
            boolean gueltig;
            try {
                gueltig = schema.validate(werte).isEmpty();
            } catch (RuntimeException | StackOverflowError e) {
                throw ungueltig();
            }
            if (!gueltig) {
                throw ungueltig();
            }
            return null;
        });
    }

    /** Prüft und kompiliert ein Schema einmal; danach kommt es aus dem Cache. */
    private Geprueft geprueft(String schemaJson) {
        if (schemaJson != null) {
            Geprueft bekannt = cache.get(schemaJson);
            if (bekannt != null) {
                return bekannt;
            }
        }
        JsonNode schema = lesen(schemaJson, MAX_SCHEMA_BYTES);
        if (!schema.isObject() || !"object".equals(schema.path("type").asText(null)) || tiefe(schema) > MAX_TIEFE) {
            throw ungueltig();
        }
        schluesselPruefen(schema);
        zyklenPruefen(schema);
        boolean metaGueltig;
        try {
            metaGueltig = metaSchema.validate(schema).isEmpty();
        } catch (RuntimeException e) {
            throw ungueltig();
        }
        if (!metaGueltig) {
            throw ungueltig();
        }
        Set<String> felder = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(felder::add);
        Geprueft ergebnis = new Geprueft(kompilieren(schema), Set.copyOf(felder));
        cache.put(schemaJson, ergebnis);
        return ergebnis;
    }

    private Schema kompilieren(JsonNode schema) {
        try {
            Schema kompiliert = registry.getSchema(schema);
            kompiliert.initializeValidators();
            return kompiliert;
        } catch (SchemaException | IllegalArgumentException | IllegalStateException | StackOverflowError e) {
            throw ungueltig();
        }
    }

    /** Setzt die Frist für den ganzen Aufruf, auch wenn er mehrere Muster prüft. */
    private <T> T mitFrist(Supplier<T> aufruf) {
        if (frist.get() != null) {
            return aufruf.get();
        }
        frist.set(System.nanoTime() + zeitgrenzeNanos);
        try {
            return aufruf.get();
        } finally {
            frist.remove();
        }
    }

    /** Wie die JDK-Fabrik der Bibliothek, aber mit Frist beim Suchen. */
    private RegularExpression muster(String regex) {
        Pattern pattern = Pattern.compile(
                RegularExpressions.replaceLongformCharacterProperties(RegularExpressions.replaceDollarAnchors(regex)));
        return text -> {
            Long gesetzt = frist.get();
            long bis = gesetzt != null ? gesetzt : System.nanoTime() + zeitgrenzeNanos;
            return pattern.matcher(new MitFrist(text, bis)).find();
        };
    }

    private JsonNode lesen(String text, int maxBytes) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw ungueltig();
        }
        try {
            JsonNode knoten = StrengesJson.lesen(text);
            if (knoten == null) {
                throw ungueltig();
            }
            return knoten;
        } catch (JsonProcessingException e) {
            throw ungueltig();
        }
    }

    /** Lehnt Schlüssel ab, die eine fremde Ressource benennen oder laden könnten. */
    private static void schluesselPruefen(JsonNode knoten) {
        if (knoten.isArray()) {
            knoten.forEach(NetworkntZusatzSchemaPruefer::schluesselPruefen);
            return;
        }
        if (!knoten.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = knoten.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> eintrag = it.next();
            String name = eintrag.getKey();
            JsonNode wert = eintrag.getValue();
            if (VERBOTEN.contains(name)
                    || VERWEISE.contains(name) && !(wert.isTextual() && wert.asText().startsWith("#"))
                    || "$schema".equals(name) && !(wert.isTextual() && DIALEKT.equals(wert.asText()))) {
                throw ungueltig();
            }
            schluesselPruefen(wert);
        }
    }

    /**
     * Lehnt Verweise ab, die an derselben Stelle der Daten im Kreis laufen (Review PR #40): Kanten sind Verweise und
     * Teilschemas, die auf dieselbe Stelle wirken ({@code allOf}, {@code anyOf}, {@code oneOf}, {@code not},
     * {@code if}, {@code then}, {@code else}, {@code dependentSchemas}). Rekursion über {@code properties} oder
     * {@code items} bleibt erlaubt, weil sie mit den Daten endet. Ein Verweis, der kein JSON Pointer in dieses Schema
     * ist ({@code #} oder {@code #/…}), ist ungültig.
     */
    private static void zyklenPruefen(JsonNode wurzel) {
        Map<JsonNode, Boolean> fertig = new IdentityHashMap<>();
        List<JsonNode> alle = new ArrayList<>();
        sammeln(wurzel, alle);
        for (JsonNode knoten : alle) {
            besuchen(knoten, wurzel, fertig);
        }
    }

    private static void sammeln(JsonNode knoten, List<JsonNode> alle) {
        if (knoten.isObject()) {
            alle.add(knoten);
        }
        knoten.forEach(kind -> sammeln(kind, alle));
    }

    private static void besuchen(JsonNode knoten, JsonNode wurzel, Map<JsonNode, Boolean> fertig) {
        if (!knoten.isObject()) {
            return;
        }
        Boolean zustand = fertig.get(knoten);
        if (Boolean.TRUE.equals(zustand)) {
            return;
        }
        if (Boolean.FALSE.equals(zustand)) {
            throw ungueltig();
        }
        fertig.put(knoten, Boolean.FALSE);
        for (JsonNode ziel : amOrt(knoten, wurzel)) {
            besuchen(ziel, wurzel, fertig);
        }
        fertig.put(knoten, Boolean.TRUE);
    }

    private static List<JsonNode> amOrt(JsonNode knoten, JsonNode wurzel) {
        List<JsonNode> ziele = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = knoten.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> eintrag = it.next();
            String name = eintrag.getKey();
            JsonNode wert = eintrag.getValue();
            if (VERWEISE.contains(name)) {
                ziele.add(verweisZiel(wert.asText(), wurzel));
            } else if (AM_ORT_EINZELN.contains(name)) {
                ziele.add(wert);
            } else if (AM_ORT_LISTE.contains(name) || "dependentSchemas".equals(name)) {
                wert.forEach(ziele::add);
            }
        }
        return ziele;
    }

    private static JsonNode verweisZiel(String verweis, JsonNode wurzel) {
        if ("#".equals(verweis)) {
            return wurzel;
        }
        if (!verweis.startsWith("#/")) {
            throw ungueltig();
        }
        JsonNode ziel;
        try {
            ziel = wurzel.at(JsonPointer.compile(verweis.substring(1)));
        } catch (IllegalArgumentException e) {
            throw ungueltig();
        }
        if (ziel.isMissingNode()) {
            throw ungueltig();
        }
        return ziel;
    }

    private static int tiefe(JsonNode knoten) {
        int tiefste = 0;
        for (JsonNode kind : knoten) {
            tiefste = Math.max(tiefste, tiefe(kind));
        }
        return knoten.isContainerNode() ? tiefste + 1 : 0;
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
    }

    /** Text für den Matcher, der nach Ablauf der Frist abbricht; geprüft wird jeden 1024. Zugriff. */
    private static final class MitFrist implements CharSequence {
        private final CharSequence text;
        private final long bis;
        private int zugriffe;

        MitFrist(CharSequence text, long bis) {
            this.text = text;
            this.bis = bis;
        }

        @Override
        public char charAt(int index) {
            if ((++zugriffe & 1023) == 0 && System.nanoTime() - bis > 0) {
                throw new MusterAbgebrochen();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new MitFrist(text.subSequence(start, end), bis);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /**
     * Abbruch nach der Frist. Als {@link SchemaException}, weil die Bibliothek diese ohne Log weiterreicht; andere
     * Ausnahmen loggt sie mit Muster und Ort.
     */
    static final class MusterAbgebrochen extends SchemaException {
        private static final long serialVersionUID = 1L;

        MusterAbgebrochen() {
            super("Musterprüfung nach der Zeitgrenze abgebrochen");
        }
    }
}
