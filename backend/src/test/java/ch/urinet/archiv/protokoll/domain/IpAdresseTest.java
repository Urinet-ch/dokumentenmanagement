package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code X-Real-Ip}: nur eine gültige IPv4- oder IPv6-Adresse wird übernommen, sonst bleibt {@code ip} leer (Req 10.2). */
class IpAdresseTest {

    @ParameterizedTest
    @CsvSource({
            "203.0.113.7, 203.0.113.7",
            "' 198.51.100.1 ', 198.51.100.1",
            "2001:db8::1, 2001:db8:0:0:0:0:0:1",
            "::1, 0:0:0:0:0:0:0:1"})
    void gueltigeAdresse(String header, String erwartet) {
        assertThat(IpAdresse.aus(header)).map(IpAdresse::text).contains(erwartet);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"203.0.113.7, 198.51.100.1", "unknown", "localhost", "example.org", "203.0.113.256",
            "203.0.113", "2001:db8::g", "2001:db8::1%eth0", "[2001:db8::1]", "203.0.113.7:8080", "1.2.3.4/24"})
    void ungueltigeAdresseBleibtLeer(String header) {
        assertThat(IpAdresse.aus(header)).isEmpty();
    }
}
