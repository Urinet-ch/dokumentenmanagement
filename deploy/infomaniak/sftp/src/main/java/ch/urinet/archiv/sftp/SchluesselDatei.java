package ch.urinet.archiv.sftp;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Autorisierte Schlüssel je Scan-Benutzer aus der Secret-Datei {@code archiv_sftp_schluessel} (Requirement 1.2).
 * Eine Zeile {@code scan-<mandant> <typ> <schlüssel> [kommentar]}; leere Zeilen und Zeilen mit {@code #} zählen nicht.
 * Eine ungültige Zeile bricht den Start ab: lieber kein Eingang als ein falsch verstandener Schlüssel.
 */
final class SchluesselDatei {

    /** Benutzername {@code scan-<mandant>}; der Mandantencode in Kleinbuchstaben, Ziffern und Bindestrichen. */
    static final Pattern BENUTZER = Pattern.compile("scan-[a-z0-9][a-z0-9-]{0,62}");

    private SchluesselDatei() {
    }

    static Map<String, List<PublicKey>> lesen(Path datei) throws IOException {
        List<String> zeilen = Files.readAllLines(datei, StandardCharsets.UTF_8);
        Map<String, List<PublicKey>> schluessel = new LinkedHashMap<>();
        for (int i = 0; i < zeilen.size(); i++) {
            String zeile = zeilen.get(i).strip();
            if (zeile.isEmpty() || zeile.startsWith("#")) {
                continue;
            }
            String[] teile = zeile.split("\\s+", 3);
            if (teile.length < 3) {
                throw new IllegalArgumentException("Schlüsseldatei, Zeile " + (i + 1)
                        + ": erwartet «scan-<mandant> <typ> <schlüssel>»");
            }
            if (!BENUTZER.matcher(teile[0]).matches()) {
                throw new IllegalArgumentException("Schlüsseldatei, Zeile " + (i + 1)
                        + ": Benutzer muss «scan-<mandant>» heissen");
            }
            PublicKey key;
            try {
                AuthorizedKeyEntry eintrag = AuthorizedKeyEntry.parseAuthorizedKeyEntry(teile[1] + " " + teile[2]);
                key = eintrag == null ? null : eintrag.resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                // Den Schlüssel selbst nicht in die Meldung übernehmen
                throw new IllegalArgumentException("Schlüsseldatei, Zeile " + (i + 1)
                        + ": Schlüssel vom Typ «" + teile[1] + "» nicht lesbar", e);
            }
            if (key == null) {
                throw new IllegalArgumentException("Schlüsseldatei, Zeile " + (i + 1) + ": kein Schlüssel");
            }
            schluessel.computeIfAbsent(teile[0], b -> new ArrayList<>()).add(key);
        }
        if (schluessel.isEmpty()) {
            throw new IllegalArgumentException("Schlüsseldatei enthält keinen Scan-Benutzer");
        }
        schluessel.replaceAll((b, l) -> List.copyOf(l));
        return Map.copyOf(schluessel);
    }
}
