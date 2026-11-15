package ch.urinet.archiv.abnahme;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Chat-Prüfliste (Spec 02, Req 13.1): liest {@code chat-pruefliste.yaml} und prüft sie gegen das Schema
 * {@code abnahme/chat-pruefliste.schema.json}. Die Datei mit den echten Fragen liegt ausserhalb des Repositorys unter
 * {@code ARCHIV_GOLDEN_DIR}; Fehlermeldungen nennen darum nur Position und Regel, nie den Wert.
 */
public final class Pruefliste {

    /** Mindestzahl der Fragen (7 Beispielfragen und mindestens 13 weitere, Req 13.1). */
    public static final int MIN_FRAGEN = 20;
    /** Anzahl der Beispielfragen aus {@code product.md}; sie tragen die Nummern 1 bis 7. */
    public static final int BEISPIELFRAGEN = 7;
    static final String SCHEMA = "/abnahme/chat-pruefliste.schema.json";

    /** Erwartete Quelle: genau eines von Dokument-ID und Name des Zahlenwerkzeugs (Datenstand). */
    public record ErwarteteQuelle(UUID dokument, String datenstand) {
    }

    public record Frage(int nr, String frage, boolean beispielfrage, List<String> kernwerte,
                        List<ErwarteteQuelle> quellen, String hinweis) {
    }

    /** Die Datei verletzt das Schema oder die Regeln der Prüfliste; die Meldung enthält keine Werte. */
    public static final class UngueltigePruefliste extends RuntimeException {
        UngueltigePruefliste(String meldung) {
            super(meldung);
        }
    }

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Schema PRUEFSCHEMA = schema();

    private Pruefliste() {
    }

    public static List<Frage> lesen(Path yaml) throws IOException {
        JsonNode baum;
        try {
            baum = YAML.readTree(Files.readString(yaml));
        } catch (RuntimeException e) {
            throw new UngueltigePruefliste("Die Prüfliste ist kein lesbares YAML");
        }
        return aus(baum);
    }

    public static List<Frage> aus(JsonNode baum) {
        List<Error> fehler = PRUEFSCHEMA.validate(baum);
        if (!fehler.isEmpty()) {
            List<String> stellen = fehler.stream()
                    .map(f -> f.getInstanceLocation() + " (" + f.getKeyword() + ")").sorted().distinct().toList();
            throw new UngueltigePruefliste("Schema verletzt an: " + String.join("; ", stellen));
        }
        List<Frage> fragen = new ArrayList<>();
        for (JsonNode f : baum) {
            List<String> kernwerte = new ArrayList<>();
            f.path("erwartet").path("kernwerte").forEach(k -> kernwerte.add(k.asString()));
            List<ErwarteteQuelle> quellen = new ArrayList<>();
            f.path("erwartet").path("quellen").forEach(q -> quellen.add(q.has("dokument")
                    ? new ErwarteteQuelle(UUID.fromString(q.path("dokument").asString()), null)
                    : new ErwarteteQuelle(null, q.path("datenstand").asString())));
            fragen.add(new Frage(f.path("nr").asInt(), f.path("frage").asString(), f.path("beispielfrage").asBoolean(),
                    List.copyOf(kernwerte), List.copyOf(quellen), f.path("hinweis").asString("")));
        }
        regeln(fragen);
        return List.copyOf(fragen);
    }

    /** Regeln, die das Schema nicht ausdrückt: Anzahl, eindeutige Nummern, Beispielfragen genau 1 bis 7. */
    private static void regeln(List<Frage> fragen) {
        if (fragen.size() < MIN_FRAGEN) {
            throw new UngueltigePruefliste("Die Prüfliste hat " + fragen.size() + " Fragen, verlangt sind mindestens "
                    + MIN_FRAGEN);
        }
        Set<Integer> nummern = new HashSet<>();
        for (Frage f : fragen) {
            if (!nummern.add(f.nr())) {
                throw new UngueltigePruefliste("Nummer " + f.nr() + " kommt mehrfach vor");
            }
            if (f.beispielfrage() != (f.nr() <= BEISPIELFRAGEN)) {
                throw new UngueltigePruefliste("Frage " + f.nr() + ": beispielfrage muss genau für die Nummern 1 bis "
                        + BEISPIELFRAGEN + " gelten");
            }
        }
        for (int nr = 1; nr <= BEISPIELFRAGEN; nr++) {
            if (!nummern.contains(nr)) {
                throw new UngueltigePruefliste("Beispielfrage " + nr + " fehlt");
            }
        }
    }

    private static Schema schema() {
        try (InputStream in = Pruefliste.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Schema fehlt: " + SCHEMA);
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                            b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder()
                                    .formatAssertionsEnabled(true).build()))
                    .getSchema(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                            com.networknt.schema.InputFormat.JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
