package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestanmeldungTest {

    @Test
    void totpNachRfc6238() {
        // Testvektor aus RFC 6238, Anhang B (SHA1, T = 59 s), auf 6 Stellen gekürzt
        assertThat(Testanmeldung.totp("12345678901234567890", 59)).isEqualTo("287082");
    }
}
