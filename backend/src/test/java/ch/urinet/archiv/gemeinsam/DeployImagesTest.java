package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dev Services und Deploy nutzen dieselben Images von PostgreSQL und Keycloak (Aufgabe 1.9, tech.md «Stack»).
 * Dependabot pflegt nur die Dockerfiles unter {@code deploy/infomaniak/}; zieht ein Update dort nicht in
 * {@code application.properties} nach, schlägt dieser Test fehl.
 */
class DeployImagesTest {

    private static final Path REPO = GemeinsamSchritte.REPO;
    private static final Pattern FROM = Pattern.compile(
            "^FROM\\s+(\\S+?)@(sha256:[0-9a-f]{64})(?:\\s+AS\\s+(\\S+))?$");
    private static final Pattern FROM_STAGE = Pattern.compile("^FROM\\s+(\\S+?)(?:\\s+AS\\s+(\\S+))?$");

    @Test
    void postgresqlWieInDevServices() throws IOException {
        assertThat(basisImages("deploy/infomaniak/db/Dockerfile").getFirst())
                .isEqualTo(eigenschaft("quarkus.datasource.devservices.image-name"));
    }

    @Test
    void keycloakWieInDevServices() throws IOException {
        assertThat(basisImages("deploy/infomaniak/keycloak/Dockerfile").getFirst())
                .isEqualTo(eigenschaft("quarkus.keycloak.devservices.image-name"));
    }

    @Test
    void apiUndKeycloakNutzenDasGepatchteTemurin25() throws IOException {
        String temurinJre = "eclipse-temurin:25.0.5.1_2-jre-noble";
        assertThat(basisImages("backend/src/main/docker/Dockerfile.jvm")).contains(temurinJre);
        assertThat(basisImages("deploy/infomaniak/keycloak/Dockerfile"))
                .contains("eclipse-temurin:25.0.5.1_2-jdk-noble", temurinJre);
    }

    /** Jedes {@code FROM} muss einen unveränderlichen Digest haben. */
    private static List<String> basisImages(String dockerfile) throws IOException {
        String inhalt = Files.readString(REPO.resolve(dockerfile), StandardCharsets.UTF_8);
        List<String> fromZeilen = inhalt.lines().filter(z -> z.startsWith("FROM ")).toList();
        List<String> images = new ArrayList<>();
        Set<String> stufen = new HashSet<>();
        for (String zeile : fromZeilen) {
            Matcher gepinnt = FROM.matcher(zeile);
            if (gepinnt.matches()) {
                images.add(gepinnt.group(1));
                if (gepinnt.group(3) != null) {
                    stufen.add(gepinnt.group(3));
                }
                continue;
            }
            Matcher stufe = FROM_STAGE.matcher(zeile);
            assertThat(stufe.matches()).as("FROM in %s mit Digest oder Stufenname", dockerfile).isTrue();
            assertThat(stufen).as("vorherige Stufe in %s", dockerfile).contains(stufe.group(1));
            if (stufe.group(2) != null) {
                stufen.add(stufe.group(2));
            }
        }
        return images;
    }

    private static String eigenschaft(String schluessel) throws IOException {
        Properties eigenschaften = new Properties();
        try (Reader leser = Files.newBufferedReader(
                REPO.resolve("backend/src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            eigenschaften.load(leser);
        }
        return eigenschaften.getProperty(schluessel);
    }
}
