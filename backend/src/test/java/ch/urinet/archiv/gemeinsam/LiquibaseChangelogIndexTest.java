package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LiquibaseChangelogIndexTest {

    private static final Path DB = Path.of("src/main/resources/db");
    private static final Pattern INCLUDE = Pattern.compile("<include file=\"([^\"]+\\.xml)\"");
    private static final Pattern CHANGESET = Pattern.compile("<changeSet\\s+id=\"([^\"]+)\"");

    @Test
    void letzteZwanzigChangesetsHabenEindeutigeLiquibaseIdsUndNummern() throws IOException {
        for (String scope : List.of("public", "mandant")) {
            List<Changeset> changesets = changesets(scope);
            List<Changeset> letzteZwanzig = changesets.subList(Math.max(0, changesets.size() - 20), changesets.size());

            assertThat(letzteZwanzig).hasSize(20);
            assertThat(letzteZwanzig.stream().map(Changeset::id).toList()).doesNotHaveDuplicates();

            Map<String, Set<String>> dateienJeNummer = new HashMap<>();
            for (Changeset changeset : letzteZwanzig) {
                dateienJeNummer.computeIfAbsent(changeset.nummer(), ignoriert -> new HashSet<>())
                        .add(changeset.datei());
            }
            assertThat(dateienJeNummer.entrySet().stream()
                    .filter(eintrag -> eintrag.getValue().size() > 1)
                    .map(Map.Entry::getKey)
                    .toList()).as("Nummern, die in verschiedenen Dateien von db/%s wiederverwendet werden", scope)
                    .isEmpty();
        }
    }

    @Test
    void includeReihenfolgeEnthaeltNurBekannteNachtraege() throws IOException {
        assertThat(includeAbstiege("public")).isEmpty();
        assertThat(includeAbstiege("mandant"))
                .containsExactly("082->060", "090->070", "130->121");
    }

    private static List<Changeset> changesets(String scope) throws IOException {
        String master = Files.readString(DB.resolve(scope).resolve("changelog.xml"));
        Matcher includes = INCLUDE.matcher(master);
        List<Changeset> changesets = new ArrayList<>();
        while (includes.find()) {
            String datei = includes.group(1);
            String inhalt = Files.readString(DB.resolve(scope).resolve(datei));
            Matcher ids = CHANGESET.matcher(inhalt);
            while (ids.find()) {
                String id = ids.group(1);
                changesets.add(new Changeset(id.substring(0, 3), id, datei));
            }
        }
        return List.copyOf(changesets);
    }

    private static List<String> includeAbstiege(String scope) throws IOException {
        String master = Files.readString(DB.resolve(scope).resolve("changelog.xml"));
        Matcher includes = INCLUDE.matcher(master);
        List<String> abstiege = new ArrayList<>();
        int vorher = -1;
        while (includes.find()) {
            String datei = includes.group(1);
            int nummer = Integer.parseInt(Path.of(datei).getFileName().toString().substring(0, 3));
            if (nummer < vorher) {
                abstiege.add("%03d->%03d".formatted(vorher, nummer));
            }
            vorher = nummer;
        }
        return List.copyOf(abstiege);
    }

    private record Changeset(String nummer, String id, String datei) {
    }
}
