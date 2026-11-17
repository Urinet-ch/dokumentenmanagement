package ch.urinet.archiv.mandant.adapter.out.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Der Bucket-Name wird vor dem Löschen gegen das Mandanten-Muster geprüft; ungültige Namen erreichen S3 nie. */
class S3MandantBucketTest {

    @Test
    void ungueltigeNamenWerdenVorDemLoeschenAbgelehnt() {
        AtomicInteger aufrufe = new AtomicInteger();
        S3Client s3 = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
                (proxy, methode, argumente) -> {
                    aufrufe.incrementAndGet();
                    return null;
                });
        var bucket = new S3MandantBucket(s3, "archiv-");

        for (String name : new String[]{null, "", "archiv-", "andere-vhf", "archiv-VHF", "archiv--x", "archiv-../x",
                "archiv-vhf gsk"}) {
            assertThatThrownBy(() -> bucket.entfernenWennLeer(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(aufrufe.get()).isZero();
    }
}
