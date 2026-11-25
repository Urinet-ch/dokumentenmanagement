package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public final class BenutzerEtag {

    private BenutzerEtag() {
    }

    public static String berechnen(IdentitaetsDienst.Benutzer benutzer, BenutzerStatus status) {
        return berechnen(benutzer, status, null);
    }

    public static String berechnen(IdentitaetsDienst.Benutzer benutzer, BenutzerStatus status,
                                   Instant einladungGueltigBis) {
        Objects.requireNonNull(benutzer);
        Objects.requireNonNull(status);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", e);
        }
        schreiben(digest, benutzer.id());
        schreiben(digest, benutzer.benutzername());
        schreiben(digest, status.name());
        schreiben(digest, einladungGueltigBis == null ? null : einladungGueltigBis.toString());
        schreiben(digest, benutzer.email());
        schreiben(digest, benutzer.vorname());
        schreiben(digest, benutzer.nachname());
        benutzer.rollen().stream().map(Enum::name).sorted().forEach(wert -> schreiben(digest, wert));
        schreiben(digest, null);
        benutzer.aktionen().stream().map(Enum::name).sorted().forEach(wert -> schreiben(digest, wert));
        schreiben(digest, null);
        digest.update((byte) (benutzer.aktiv() ? 1 : 0));
        digest.update((byte) (benutzer.totp() ? 1 : 0));
        digest.update((byte) (benutzer.google() ? 1 : 0));
        digest.update((byte) Math.min(benutzer.passkeys(), 255));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void schreiben(MessageDigest digest, String wert) {
        // Länge und UTF-16-Codeeinheiten erhalten Feldgrenzen und unterscheiden auch ungültige Surrogatpaare.
        int laenge = wert == null ? -1 : wert.length();
        digest.update((byte) (laenge >>> 24));
        digest.update((byte) (laenge >>> 16));
        digest.update((byte) (laenge >>> 8));
        digest.update((byte) laenge);
        if (wert != null) {
            for (int i = 0; i < wert.length(); i++) {
                char zeichen = wert.charAt(i);
                digest.update((byte) (zeichen >>> 8));
                digest.update((byte) zeichen);
            }
        }
    }
}
