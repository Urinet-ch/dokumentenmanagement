package ch.urinet.archiv.protokoll.domain;

import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * IP-Adresse aus dem Header {@code X-Real-Ip} (Traefik, design.md «Protokoll und Manifest»). Nur eine einzelne IPv4-
 * (Punktschreibweise) oder IPv6-Adresse gilt; alles andere ergibt keine Adresse, ohne die Anfrage abzulehnen. Ohne
 * Namensauflösung ({@link InetAddress#ofLiteral}).
 */
public record IpAdresse(String text) {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");
    private static final int MAX_LAENGE = 45;

    public static Optional<IpAdresse> aus(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String wert = header.strip();
        if (wert.isEmpty() || wert.length() > MAX_LAENGE
                || !(IPV4.matcher(wert).matches() || wert.contains(":") && IPV6.matcher(wert).matches())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IpAdresse(InetAddress.ofLiteral(wert).getHostAddress()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Gekürzte Adresse für Protokolle ohne Personendaten (Spec 03, Req 5.4): IPv4 ohne das letzte Byte
     * ({@code 192.0.2.17} → {@code 192.0.2.0}), IPv6 auf die ersten 48 Bit ({@code 2001:db8:1:2::5} →
     * {@code 2001:db8:1::}); ein eingebettetes IPv4 in IPv6 ({@code ::ffff:192.0.2.17}) wird wie IPv4 gekürzt.
     */
    public IpAdresse gekuerzt() {
        try {
            byte[] bytes = InetAddress.ofLiteral(text).getAddress();
            if (bytes.length == 4) {
                bytes[3] = 0;
            } else {
                for (int i = 6; i < bytes.length; i++) {
                    bytes[i] = 0;
                }
            }
            return new IpAdresse(InetAddress.getByAddress(bytes).getHostAddress());
        } catch (java.net.UnknownHostException | IllegalArgumentException e) {
            throw new IllegalStateException("Adresse nicht kürzbar", e);
        }
    }
}
