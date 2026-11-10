package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantMigration;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schritte zu Req 1.1, 1.5 und 9.6: Mandanten anlegen, getrennt halten und migrieren. */
public class MandantSchritte {

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantMigration migration;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    S3Client s3;

    @Inject
    ObjectMapper json;

    private MandantFehler fehler;

    private final List<String> eingetragen = new ArrayList<>();

    @Wenn("der Betreiber die Mandanten «{word}» und «{word}» anlegt")
    public void mandantenAnlegen(String a, String b) throws SQLException {
        for (String code : List.of(a, b)) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
    }

    @Wenn("der Betreiber den Mandanten «{word}» anlegt")
    public void anlegen(String code) {
        versuchen(code, null);
    }

    @Wenn("der Betreiber den Mandanten «{word}» mit Geschäftsjahresende «{word}» anlegt")
    public void anlegenMitGeschaeftsjahresende(String code, String ende) {
        versuchen(code, ende);
    }

    @Dann("lehnt das Archiv mit «{word}» ab")
    public void abgelehnt(String grund) {
        assertThat(fehler).as("Ablehnung erwartet").isNotNull();
        assertThat(fehler.grund().name()).isEqualTo(grund);
    }

    @Dann("gibt es die Schemas «{word}» und «{word}» je mit eigener Tabelle «{word}»")
    public void schemas(String a, String b, String tabelle) throws SQLException {
        tabelleVorhanden(a, tabelle);
        tabelleVorhanden(b, tabelle);
    }

    @Dann("gibt es das Schema «{word}» mit der Tabelle «{word}»")
    public void tabelleVorhanden(String schema, String tabelle) throws SQLException {
        assertThat(werte("select table_name from information_schema.tables where table_schema = ?", schema))
                .contains(tabelle);
    }

    @Und("eine Dokumentart im Schema «{word}» ist im Schema «{word}» nicht sichtbar")
    public void schemasGetrennt(String quelle, String anderes) throws SQLException {
        assertThat(werte("select code from " + quelle + ".dokumentart")).contains("PROBE_BELEG");
        assertThat(werte("select code from " + anderes + ".dokumentart")).doesNotContain("PROBE_BELEG");
    }

    @Und("die Buckets «{word}» und «{word}» haben Object Lock und Versionierung ohne Standard-Aufbewahrung")
    public void objectLock(String a, String b) {
        for (String bucket : List.of(a, b)) {
            var sperre = s3.getObjectLockConfiguration(r -> r.bucket(bucket)).objectLockConfiguration();
            assertThat(sperre.objectLockEnabled()).as(bucket).isEqualTo(ObjectLockEnabled.ENABLED);
            assertThat(sperre.rule()).as(bucket + ": keine Standard-Aufbewahrung").isNull();
            assertThat(s3.getBucketVersioning(r -> r.bucket(bucket)).status()).as(bucket)
                    .isEqualTo(BucketVersioningStatus.ENABLED);
        }
    }

    @Und("ein Objekt im Bucket «{word}» fehlt im Bucket «{word}»")
    public void bucketsGetrennt(String quelle, String anderes) {
        s3.putObject(r -> r.bucket(quelle).key("eingang/trennung.txt"), RequestBody.fromString("probe"));
        assertThat(s3.headObject(r -> r.bucket(quelle).key("eingang/trennung.txt")).contentLength()).isEqualTo(5);
        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(anderes).key("eingang/trennung.txt")))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    }

    @Und("die Buckets «{word}» und «{word}» tragen die Bucket Policy mit Deny auf «{word}»")
    public void policy(String a, String b, String aktion) throws IOException {
        for (String bucket : List.of(a, b)) {
            JsonNode richtlinie = json.readTree(s3.getBucketPolicy(r -> r.bucket(bucket)).policy());
            boolean deny = false;
            for (JsonNode regel : richtlinie.path("Statement")) {
                if ("Deny".equals(regel.path("Effect").asText()) && regel.path("Action").toString().contains(aktion)
                        && regel.path("Resource").toString().contains("arn:aws:s3:::" + bucket + "/*")
                        && alle(regel.path("Principal")) && regel.path("Condition").isMissingNode()) {
                    deny = true;
                }
            }
            assertThat(deny).as(bucket).isTrue();
        }
    }

    /** Principal {@code "*"} oder {@code {"AWS": "*"}} (MinIO gibt die zweite Form zurück). */
    private static boolean alle(JsonNode principal) {
        JsonNode aws = principal.path("AWS");
        return "*".equals(principal.asText()) || "*".equals(aws.asText())
                || (aws.isArray() && aws.size() == 1 && "*".equals(aws.get(0).asText()));
    }

    @Dann("enthält das Schema «{word}» genau die Dokumentarten aus «{word}»")
    public void seedEingespielt(String schema, String seed) throws IOException, SQLException {
        List<String> erwartet = new ArrayList<>();
        try (InputStream inhalt = getClass().getClassLoader().getResourceAsStream(seed)) {
            json.readTree(inhalt).path("dokumentarten").forEach(a -> erwartet.add(a.path("code").asText()));
        }
        assertThat(erwartet).hasSize(11);
        assertThat(werte("select code from " + schema + ".dokumentart")).containsExactlyInAnyOrderElementsOf(erwartet);
        assertThat(werte("select vertraulichkeit || '/' || gesetzliche_pflicht from " + schema
                + ".dokumentart where code = 'PROJEKTDOKUMENT'")).containsExactly("NORMAL/false");
    }

    @Und("es gibt weder den Registereintrag «{word}» noch das Schema «{word}»")
    public void nichtsAngelegt(String code, String schema) throws SQLException {
        assertThat(imRegister(code)).isFalse();
        assertThat(werte("select schema_name from information_schema.schemata where schema_name = ?", schema)).isEmpty();
    }

    @Angenommen("im Register steht der aktive Mandant «{word}» ohne Schema")
    public void ohneSchema(String code) throws SQLException {
        eintragen(code, false);
    }

    @Angenommen("im Register steht der aktive Mandant «{word}» mit leerem Schema")
    public void leeresSchema(String code) throws SQLException {
        eintragen(code, true);
    }

    private void eintragen(String code, boolean leeresSchema) throws SQLException {
        String schema = "m_" + code.replace('-', '_');
        eingetragen.add(code);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung.prepareStatement(
                "insert into public.mandant (code, name, schema_name, bucket) values (?, ?, ?, ?) on conflict do nothing")) {
            einfuegen.setString(1, code);
            einfuegen.setString(2, code);
            einfuegen.setString(3, schema);
            einfuegen.setString(4, "urinet-archiv-" + code);
            einfuegen.executeUpdate();
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("drop schema if exists " + schema + " cascade");
                if (leeresSchema) {
                    befehl.execute("create schema " + schema);
                }
            }
        }
    }

    /** Ohne Aufräumen bräche jeder spätere Start im selben Container am fehlenden Schema ab. */
    @After
    public void aufraeumen() throws SQLException {
        for (String code : eingetragen) {
            try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement loeschen =
                    verbindung.prepareStatement("delete from public.mandant where code = ?")) {
                loeschen.setString(1, code);
                loeschen.executeUpdate();
            }
        }
        eingetragen.clear();
    }

    @Wenn("das Archiv die aktiven Mandanten migriert")
    public void migrieren() {
        fehler = null;
        try {
            migration.aktiveMigrieren();
        } catch (MandantFehler e) {
            fehler = e;
        }
    }

    @Dann("bricht das Archiv mit «{word}» ab")
    public void abgebrochen(String grund) {
        abgelehnt(grund);
    }

    @Und("es gibt das Schema «{word}» nicht")
    public void schemaFehlt(String schema) throws SQLException {
        assertThat(werte("select schema_name from information_schema.schemata where schema_name = ?", schema)).isEmpty();
    }

    @Dann("führt das Register für «{word}» das Geschäftsjahresende «{word}»")
    public void geschaeftsjahresende(String code, String ende) throws SQLException {
        assertThat(werte("select geschaeftsjahr_ende from public.mandant where code = ?", code)).containsExactly(ende);
    }

    private void versuchen(String code, String ende) {
        fehler = null;
        try {
            provisionierung.anlegen(code, null, ende);
        } catch (MandantFehler e) {
            fehler = e;
        }
    }

    private boolean imRegister(String code) throws SQLException {
        return !werte("select code from public.mandant where code = ?", code).isEmpty();
    }

    private List<String> werte(String sql, String... parameter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                abfrage.setString(i + 1, parameter[i]);
            }
            List<String> ergebnis = new ArrayList<>();
            try (ResultSet zeilen = abfrage.executeQuery()) {
                while (zeilen.next()) {
                    ergebnis.add(zeilen.getString(1));
                }
            }
            return ergebnis;
        }
    }
}
