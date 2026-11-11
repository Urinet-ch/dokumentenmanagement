package ch.urinet.archiv.gemeinsam;

import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
            "(?ism)\\b(drop\\s+(table|column|view|schema|sequence|type)"
                    + "|rename\\s+(to|column)"
                    + "|alter\\s+column\\s+\\S+\\s+(set\\s+not\\s+null|(set\\s+data\\s+)?type))\\b"
                    // TRUNCATE nur als Anweisung, nicht im Trigger «before … or truncate» der append-only-Tabellen
                    + "|(^|;|>)\\s*truncate\\b");

    /**
     * Geprüfte Ausnahmen: Changeset und SHA-256 seines Inhalts. Nur für schon gemergte Changesets, die das Muster
     * treffen, den Vorgänger aber nachweislich nicht brechen (Changesets werden nie geändert, tech.md Regel 11); ändert
     * sich die Datei, gilt die Ausnahme nicht mehr.
     * <ul>
     * <li>005 (PR #41, Aufgabe 2.5): {@code NOT NULL} auf {@code auftrag.mandant}. Der Vorgänger-Stand (vor #41)
     * schreibt nicht in {@code auftrag}, es gibt keine Zeile ohne Mandant.</li>
     * </ul>
     */
    private static final Map<String, String> GEPRUEFT = Map.of(
            "backend/src/main/resources/db/public/changesets/005-auftrag-mandant-pflicht.xml",
            "5510f05bce3c6a4952ea29ed5f1076d0fe6cf4452d724ae7f964ee28ea59eee0");

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
            String name = REPO.relativize(changeset).toString().replace('\\', '/');
            if (GEPRUEFT.containsKey(name) && GEPRUEFT.get(name).equals(sha256(changeset))) {
                continue;
            }
            String inhalt = Files.readString(changeset, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
            assertThat(LIQUIBASE_BRUCH.matcher(inhalt).find()).as("Liquibase-Änderung bricht Vorgänger in %s", name)
                    .isFalse();
            assertThat(SQL_BRUCH.matcher(inhalt).find()).as("SQL bricht Vorgänger in %s", name).isFalse();
        }
    }

    private static String sha256(Path datei) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(datei)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
