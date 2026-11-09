package ch.urinet.archiv.gemeinsam;

import io.agroal.api.AgroalDataSource;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;

/** Die Dienste der Entwicklungsumgebung (Aufgabe 1.2) laufen in Tests wie in dev. */
@QuarkusTest
class EntwicklungsdiensteTest {

    @Inject
    AgroalDataSource datenbank;

    @Inject
    S3Client s3;

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    int mailpitPort;

    @Test
    void postgresql17MitPgvector() throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); Statement befehl = verbindung.createStatement()) {
            ResultSet version = befehl.executeQuery("select current_setting('server_version_num')::int / 10000");
            version.next();
            assertThat(version.getInt(1)).isEqualTo(17);
            ResultSet vektor = befehl.executeQuery("select count(*) from pg_available_extensions where name = 'vector'");
            vektor.next();
            assertThat(vektor.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void minioMitObjectLockUndVersionierung() {
        String bucket = "probe-" + UUID.randomUUID();
        s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
        assertThat(s3.getBucketVersioning(b -> b.bucket(bucket)).status()).isEqualTo(BucketVersioningStatus.ENABLED);

        PutObjectResponse abgelegt = s3.putObject(b -> b.bucket(bucket).key("archiv/probe.txt")
                .objectLockMode(ObjectLockMode.GOVERNANCE)
                .objectLockRetainUntilDate(Instant.now().plus(1, ChronoUnit.DAYS)), RequestBody.fromString("probe"));

        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key("archiv/probe.txt").versionId(abgelegt.versionId())))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    void mailpitEmpfaengtMail() {
        String betreff = "Probe " + UUID.randomUUID();
        mailer.send(Mail.withText("inhaber@example.invalid", betreff, "Probe"));
        given().baseUri("http://localhost:" + mailpitPort)
                .when().get("/api/v1/messages")
                .then().statusCode(200).body(containsString(betreff));
    }
}
