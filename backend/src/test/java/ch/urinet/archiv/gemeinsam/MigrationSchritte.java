package ch.urinet.archiv.gemeinsam;

import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rückwärtskompatible Migrationen (Req 16.11, Aufgabe 1.9): Ein Rollback setzt nur die Images zurück, die Datenbank
 * bleibt auf dem neuen Stand. Das Vorgänger-Release muss mit ihr laufen, darum sind Änderungen verboten, die Tabellen
 * oder Spalten entfernen, umbenennen oder enger machen. Braucht eine Migration das doch, ist das ein Entscheid von
 * Fabian (zweistufig über zwei Releases, design.md «Betrieb auf der VPS»).
 */
public class MigrationSchritte {

    private static final Path REPO = GemeinsamSchritte.REPO;

    private static final Pattern LIQUIBASE_BRUCH = Pattern.compile(
            "<(dropTable|dropColumn|dropView|dropSequence|renameTable|renameColumn|renameView|renameSequence"
                    + "|modifyDataType|addNotNullConstraint|mergeColumns|dropAllForeignKeyConstraints)\\b");

    private static final Pattern SQL_BRUCH = Pattern.compile(
            "(?is)\\b(drop\\s+(table|column|view|schema|sequence|type)"
                    + "|rename\\s+(to|column)"
                    + "|alter\\s+column\\s+\\S+\\s+(set\\s+not\\s+null|(set\\s+data\\s+)?type)"
                    + "|truncate)\\b");

    @Dann("enthält kein Changeset unter «{word}» eine Änderung, die das Vorgänger-Release bricht")
    public void changesetsRueckwaertskompatibel(String verzeichnis) throws IOException {
        List<Path> changesets;
        try (Stream<Path> dateien = Files.walk(REPO.resolve(verzeichnis))) {
            changesets = dateien.filter(Files::isRegularFile)
                    .filter(p -> p.getParent().getFileName().toString().equals("changesets"))
                    .sorted().toList();
        }
        assertThat(changesets).isNotEmpty();
        for (Path changeset : changesets) {
            String inhalt = Files.readString(changeset, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
            String name = REPO.relativize(changeset).toString();
            assertThat(LIQUIBASE_BRUCH.matcher(inhalt).find()).as("Liquibase-Änderung bricht Vorgänger in %s", name)
                    .isFalse();
            assertThat(SQL_BRUCH.matcher(inhalt).find()).as("SQL bricht Vorgänger in %s", name).isFalse();
        }
    }

    @Und("der Workflow «{word}» setzt bei einem Fehler nur die Images zurück, nie die Datenbank")
    public void rollbackOhneDatenbank(String workflow) throws IOException {
        String inhalt = Files.readString(REPO.resolve(workflow), StandardCharsets.UTF_8);
        assertThat(inhalt).contains("\"rollback $APP\"")
                .doesNotContainIgnoringCase("down -v")
                .doesNotContainIgnoringCase("volume rm")
                .doesNotContainIgnoringCase("dropdb")
                .doesNotContainIgnoringCase("liquibase rollback")
                .doesNotContainIgnoringCase("rollbackCount");
    }
}
