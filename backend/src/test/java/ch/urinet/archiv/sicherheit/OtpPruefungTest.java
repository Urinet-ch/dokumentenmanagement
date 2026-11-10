package ch.urinet.archiv.sicherheit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtpPruefungTest {

    @Test
    void otpVerlangtOhneBedingung() {
        assertThat(OtpPruefung.otpOhneBedingung(List.of(
                schritt(0, "direct-grant-validate-username", "REQUIRED"),
                schritt(0, "direct-grant-validate-password", "REQUIRED"),
                schritt(0, "direct-grant-validate-otp", "REQUIRED")))).isTrue();
    }

    @Test
    void otpInBedingtemUnterablaufGenuegtNicht() {
        assertThat(OtpPruefung.otpOhneBedingung(List.of(
                schritt(0, "direct-grant-validate-password", "REQUIRED"),
                ablauf(0, "CONDITIONAL"),
                schritt(1, "conditional-user-configured", "REQUIRED"),
                schritt(1, "direct-grant-validate-otp", "REQUIRED")))).isFalse();
    }

    @Test
    void cookieUndFormularMitOtpSindGeschuetzt() {
        assertThat(OtpPruefung.otpOhneBedingung(List.of(
                schritt(0, "auth-cookie", "ALTERNATIVE"),
                ablauf(0, "ALTERNATIVE"),
                schritt(1, "auth-username-password-form", "REQUIRED"),
                schritt(1, "auth-otp-form", "REQUIRED")))).isTrue();
    }

    @Test
    void alternativeOhneOtpDanebenIstUngeschuetzt() {
        assertThat(OtpPruefung.otpOhneBedingung(List.of(
                schritt(0, "auth-cookie", "ALTERNATIVE"),
                schritt(0, "auth-username-password-form", "ALTERNATIVE"),
                ablauf(0, "ALTERNATIVE"),
                schritt(1, "auth-username-password-form", "REQUIRED"),
                schritt(1, "auth-otp-form", "REQUIRED")))).isFalse();
    }

    @Test
    void nurCookieGenuegtNicht() {
        assertThat(OtpPruefung.otpOhneBedingung(List.of(schritt(0, "auth-cookie", "ALTERNATIVE")))).isFalse();
    }

    @Test
    void deaktivierterOtpSchrittGenuegtNicht() {
        assertThat(OtpPruefung.otpOhneBedingung(List.of(
                schritt(0, "direct-grant-validate-password", "REQUIRED"),
                schritt(0, "direct-grant-validate-otp", "DISABLED")))).isFalse();
    }

    private static Map<String, Object> schritt(int ebene, String anbieter, String anforderung) {
        return Map.of("level", ebene, "providerId", anbieter, "requirement", anforderung, "authenticationFlow", false);
    }

    private static Map<String, Object> ablauf(int ebene, String anforderung) {
        return Map.of("level", ebene, "requirement", anforderung, "authenticationFlow", true);
    }
}
