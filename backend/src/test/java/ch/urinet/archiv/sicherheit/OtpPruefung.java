package ch.urinet.archiv.sicherheit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prüft einen Keycloak-Ablauf (flache Liste aus {@code /authentication/flows/{alias}/executions}): Jeder Weg zum Token
 * verlangt einen OTP-Schritt ohne Bedingung oder den exakt geprüften Browser-Ablauf mit Passkey. Keycloak-Regel: Gibt es
 * in einem Ablauf REQUIRED- oder CONDITIONAL-Schritte, zählen die ALTERNATIVE-Schritte nicht; sonst genügt eine Alternative.
 */
final class OtpPruefung {

    private static final List<String> OTP_SCHRITTE = List.of("auth-otp-form", "direct-grant-validate-otp");
    // Das Cookie setzt eine Sitzung voraus, die ein geschützter Ablauf angelegt hat.
    private static final String COOKIE = "auth-cookie";

    private record Knoten(Map<String, Object> schritt, List<Knoten> kinder) {
        String anforderung() {
            return (String) schritt.get("requirement");
        }

        boolean istAblauf() {
            return Boolean.TRUE.equals(schritt.get("authenticationFlow"));
        }
    }

    private OtpPruefung() {
    }

    static boolean otpOhneBedingung(List<Map<String, Object>> schritte) {
        return verlangtOtp(baum(schritte));
    }

    static boolean passkeyOderOtp(List<Map<String, Object>> schritte, Map<String, String> bedingung) {
        return schritte.size() == 6
                && schritt(schritte.get(0), 0, "auth-cookie", "ALTERNATIVE", false)
                && schritt(schritte.get(1), 0, null, "ALTERNATIVE", true)
                && schritt(schritte.get(2), 1, "auth-username-password-form", "REQUIRED", false)
                && schritt(schritte.get(3), 1, null, "CONDITIONAL", true)
                && schritt(schritte.get(4), 2, "conditional-credential", "REQUIRED", false)
                && schritte.get(4).get("authenticationConfig") instanceof String
                && schritt(schritte.get(5), 2, "auth-otp-form", "REQUIRED", false)
                && bedingung.equals(Map.of("credentials", "webauthn-passwordless", "included", "false"));
    }

    private static boolean schritt(Map<String, Object> schritt, int ebene, String provider, String anforderung,
                                   boolean ablauf) {
        return schritt.get("level") instanceof Number level && level.intValue() == ebene
                && java.util.Objects.equals(provider, schritt.get("providerId"))
                && anforderung.equals(schritt.get("requirement"))
                && ablauf == Boolean.TRUE.equals(schritt.get("authenticationFlow"));
    }

    private static boolean verlangtOtp(List<Knoten> kinder) {
        List<Knoten> verlangt = kinder.stream().filter(k -> "REQUIRED".equals(k.anforderung())).toList();
        boolean bedingt = kinder.stream().anyMatch(k -> "CONDITIONAL".equals(k.anforderung()));
        if (!verlangt.isEmpty() || bedingt) {
            return verlangt.stream().anyMatch(OtpPruefung::verlangtOtp);
        }
        List<Knoten> alternativen = kinder.stream().filter(k -> "ALTERNATIVE".equals(k.anforderung())).toList();
        return alternativen.stream().anyMatch(k -> !istCookie(k))
                && alternativen.stream().allMatch(k -> istCookie(k) || verlangtOtp(k));
    }

    private static boolean verlangtOtp(Knoten knoten) {
        if (knoten.istAblauf()) {
            return verlangtOtp(knoten.kinder());
        }
        return OTP_SCHRITTE.contains(knoten.schritt().get("providerId"));
    }

    private static boolean istCookie(Knoten knoten) {
        return !knoten.istAblauf() && COOKIE.equals(knoten.schritt().get("providerId"));
    }

    private static List<Knoten> baum(List<Map<String, Object>> schritte) {
        List<Knoten> wurzel = new ArrayList<>();
        List<List<Knoten>> ebenen = new ArrayList<>();
        ebenen.add(wurzel);
        for (Map<String, Object> schritt : schritte) {
            int ebene = ((Number) schritt.get("level")).intValue();
            Knoten knoten = new Knoten(schritt, new ArrayList<>());
            ebenen.get(ebene).add(knoten);
            while (ebenen.size() > ebene + 1) {
                ebenen.removeLast();
            }
            ebenen.add(knoten.kinder());
        }
        return wurzel;
    }
}
