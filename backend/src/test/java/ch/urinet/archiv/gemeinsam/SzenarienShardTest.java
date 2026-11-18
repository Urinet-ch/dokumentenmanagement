package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.SzenarienRestTest;
import ch.urinet.archiv.SzenarienShards;
import ch.urinet.archiv.SzenarienTest;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.PickleTag;
import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;
import io.quarkiverse.cucumber.CucumberOptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vollständigkeit der Szenario-Aufteilung ({@link SzenarienShards}, CI-Jobs «Backend-Szenarien»): Jedes Szenario
 * (bei Szenariogrundrissen jede Beispielzeile, wie Cucumber sie zählt), das die Backend-Szenarien überhaupt laufen,
 * läuft in genau einem der beiden Runner, auch ohne Spec-Tag; die Summe der Shards ist die Gesamtzahl. Ohne Quarkus-Start,
 * damit der Beweis auch im Job «Backend-Tests» ohne die Runner selbst steht.
 */
class SzenarienShardTest {

    private static final Path FEATURES = GemeinsamSchritte.REPO.resolve("backend/src/test/resources/features");

    @Test
    void jedesSzenarioLaeuftInGenauEinemShard() throws IOException {
        List<List<String>> szenarien = szenarien(FEATURES);

        Expression basis = TagExpressionParser.parse(SzenarienShards.AUSGENOMMEN);
        long gesamt = szenarien.stream().filter(basis::evaluate).count();
        long shard1 = szenarien.stream().filter(TagExpressionParser.parse(SzenarienShards.SHARD_1)::evaluate).count();
        long shard2 = szenarien.stream().filter(TagExpressionParser.parse(SzenarienShards.SHARD_2)::evaluate).count();

        assertThat(shard1).as("Szenarien in Shard 1 (SzenarienTest)").isPositive();
        assertThat(shard2).as("Szenarien in Shard 2 (SzenarienRestTest)").isPositive();
        assertThat(shard1 + shard2).as("Summe der Shards = Gesamtzahl der Backend-Szenarien (%s)", gesamt)
                .isEqualTo(gesamt);
        assertThat(fehler(szenarien)).isEmpty();
    }

    @Test
    void runnerNutzenDieAusdruecke() {
        assertThat(SzenarienTest.class.getAnnotation(CucumberOptions.class).tags()).isEqualTo(SzenarienShards.SHARD_1);
        assertThat(SzenarienRestTest.class.getAnnotation(CucumberOptions.class).tags())
                .isEqualTo(SzenarienShards.SHARD_2);
    }

    @Test
    void gegenprobe() {
        List<List<String>> szenarien = List.of(
                List.of("@spec-01", "@req-1.1"),            // Shard 1
                List.of("@spec-02"),                         // Shard 2
                List.of(),                                   // ohne Tag: Shard 2, geht nicht verloren
                List.of("@spec-07", "@req-9.9"),            // neues Spec-Tag: Shard 2
                List.of("@spec-01", "@browser"),            // nie im Backend
                List.of("@spec-02", "@sicherung"),          // nie im Backend
                List.of("@spec-03", "@mandant-bericht"));  // nie im Backend

        assertThat(fehler(szenarien)).isEmpty();
        assertThat(szenarien.stream().filter(TagExpressionParser.parse(SzenarienShards.SHARD_2)::evaluate)).hasSize(3);
        // ein Szenario in beiden oder keinem Shard wird gemeldet: Ausdruck von Shard 1 ohne Komplement
        assertThat(fehler(szenarien, SzenarienShards.SHARD_1, SzenarienShards.AUSGENOMMEN + " and @spec-02")).hasSize(2);
    }

    static List<String> fehler(List<List<String>> szenarien) {
        return fehler(szenarien, SzenarienShards.SHARD_1, SzenarienShards.SHARD_2);
    }

    private static List<String> fehler(List<List<String>> szenarien, String ausdruck1, String ausdruck2) {
        Expression basis = TagExpressionParser.parse(SzenarienShards.AUSGENOMMEN);
        Expression s1 = TagExpressionParser.parse(ausdruck1);
        Expression s2 = TagExpressionParser.parse(ausdruck2);
        List<String> fehler = new ArrayList<>();
        for (List<String> tags : szenarien) {
            int treffer = (s1.evaluate(tags) ? 1 : 0) + (s2.evaluate(tags) ? 1 : 0);
            int erwartet = basis.evaluate(tags) ? 1 : 0;
            if (treffer != erwartet) {
                fehler.add(tags + ": läuft in " + treffer + " Shards, erwartet " + erwartet);
            }
        }
        return fehler;
    }

    /** Tags (Feature, Regel, Szenario, Beispiele) je Szenario bzw. Beispielzeile aller .feature-Dateien. */
    static List<List<String>> szenarien(Path features) throws IOException {
        GherkinParser parser = GherkinParser.builder().includeSource(false).includeGherkinDocument(false)
                .includePickles(true).build();
        List<List<String>> ergebnis = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(features)) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".feature")).sorted().toList()) {
                try (Stream<Envelope> nachrichten = parser.parse(datei)) {
                    nachrichten.map(Envelope::getPickle).filter(java.util.Optional::isPresent)
                            .map(java.util.Optional::get).map(Pickle::getTags)
                            .forEach(tags -> ergebnis.add(tags.stream().map(PickleTag::getName).toList()));
                }
            }
        }
        assertThat(ergebnis).as("Szenarien unter " + features).hasSizeGreaterThan(1000);
        return ergebnis;
    }
}
