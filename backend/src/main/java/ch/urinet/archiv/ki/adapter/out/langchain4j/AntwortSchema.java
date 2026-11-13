package ch.urinet.archiv.ki.adapter.out.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.service.output.JsonSchemas;

/**
 * Prüft die Rohantwort des Modells gegen das strikte Antwortschema, wie es im {@code response_format} hinausgeht (Req
 * 7.3): alle Felder {@code required}, {@code additionalProperties: false}, Typen und Enums. LangChain4j selbst füllt
 * fehlende Felder mit {@code null}; eine solche Antwort ist hier ein Schemafehler und löst die eine Nachfrage aus.
 */
public final class AntwortSchema {

    private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(json(), InputFormat.JSON);

    private AntwortSchema() {
    }

    /** Strikte Fassung des Antwortschemas als JSON (gleich dem {@code json_schema.schema} der Anfrage). */
    public static String json() {
        try {
            return new ObjectMapper().writeValueAsString(JsonSchemaElementUtils.toMap(
                    JsonSchemas.jsonSchemaFrom(MetadatenVorschlag.class).orElseThrow().rootElement(), true));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Antwortschema nicht serialisierbar", e);
        }
    }

    /** {@code true}, wenn der Text gültiges JSON nach dem strikten Schema ist; leerer Text oder {@code null} nie. */
    static boolean gueltig(String roh) {
        if (roh == null || roh.isBlank()) {
            return false;
        }
        try {
            return SCHEMA.validate(roh, InputFormat.JSON).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
