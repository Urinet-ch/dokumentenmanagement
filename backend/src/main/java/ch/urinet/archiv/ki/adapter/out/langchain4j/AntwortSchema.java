package ch.urinet.archiv.ki.adapter.out.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.KiKlassifikation;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.service.output.JsonSchemas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prüft die Rohantwort des Modells gegen das strikte Antwortschema, wie es im {@code response_format} hinausgeht (Req
 * 7.3): alle Felder {@code required}, {@code additionalProperties: false}, Typen und Enums. LangChain4j selbst füllt
 * fehlende Felder mit {@code null}; eine solche Antwort ist hier ein Schemafehler und löst die eine Nachfrage aus.
 */
public final class AntwortSchema {

    /** Begrenzt (zuletzt benutzte zuerst verdrängt): jede Konfigurationsänderung ergibt ein neues Schema. */
    private static final int MAX_SCHEMAS = 64;
    private static final Map<String, Schema> SCHEMAS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Schema> aeltester) {
                    return size() > MAX_SCHEMAS;
                }
            });

    private AntwortSchema() {
    }

    /** Strikte Fassung des Antwortschemas als JSON (gleich dem {@code json_schema.schema} der Anfrage). */
    public static String json() {
        return json(MetadatenVorschlag.class);
    }

    public static String json(List<ArtEintrag> arten, List<KiKlassifikation> klassifikationen) {
        return ErfassungAntwortSchema.json(arten, klassifikationen);
    }

    static String json(Class<?> antworttyp) {
        try {
            return new ObjectMapper().writeValueAsString(JsonSchemaElementUtils.toMap(
                    JsonSchemas.jsonSchemaFrom(antworttyp).orElseThrow().rootElement(), true));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Antwortschema nicht serialisierbar", e);
        }
    }

    /** {@code true}, wenn der Text gültiges JSON nach dem strikten Schema ist; leerer Text oder {@code null} nie. */
    static boolean gueltig(String roh) {
        return gueltig(roh, json());
    }

    /** Prüft die Rohantwort gegen das Antwortschema des jeweiligen KI-Aufrufs. */
    static boolean gueltig(String roh, Class<?> antworttyp) {
        return gueltig(roh, json(antworttyp));
    }

    /** Prüft die Rohantwort gegen das mandantenspezifische Antwortschema des KI-Aufrufs. */
    static boolean gueltig(String roh, String jsonSchema) {
        if (roh == null || roh.isBlank()) {
            return false;
        }
        try {
            return schemaFuer(jsonSchema).validate(roh, InputFormat.JSON).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Schema schemaFuer(String jsonSchema) {
        synchronized (SCHEMAS) {
            return SCHEMAS.computeIfAbsent(jsonSchema, AntwortSchema::schema);
        }
    }

    private static Schema schema(String jsonSchema) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(jsonSchema, InputFormat.JSON);
    }
}
