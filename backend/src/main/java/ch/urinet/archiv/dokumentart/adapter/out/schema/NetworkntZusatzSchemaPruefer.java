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
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.Keyword;
import com.networknt.schema.keyword.KeywordValidator;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressions;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
import java.util.function.LongSupplier;
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
 * Schutz gegen Rechenzeit (Review PR #40): Ein Aufruf von {@link #schemaPruefen} oder {@link #wertePruefen} hat
 * zusammen höchstens {@code archiv.dokumentart.muster-zeitgrenze}, ein Aufruf von {@link #seedSchemaPruefen} (Seed
 * eines Mandanten, Admin-Kommando) höchstens {@code archiv.dokumentart.seed-zeitgrenze}; danach ist das Ergebnis
 * {@code ZUSATZ_UNGUELTIG}. Gemessen wird die CPU-Zeit des prüfenden Threads, nicht die Wanduhr: Unter gedrosselter CPU
 * ({@code cpus} in Compose, CFS-Quote) wartet der Thread oft länger als die Frist, ohne zu rechnen, und die Wanduhr
 * lehnte dann gültige Schemas ab (Stufe A, 12.11.2026). Kann die JVM die CPU-Zeit des Threads nicht messen (virtueller
 * Thread, abgeschaltet), gilt für diesen Aufruf die Wanduhr. Weil das Ablesen der CPU-Zeit unter Linux ein
 * Systemaufruf ist und selbst gegen die Frist zählt, liest die Frist zuerst die Wanduhr und die CPU-Zeit erst, wenn die
 * Wanduhr den Rest der Frist überschritten hat (CPU-Zeit ≤ Wanduhr, Review PR #81). Die Wanduhr eines Aufrufs ist
 * bewusst nicht begrenzt: Die Prüfung blockiert nicht, Wartezeit wird also nicht zu Rechenzeit.
 * Die Frist prüft jedes Schlüsselwort des Dialekts ausser {@code format} vor seiner Prüfung (auch {@code $ref}, gegen
 * Verweis-Graphen mit exponentiell vielen Pfaden), jeder reguläre Ausdruck vor dem Kompilieren (auch Werte mit
 * {@code format: regex} und Muster, die das Meta-Schema über {@code format: regex} prüft), jede Musterprüfung vor dem
 * Suchen (gegen viele kurze Treffer) und der Text während des Suchens (gegen ein einzelnes rückfallreiches Muster).
 * Ein regulärer Ausdruck hat höchstens {@value #MAX_MUSTER_ZEICHEN} Zeichen, weil das Kompilieren selbst nicht
 * unterbrochen werden kann; die Grenze greift vor den Ersetzungen der Bibliothek (quadratisch bei vielen offenen
 * Zeicheneigenschaften) und vor {@code Pattern.compile} (rund 0.4 s für ein Literal von 48'000 Zeichen). Beim Start
 * wird der Prüfer einmal mit sehr langer Frist über dieselben Wege wie eine Anfrage aufgewärmt (Meta-Schema, Kompilieren,
 * gültige und ungültige Werte, {@code format}, Uhr der Frist), damit Klassenladen und JIT nicht in die Frist der ersten
 * Anfrage fallen. Reguläre Ausdrücke ({@code pattern}, {@code patternProperties}, {@code pattern} unter
 * {@code propertyNames}) laufen mit der JDK-Syntax. Verweise, die
 * ohne Fortschritt in den Daten auf sich selbst zurückführen ({@code {"$ref":"#"}} an der Wurzel), sind ungültig.
 */
@Startup
@ApplicationScoped
public class NetworkntZusatzSchemaPruefer implements ZusatzSchemaPruefer {

    static final int MAX_SCHEMA_BYTES = 16 * 1024;
    static final int MAX_WERTE_BYTES = 64 * 1024;
    static final int MAX_TIEFE = 10;
    static final int MAX_MUSTER_ZEICHEN = 1_000;
    private static final int CACHE_GROESSE = 32;
    private static final String DIALEKT = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> VERWEISE = Set.of("$ref", "$dynamicRef", "$recursiveRef");
    private static final Set<String> VERBOTEN = Set.of("$id", "$vocabulary", "$anchor", "$dynamicAnchor",
            "$recursiveAnchor");
    /** Schlüssel, deren Teilschema auf dieselbe Stelle der Daten wirkt (ohne Fortschritt in den Daten). */
    private static final Set<String> AM_ORT_EINZELN = Set.of("not", "if", "then", "else");
    private static final Set<String> AM_ORT_LISTE = Set.of("allOf", "anyOf", "oneOf");
    /** Schlüsselwörter und Formate der Seeds und üblicher Zusatz-Schemas, damit die erste Anfrage nichts mehr lädt. */
    private static final String AUFWAERMEN_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
             "required":["d"],
             "properties":{"d":{"type":"string","format":"date"},"t":{"type":"string","format":"date-time"},
              "u":{"type":"string","format":"uuid"},"e":{"type":"string","format":"email"},
              "r":{"type":"string","format":"regex"},
              "p":{"type":"string","pattern":"^\\\\d{4}(-\\\\d{2})?$","minLength":4,"maxLength":7},
              "k":{"enum":["A","B"]},"c":{"const":1},"b":{"type":"boolean"},
              "i":{"type":"integer","minimum":0,"maximum":9},"n":{"$ref":"#/$defs/n"},
              "l":{"type":"array","items":{"type":"string"},"maxItems":3},
              "o":{"type":"object","propertyNames":{"pattern":"^[a-z]+$"},"patternProperties":{"^z":{"type":"integer"}}},
              "x":{"anyOf":[{"type":"string"},{"type":"null"}]}},
             "$defs":{"n":{"type":"number","exclusiveMinimum":0,"multipleOf":0.01}}}""";
    private static final String AUFWAERMEN_GUELTIG = """
            {"d":"2026-11-12","t":"2026-11-12T12:00:00Z","u":"00000000-0000-4000-8000-000000000000",
             "e":"a@example.org","r":"^a$","p":"2026-11","k":"A","c":1,"b":true,"i":1,"n":1.5,"l":["a"],
             "o":{"z":1},"x":null}""";
    private static final String AUFWAERMEN_UNGUELTIG = """
            {"d":"2026-02-30","t":"x","u":"x","e":"x","r":"(","p":"x","k":"C","c":2,"b":1,"i":-1,"n":0,"l":[1],
             "o":{"Z":"a"},"x":1,"fremd":1}""";
    /** Frist beim Aufwärmen: praktisch keine, aber derselbe Weg wie eine Anfrage. */
    private static final long AUFWAERMEN_NANOS = Duration.ofHours(1).toNanos();

    private final long zeitgrenzeNanos;
    private final long seedZeitgrenzeNanos;
    private final Supplier<Uhren> uhrwahl;
    private final ThreadLocal<Frist> frist = new ThreadLocal<>();
    private final SchemaRegistry registry;
    private final Schema metaSchema;
    /** Paketsichtbar nur für den Test, der den prüfenden Thread an dieser Sperre warten lässt. */
    final Map<String, Geprueft> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_GROESSE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Geprueft> eldest) {
                    return size() > CACHE_GROESSE;
                }
            });

    @Inject
    public NetworkntZusatzSchemaPruefer(
            @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration zeitgrenze,
            @ConfigProperty(name = "archiv.dokumentart.seed-zeitgrenze") Duration seedZeitgrenze) {
        this(zeitgrenze, seedZeitgrenze, ANWENDUNG);
    }

    /**
     * Mit eigener Uhrwahl, damit Tests die Frist ohne echte Uhr prüfen: {@code uhrwahl} liefert beim Beginn eines
     * Aufrufs die Uhren, die für diesen ganzen Aufruf gelten.
     */
    NetworkntZusatzSchemaPruefer(Duration zeitgrenze, Duration seedZeitgrenze, Supplier<Uhren> uhrwahl) {
        this.zeitgrenzeNanos = positiv(zeitgrenze, "archiv.dokumentart.muster-zeitgrenze");
        this.seedZeitgrenzeNanos = positiv(seedZeitgrenze, "archiv.dokumentart.seed-zeitgrenze");
        this.uhrwahl = uhrwahl;
        Dialect standard = Dialects.getDraft202012();
        // Der Builder ergänzt die Schlüsselwörter der Vokabulare erst beim Bauen; «format» lässt er nicht ersetzen.
        Dialect mitFrist = Dialect.builder(standard)
                .keywords(k -> standard.getKeywords().forEach((name, keyword) -> {
                    if (!"format".equals(name)) {
                        k.put(name, new FristKeyword(keyword));
                    }
                }))
                .build();
        this.registry = SchemaRegistry.withDefaultDialect(mitFrist,
                b -> b.schemaLoader(l -> l.fetchRemoteResources(false))
                        .schemaRegistryConfig(SchemaRegistryConfig.builder()
                                .formatAssertionsEnabled(true)
                                .regularExpressionFactory(this::muster)
                                .build()));
        this.metaSchema = registry.getSchema(SchemaLocation.of(DIALEKT));
        aufwaermen();
    }

    private static long positiv(Duration grenze, String schluessel) {
        if (grenze == null || grenze.isNegative() || grenze.isZero()) {
            throw new IllegalArgumentException(schluessel + " muss grösser als 0 sein");
        }
        return grenze.toNanos();
    }

    /**
     * Uhren eines Aufrufs: {@code wand} ist günstig abzulesen, {@code rechen} misst die Frist. Für einen Thread gilt
     * Rechenzeit ≤ Wanduhr; darum liest die {@link Frist} die (unter Linux teure) Rechenzeit erst, wenn die Wanduhr
     * die verbleibende Frist überschritten hat. Sind beide dieselbe Uhr, liest die Frist sie einmal je Prüfung.
     */
    record Uhren(LongSupplier wand, LongSupplier rechen) {
        static Uhren eine(LongSupplier uhr) {
            return new Uhren(uhr, uhr);
        }
    }

    private static final LongSupplier WANDUHR = System::nanoTime;
    /** Uhrwahl der Anwendung (öffentlicher Konstruktor); als Konstante, damit ein Test sie dort nachweisen kann. */
    static final Supplier<Uhren> ANWENDUNG = NetworkntZusatzSchemaPruefer::uhrenDesThreads;

    /**
     * Uhren eines Aufrufs in der Anwendung: Rechenzeit ist die CPU-Zeit des aktuellen Threads, sonst die Wanduhr. Die
     * Wahl gilt für den ganzen Aufruf, damit Frist und Ablesen nie verschiedene Uhren mischen.
     */
    static Uhren uhrenDesThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            if (!Thread.currentThread().isVirtual() && threads.isCurrentThreadCpuTimeSupported()
                    && threads.isThreadCpuTimeEnabled() && threads.getCurrentThreadCpuTime() >= 0) {
                return new Uhren(WANDUHR, threads::getCurrentThreadCpuTime);
            }
        } catch (UnsupportedOperationException e) {
            // Wanduhr
        }
        return Uhren.eine(WANDUHR);
    }

    /**
     * Ein Probelauf beim Start über dieselben Wege wie eine Anfrage, mit sehr langer Frist; das Ergebnis zählt nicht,
     * das Schema bleibt nicht im Cache.
     */
    private void aufwaermen() {
        mitFrist(AUFWAERMEN_NANOS, () -> {
            geprueft(AUFWAERMEN_SCHEMA);
            werteGegen(AUFWAERMEN_SCHEMA, AUFWAERMEN_GUELTIG);
            try {
                werteGegen(AUFWAERMEN_SCHEMA, AUFWAERMEN_UNGUELTIG);
            } catch (FachFehler erwartet) {
                // ungültige Werte: der Fehlerweg soll ebenfalls warm sein
            }
            return null;
        });
        cache.clear();
    }

    record Geprueft(Schema kompiliert, Set<String> felder) {
    }

    @Override
    public Set<String> schemaPruefen(String schemaJson) {
        return mitFrist(zeitgrenzeNanos, () -> geprueft(schemaJson).felder());
    }

    @Override
    public Set<String> seedSchemaPruefen(String schemaJson) {
        return mitFrist(seedZeitgrenzeNanos, () -> geprueft(schemaJson).felder());
    }

    @Override
    public void wertePruefen(String schemaJson, String werteJson) {
        mitFrist(zeitgrenzeNanos, () -> {
            werteGegen(schemaJson, werteJson);
            return null;
        });
    }

    private void werteGegen(String schemaJson, String werteJson) {
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
        } catch (RuntimeException | StackOverflowError e) {
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

    /**
     * Frist eines Aufrufs nach der Rechenzeit (Review PR #81, S1): Die Wanduhr ist eine obere Schranke der Rechenzeit
     * des Threads. Solange sie die Stelle {@code naechste} nicht überschritten hat, kann auch die Rechenzeit die Frist
     * nicht überschritten haben, und die Rechenzeit bleibt ungelesen. Sonst liest die Frist die Rechenzeit und rückt
     * {@code naechste} um den Rest der Frist vor. Gehört einem Thread (ThreadLocal bzw. eine Musterprüfung).
     */
    private static final class Frist {
        private final Uhren uhren;
        private final long rechenBis;
        private long naechste;

        private Frist(Uhren uhren, long nanos) {
            this.uhren = uhren;
            long wand = uhren.wand().getAsLong();
            long rechen = uhren.wand() == uhren.rechen() ? wand : uhren.rechen().getAsLong();
            this.rechenBis = rechen + nanos;
            this.naechste = wand + nanos;
        }

        static Frist ab(Uhren uhren, long nanos) {
            return new Frist(uhren, nanos);
        }

        void pruefen() {
            long wand = uhren.wand().getAsLong();
            if (wand - naechste <= 0) {
                return;
            }
            long rest = rechenBis - (uhren.wand() == uhren.rechen() ? wand : uhren.rechen().getAsLong());
            if (rest < 0) {
                throw new FristAbgelaufen();
            }
            naechste = wand + rest;
        }
    }

    /** Setzt die Frist für den ganzen Aufruf, auch wenn er mehrere Muster prüft. */
    private <T> T mitFrist(long nanos, Supplier<T> aufruf) {
        if (frist.get() != null) {
            return aufruf.get();
        }
        frist.set(Frist.ab(uhrwahl.get(), nanos));
        try {
            return aufruf.get();
        } finally {
            frist.remove();
        }
    }

    /** Wie die JDK-Fabrik der Bibliothek, aber mit Frist vor dem Kompilieren, vor und beim Suchen. */
    private RegularExpression muster(String regex) {
        fristPruefen();
        if (regex.length() > MAX_MUSTER_ZEICHEN) {
            throw new MusterZuLang();
        }
        Pattern pattern = Pattern.compile(
                RegularExpressions.replaceLongformCharacterProperties(RegularExpressions.replaceDollarAnchors(regex)));
        return text -> {
            Frist gesetzt = frist.get();
            Frist f = gesetzt != null ? gesetzt : Frist.ab(uhrwahl.get(), zeitgrenzeNanos);
            f.pruefen();
            return pattern.matcher(new MitFrist(text, f)).find();
        };
    }

    /** Bricht ab, wenn die Frist des laufenden Aufrufs abgelaufen ist; ausserhalb eines Aufrufs gilt keine Frist. */
    private void fristPruefen() {
        Frist f = frist.get();
        if (f != null) {
            f.pruefen();
        }
    }

    /** Schlüsselwort des Dialekts, dessen Validator vor jeder Prüfung die Frist prüft. */
    private final class FristKeyword implements Keyword {
        private final Keyword keyword;

        FristKeyword(Keyword keyword) {
            this.keyword = keyword;
        }

        @Override
        public String getValue() {
            return keyword.getValue();
        }

        @Override
        public KeywordValidator newValidator(SchemaLocation ort, JsonNode knoten, Schema schema, SchemaContext kontext)
                throws Exception {
            KeywordValidator validator = keyword.newValidator(ort, knoten, schema, kontext);
            return validator == null ? null : new FristValidator(validator);
        }
    }

    private final class FristValidator implements KeywordValidator {
        private final KeywordValidator validator;

        FristValidator(KeywordValidator validator) {
            this.validator = validator;
        }

        @Override
        public void validate(ExecutionContext kontext, JsonNode knoten, JsonNode wurzel, NodePath ort) {
            fristPruefen();
            validator.validate(kontext, knoten, wurzel, ort);
        }

        @Override
        public void walk(ExecutionContext kontext, JsonNode knoten, JsonNode wurzel, NodePath ort, boolean pruefen) {
            fristPruefen();
            validator.walk(kontext, knoten, wurzel, ort, pruefen);
        }

        @Override
        public SchemaLocation getSchemaLocation() {
            return validator.getSchemaLocation();
        }

        @Override
        public String getKeyword() {
            return validator.getKeyword();
        }

        @Override
        public void preloadSchema() {
            validator.preloadSchema();
        }
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
        private final Frist frist;
        private int zugriffe;

        MitFrist(CharSequence text, Frist frist) {
            this.text = text;
            this.frist = frist;
        }

        @Override
        public char charAt(int index) {
            if ((++zugriffe & 1023) == 0) {
                frist.pruefen();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new MitFrist(text.subSequence(start, end), frist);
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
    static final class FristAbgelaufen extends SchemaException {
        private static final long serialVersionUID = 1L;

        FristAbgelaufen() {
            super("Prüfung nach der Zeitgrenze abgebrochen");
        }
    }

    /** Regulärer Ausdruck über {@value #MAX_MUSTER_ZEICHEN} Zeichen; als {@link SchemaException} wie oben. */
    static final class MusterZuLang extends SchemaException {
        private static final long serialVersionUID = 1L;

        MusterZuLang() {
            super("Regulärer Ausdruck zu lang");
        }
    }
}
