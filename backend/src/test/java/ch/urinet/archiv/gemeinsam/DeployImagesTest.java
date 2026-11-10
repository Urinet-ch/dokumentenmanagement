package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
    private static final Pattern FROM = Pattern.compile("(?m)^FROM\\s+(\\S+?)@(sha256:[0-9a-f]{64})");

    @Test
    void postgresqlWieInDevServices() throws IOException {
        assertThat(basisImage("deploy/infomaniak/db/Dockerfile"))
                .isEqualTo(eigenschaft("quarkus.datasource.devservices.image-name"));
    }

    @Test
    void keycloakWieInDevServices() throws IOException {
        assertThat(basisImage("deploy/infomaniak/keycloak/Dockerfile"))
                .isEqualTo(eigenschaft("quarkus.keycloak.devservices.image-name"));
    }

    /** Erstes {@code FROM} mit Digest; jedes {@code FROM} der Datei muss dasselbe Image mit Digest nennen. */
    private static String basisImage(String dockerfile) throws IOException {
        String inhalt = Files.readString(REPO.resolve(dockerfile), StandardCharsets.UTF_8);
        Matcher treffer = FROM.matcher(inhalt);
        assertThat(treffer.find()).as("FROM mit Digest in %s", dockerfile).isTrue();
        String image = treffer.group(1);
        long anzahlFrom = inhalt.lines().filter(z -> z.startsWith("FROM ")).count();
        long anzahlGleich = FROM.matcher(inhalt).results().filter(r -> r.group(1).equals(image)).count();
        assertThat(anzahlGleich).as("alle FROM in %s mit demselben Image und Digest", dockerfile).isEqualTo(anzahlFrom);
        return image;
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
