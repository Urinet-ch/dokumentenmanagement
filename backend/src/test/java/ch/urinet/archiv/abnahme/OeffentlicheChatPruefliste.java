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

/** Prüfliste für den öffentlichen Chatbot (Spec 03, Aufgabe 11.3); echte Fragen liegen ausserhalb des Repositorys. */
public final class OeffentlicheChatPruefliste {

    public static final int MIN_FRAGEN = 40;
    public static final int MAX_FRAGEN = 60;
    static final String SCHEMA = "/abnahme/oeffentlicher-chat-pruefliste.schema.json";

    public record ErwarteteQuelle(UUID dokument, String titel, String reihe, String ausgabe, int jahr, String seite) {
    }

    public record Frage(int nr, String frage, List<String> kernwerte, List<ErwarteteQuelle> quellen) {
        public Frage {
            kernwerte = List.copyOf(kernwerte);
            quellen = List.copyOf(quellen);
        }
    }

    public static final class UngueltigePruefliste extends RuntimeException {
        UngueltigePruefliste(String meldung) {
            super(meldung);
        }
    }

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Schema PRUEFSCHEMA = schema();

    private OeffentlicheChatPruefliste() {
    }

    public static List<Frage> lesen(Path yaml) throws IOException {
        JsonNode baum;
        try {
            baum = YAML.readTree(Files.readString(yaml));
        } catch (RuntimeException e) {
            throw new UngueltigePruefliste("Die öffentliche Prüfliste ist kein lesbares YAML");
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
            f.path("erwartet").path("quellen").forEach(q -> quellen.add(new ErwarteteQuelle(
                    UUID.fromString(q.path("dokument").asString()), q.path("titel").asString(),
                    q.path("reihe").asString(), q.path("ausgabe").asString(), q.path("jahr").asInt(),
                    q.path("seite").asString())));
            fragen.add(new Frage(f.path("nr").asInt(), f.path("frage").asString(), kernwerte, quellen));
        }
        regeln(fragen);
        return List.copyOf(fragen);
    }

    private static void regeln(List<Frage> fragen) {
        if (fragen.size() < MIN_FRAGEN || fragen.size() > MAX_FRAGEN) {
            throw new UngueltigePruefliste("Die Prüfliste muss 40 bis 60 Fragen enthalten");
        }
        Set<Integer> nummern = new HashSet<>();
        for (Frage frage : fragen) {
            if (!nummern.add(frage.nr())) {
                throw new UngueltigePruefliste("Eine Fragennummer kommt mehrfach vor");
            }
        }
        for (int nr = 1; nr <= fragen.size(); nr++) {
            if (!nummern.contains(nr)) {
                throw new UngueltigePruefliste("Die Fragennummern müssen lückenlos bei 1 beginnen");
            }
        }
    }

    private static Schema schema() {
        try (InputStream in = OeffentlicheChatPruefliste.class.getResourceAsStream(SCHEMA)) {
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
