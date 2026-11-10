package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Die Provisionierung nimmt den Präfix aus {@code archiv.ablage.bucket-praefix} (Req 6.11, Stufe A). */
@QuarkusTest
@TestProfile(MandantPraefixTest.StufeA.class)
class MandantPraefixTest {

    public static class StufeA implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.ablage.bucket-praefix", "urinet-archiv-a-");
        }
    }

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    S3Client s3;

    @Test
    void bucketMitPraefixDerStufeA() {
        assertThat(provisionierung.anlegen("probe-praefix", null, null).bucket()).isEqualTo("urinet-archiv-a-probe-praefix");
        s3.headBucket(b -> b.bucket("urinet-archiv-a-probe-praefix"));
        assertThatThrownBy(() -> s3.headBucket(b -> b.bucket("urinet-archiv-probe-praefix")))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    }
}
