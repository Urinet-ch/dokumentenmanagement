package ch.urinet.archiv.driveimport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liest das API-Token (Scope {@code import}) aus einer Datei mit Rechten 600 (tech.md, Regel 7; tasks.md 4.7, 4.8):
 * Pfad aus {@code --token-datei}, sonst aus {@code ARCHIV_TOKEN_DATEI}, sonst {@code ~/.config/urinet-archiv/token}.
 * Das Token selbst kommt nie aus einer Umgebungsvariable. Verweigert wird eine Datei, die nicht regulär ist (auch ein
 * symbolischer Link), nicht dem angemeldeten Benutzer gehört, andere Rechte als Lesen und Schreiben für den Eigentümer
 * hat (600 oder 400) oder
 * kein Token im Format des Archivs enthält. Keine Meldung enthält den Inhalt der Datei.
 */
final class TokenDatei {

    static final String UMGEBUNG = "ARCHIV_TOKEN_DATEI";
    private static final int MAX_GROESSE = 4096;
    private static final Pattern FORMAT = Pattern.compile("ua_[a-z0-9]{12}_[A-Za-z0-9_-]{43}");
    private static final Set<PosixFilePermission> ERLAUBT =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Ungültige Token-Datei; die Meldung nennt nur den Pfad und den Grund. */
    static final class Ungueltig extends Exception {
        private static final long serialVersionUID = 1L;

        Ungueltig(String meldung) {
            super(meldung);
        }
    }

    private TokenDatei() {
    }

    static Path pfad(Path angegeben, Map<String, String> umgebung, String home) {
        if (angegeben != null) {
            return angegeben;
        }
        String ausUmgebung = umgebung.get(UMGEBUNG);
        if (ausUmgebung != null && !ausUmgebung.isBlank()) {
            return Path.of(ausUmgebung);
        }
        return Path.of(home, ".config", "urinet-archiv", "token");
    }

    static String lesen(Path datei) throws Ungueltig {
        PosixFileAttributes attribute;
        try {
            attribute = Files.readAttributes(datei, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            throw new Ungueltig("Token-Datei " + datei + ": Dateirechte lassen sich auf diesem System nicht prüfen "
                    + "(POSIX nötig, z. B. macOS oder Linux).");
        } catch (IOException e) {
            throw new Ungueltig("Token-Datei " + datei + " nicht gefunden oder nicht lesbar. Bitte das Token mit "
                    + "Rechten 600 dort ablegen oder den Pfad mit --token-datei angeben.");
        }
        if (!attribute.isRegularFile()) {
            throw new Ungueltig("Token-Datei " + datei + " ist keine gewöhnliche Datei (z. B. ein symbolischer Link).");
        }
        String benutzer = System.getProperty("user.name");
        if (benutzer != null && !benutzer.equals(attribute.owner().getName())) {
            throw new Ungueltig("Token-Datei " + datei + " gehört nicht dem angemeldeten Benutzer.");
        }
        if (!ERLAUBT.containsAll(attribute.permissions())) {
            throw new Ungueltig("Token-Datei " + datei + " hat zu weite Rechte (nur Lesen und Schreiben für den "
                    + "Eigentümer erlaubt). "
                    + "Bitte mit «chmod 600 " + datei + "» einschränken.");
        }
        String token;
        try {
            // NOFOLLOW_LINKS auch beim Lesen: Ein zwischen Prüfung und Lesen eingeschobener Link wird nicht gelesen.
            try (InputStream ein = Files.newInputStream(datei, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                token = new String(ein.readNBytes(MAX_GROESSE), StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            throw new Ungueltig("Token-Datei " + datei + " ist nicht lesbar.");
        }
        if (!FORMAT.matcher(token).matches()) {
            throw new Ungueltig("Token-Datei " + datei + " enthält kein gültiges API-Token. Bitte das Token so "
                    + "ablegen, wie es beim Anlegen angezeigt wurde.");
        }
        return token;
    }
}
