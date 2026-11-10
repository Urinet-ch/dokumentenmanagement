package ch.urinet.archiv.dokumentart.adapter.out.schema;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link ZusatzSchemaPruefer} mit networknt json-schema-validator (Draft 2020-12, {@code format} als Prüfung, Req 9.5).
 * <p>
 * Schutz gegen SSRF und übergrosse Eingaben: Schemas haben höchstens {@value #MAX_SCHEMA_BYTES} Byte und
 * {@value #MAX_TIEFE} Ebenen; {@code $id}, {@code $vocabulary} und Verweise, die nicht mit {@code #} beginnen, sind
 * ungültig; {@code $schema} ist nur der Dialekt 2020-12. Die Bibliothek lädt zudem keine entfernten Ressourcen; das
 * Meta-Schema kommt aus dem JAR. Fehlertexte der Bibliothek gehen nicht nach aussen (Req 16.7).
 */
@ApplicationScoped
public class NetworkntZusatzSchemaPruefer implements ZusatzSchemaPruefer {

    static final int MAX_SCHEMA_BYTES = 16 * 1024;
    static final int MAX_WERTE_BYTES = 64 * 1024;
    static final int MAX_TIEFE = 10;
    private static final String DIALEKT = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> VERWEISE = Set.of("$ref", "$dynamicRef", "$recursiveRef");
    private static final Set<String> VERBOTEN = Set.of("$id", "$vocabulary", "$anchor", "$dynamicAnchor",
            "$recursiveAnchor");

    private final ObjectMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemaLoader(l -> l.fetchRemoteResources(false))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    private final Schema metaSchema = registry.getSchema(SchemaLocation.of(DIALEKT));

    @Override
    public Set<String> schemaPruefen(String schemaJson) {
        JsonNode schema = lesen(schemaJson, MAX_SCHEMA_BYTES);
        if (!schema.isObject() || !"object".equals(schema.path("type").asText(null)) || tiefe(schema) > MAX_TIEFE) {
            throw ungueltig();
        }
        schluesselPruefen(schema);
        if (!metaSchema.validate(schema).isEmpty()) {
            throw ungueltig();
        }
        kompilieren(schema);
        Set<String> felder = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(felder::add);
        return Set.copyOf(felder);
    }

    @Override
    public void wertePruefen(String schemaJson, String werteJson) {
        JsonNode werte = lesen(werteJson, MAX_WERTE_BYTES);
        if (!werte.isObject() || tiefe(werte) > MAX_TIEFE) {
            throw ungueltig();
        }
        schemaPruefen(schemaJson);
        boolean gueltig;
        try {
            gueltig = kompilieren(lesen(schemaJson, MAX_SCHEMA_BYTES)).validate(werte).isEmpty();
        } catch (RuntimeException e) {
            throw ungueltig();
        }
        if (!gueltig) {
            throw ungueltig();
        }
    }

    private Schema kompilieren(JsonNode schema) {
        try {
            Schema kompiliert = registry.getSchema(schema);
            kompiliert.initializeValidators();
            return kompiliert;
        } catch (SchemaException | IllegalArgumentException | IllegalStateException e) {
            throw ungueltig();
        }
    }

    private JsonNode lesen(String text, int maxBytes) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw ungueltig();
        }
        try {
            JsonNode knoten = json.readTree(text);
            if (knoten == null || knoten.isMissingNode()) {
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
}
