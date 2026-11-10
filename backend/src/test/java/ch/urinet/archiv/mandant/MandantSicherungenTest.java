package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantBucket;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Letzte Sicherungen der Provisionierung (Req 1.6, 1.7): Bucket ohne Standard-Aufbewahrung, Checks im Register. */
@QuarkusTest
class MandantSicherungenTest {

    @Inject
    MandantBucket bucket;

    @Inject
    S3Client s3;

    @Inject
    AgroalDataSource datenbank;

    @Test
    void bucketMitStandardAufbewahrungAbgelehnt() {
        String name = "urinet-archiv-probe-aufbewahrung";
        s3.createBucket(b -> b.bucket(name).objectLockEnabledForBucket(true));
        s3.putObjectLockConfiguration(b -> b.bucket(name).objectLockConfiguration(k -> k.objectLockEnabled("Enabled")
                .rule(r -> r.defaultRetention(d -> d.mode(ObjectLockRetentionMode.GOVERNANCE).days(1)))));
        assertThatThrownBy(() -> bucket.einrichten(name)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Standard-Aufbewahrung");
    }

    @Test
    void registerPrueftCodeLaengeUndTag() {
        assertThatThrownBy(() -> eintragen("a".repeat(41), "12-31")).hasMessageContaining("mandant_code_laenge_ck");
        assertThatCode(() -> eintragen("a".repeat(40), "12-31")).doesNotThrowAnyException();
        assertThatThrownBy(() -> eintragen("probe-tag", "02-30")).hasMessageContaining("mandant_geschaeftsjahr_ende_tag_ck");
        assertThatThrownBy(() -> eintragen("probe-tag", "04-31")).hasMessageContaining("mandant_geschaeftsjahr_ende_tag_ck");
        assertThatCode(() -> eintragen("probe-tag", "02-29")).doesNotThrowAnyException();
    }

    /** Nur zur Probe: die Zeile wird zurückgerollt, damit kein Start eines späteren Tests am fehlenden Schema scheitert. */
    private void eintragen(String code, String ende) throws SQLException {
        try (Connection verbindung = datenbank.getConnection()) {
            verbindung.setAutoCommit(false);
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into public.mandant"
                    + " (code, name, schema_name, bucket, geschaeftsjahr_ende) values (?, ?, ?, ?, ?)")) {
                einfuegen.setString(1, code);
                einfuegen.setString(2, code);
                einfuegen.setString(3, "m_ck_" + code.replace('-', '_'));
                einfuegen.setString(4, "urinet-archiv-ck-" + code);
                einfuegen.setString(5, ende);
                einfuegen.executeUpdate();
            } finally {
                verbindung.rollback();
                verbindung.setAutoCommit(true);
            }
        }
    }
}
