package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.DokumentArchivierungSperre;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JdbcDokumentArchivierungSperreTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");

    @Inject
    DokumentArchivierungSperre sperre;

    private final ExecutorService threads = Executors.newSingleThreadExecutor();

    @AfterEach
    void aufraeumen() {
        threads.shutdownNow();
    }

    @Test
    void gleicheMandantenDokumentKombinationWartetAufFreigabe() throws Exception {
        UUID dokumentId = UUID.randomUUID();
        UUID anderesDokument = UUID.randomUUID();
        CountDownLatch zweiterGestartet = new CountDownLatch(1);
        CountDownLatch zweiterErhieltSperre = new CountDownLatch(1);
        Future<?> zweiterLauf;

        try (DokumentArchivierungSperre.Sperre gehalten = sperre.sperren(MANDANT, dokumentId)) {
            Future<?> unabhaengig = threads.submit(() -> {
                try (DokumentArchivierungSperre.Sperre ignoriert = sperre.sperren(MANDANT, anderesDokument)) {
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            unabhaengig.get(5, TimeUnit.SECONDS);

            zweiterLauf = threads.submit(() -> {
                zweiterGestartet.countDown();
                try (DokumentArchivierungSperre.Sperre ignoriert = sperre.sperren(MANDANT, dokumentId)) {
                    zweiterErhieltSperre.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            assertThat(zweiterGestartet.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(zweiterErhieltSperre.await(300, TimeUnit.MILLISECONDS)).isFalse();
        }

        assertThat(zweiterErhieltSperre.await(5, TimeUnit.SECONDS)).isTrue();
        zweiterLauf.get(5, TimeUnit.SECONDS);
    }
}
