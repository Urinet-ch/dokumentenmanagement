package ch.urinet.archiv.ablage.adapter.out.s3;

import ch.urinet.archiv.ablage.domain.AblageGestoert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Einstufung der Fehler beim Prüfen (Req 15.9): vorübergehend, Abbruch oder dauerhaft. */
class PruefzugriffTest {

    private static final String SCHLUESSEL = "archiv/2026/datei.pdf";

    @AfterEach
    void interruptFlagZuruecksetzen() {
        Thread.interrupted();
    }

    @Test
    void serverfehlerUndDrosselungSindVoruebergehend() {
        for (int status : new int[]{500, 503, 429}) {
            S3Exception fehler = (S3Exception) S3Exception.builder().statusCode(status).build();
            assertThatThrownBy(() -> S3Ablage.pruefzugriff(SCHLUESSEL, () -> {
                throw fehler;
            })).isInstanceOf(AblageGestoert.class).hasCause(fehler).hasMessageContaining(SCHLUESSEL);
        }
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void verbindungsfehlerUndZeitueberschreitungSindVoruebergehendOhneAbbruch() {
        SdkClientException verbindung = SdkClientException.create("Verbindung", new SocketTimeoutException("Read"));
        ApiCallTimeoutException zeit = ApiCallTimeoutException.create(120_000);
        for (RuntimeException fehler : new RuntimeException[]{verbindung, zeit}) {
            assertThatThrownBy(() -> S3Ablage.pruefzugriff(SCHLUESSEL, () -> {
                throw fehler;
            })).isInstanceOf(AblageGestoert.class).hasCause(fehler);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }

    /** Das SDK löscht das Interrupt-Flag und meldet die Unterbrechung als {@link AbortedException}. */
    @Test
    void abbruchWirdWeitergereichtUndSetztDasFlag() {
        RuntimeException[] abbrueche = {AbortedException.builder().message("Thread was interrupted").build(),
                SdkClientException.create("Lesen fehlgeschlagen",
                        new UncheckedIOException(new InterruptedIOException("unterbrochen")))};
        for (RuntimeException abbruch : abbrueche) {
            assertThatThrownBy(() -> S3Ablage.pruefzugriff(SCHLUESSEL, () -> {
                throw abbruch;
            })).isSameAs(abbruch);
            assertThat(Thread.interrupted()).as("Flag gesetzt").isTrue();
        }
    }

    @Test
    void dauerhafteFehlerBleibenUnveraendert() {
        S3Exception verweigert = (S3Exception) S3Exception.builder().statusCode(403).build();
        IllegalStateException sperre = new IllegalStateException("Sperre nicht verlängert");
        for (RuntimeException fehler : new RuntimeException[]{verweigert, sperre}) {
            assertThatThrownBy(() -> S3Ablage.pruefzugriff(SCHLUESSEL, () -> {
                throw fehler;
            })).isSameAs(fehler);
        }
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void ergebnisOhneFehler() {
        assertThat(S3Ablage.pruefzugriff(SCHLUESSEL, () -> "ok")).isEqualTo("ok");
    }
}
