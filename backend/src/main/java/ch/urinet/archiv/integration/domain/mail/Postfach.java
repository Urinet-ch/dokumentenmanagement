package ch.urinet.archiv.integration.domain.mail;

import java.util.Objects;

/**
 * Postfach des Mail-Eingangs (Spec 02, Req 1.1; design.md «E-Mail-Eingang»): IMAP über TLS. Host, Port und Benutzer
 * sind Konfiguration je Mandant (Tabelle {@code integration_mail}), nie fest im Code; das Passwort gehört nicht hierher.
 */
public record Postfach(String host, int port, String benutzer) {

    /** IMAP über TLS (IMAPS, RFC 8314). */
    public static final int STANDARD_PORT = 993;

    public Postfach {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(benutzer, "benutzer");
        if (host.isBlank() || host.length() > 253 || !host.matches("[A-Za-z0-9.:\\-\\[\\]]+")) {
            throw new IllegalArgumentException("Ungültiger Host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Ungültiger Port");
        }
        if (benutzer.isBlank() || benutzer.length() > 320 || benutzer.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.isWhitespace(c))) {
            throw new IllegalArgumentException("Ungültiger Benutzer");
        }
    }
}
