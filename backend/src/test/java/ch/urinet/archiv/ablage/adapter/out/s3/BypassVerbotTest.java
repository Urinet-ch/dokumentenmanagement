package ch.urinet.archiv.ablage.adapter.out.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bypass-Schutz im Produktionscode (Req 6.5): Interceptor in allen Profilen und kein Header-Text in {@code src/main}. */
class BypassVerbotTest {

    private static S3Client client(boolean mitVerbot) {
        // Port 1: Ein Aufruf, der den Interceptor passiert, scheitert ohne Netz
        return S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:1"))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("id", "geheim")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(o -> {
                    o.retryStrategy(r -> r.maxAttempts(1));
                    if (mitVerbot) {
                        o.addExecutionInterceptor(new BypassVerbot());
                    }
                })
                .build();
    }

    @Test
    void lehntDenBypassHeaderAbBevorErGesendetWird() {
        try (S3Client s3 = client(true)) {
            assertThat(BypassVerbot.pruefen(s3)).isSameAs(s3);
            assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket("b").key("k").versionId("v")
                    .bypassGovernanceRetention(true)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(BypassVerbot.HEADER);
            assertThatThrownBy(() -> s3.putObjectRetention(b -> b.bucket("b").key("k").bypassGovernanceRetention(true)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(BypassVerbot.HEADER);
            // Gegenprobe: ohne Bypass geht der Aufruf hinaus und scheitert erst am Netz
            assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket("b").key("k").versionId("v")))
                    .isNotInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void ohneVerbotKeinS3Client() {
        try (S3Client s3 = client(false)) {
            assertThatThrownBy(() -> BypassVerbot.pruefen(s3)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BypassVerbot.class.getName());
        }
    }

    @Test
    void derHeaderTextStehtInSrcMainNurImVerbot() throws IOException {
        Path wurzel = Path.of("src/main");
        assertThat(wurzel).isDirectory();
        List<String> mitHeader;
        List<String> mitAufruf;
        try (Stream<Path> dateien = Files.walk(wurzel)) {
            List<Path> alle = dateien.filter(Files::isRegularFile).toList();
            mitHeader = treffer(alle, d -> d.toLowerCase(Locale.ROOT).contains(BypassVerbot.HEADER));
            mitAufruf = treffer(alle, d -> d.contains("bypassGovernanceRetention("));
        }
        assertThat(mitHeader).containsExactly(
                Path.of("src/main/java/ch/urinet/archiv/ablage/adapter/out/s3/BypassVerbot.java").toString());
        assertThat(mitAufruf).isEmpty();
    }

    private static List<String> treffer(List<Path> dateien, Predicate<String> bedingung) {
        return dateien.stream().filter(d -> bedingung.test(text(d))).map(Path::toString).toList();
    }

    private static String text(Path datei) {
        try {
            return Files.readString(datei);
        } catch (MalformedInputException e) {
            // Binärdatei (z. B. Bild): kein Quelltext
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
