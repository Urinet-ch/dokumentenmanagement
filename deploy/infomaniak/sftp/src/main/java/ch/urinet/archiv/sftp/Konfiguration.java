package ch.urinet.archiv.sftp;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Konfiguration des Dienstes aus Umgebungsvariablen. Sie enthalten nur Pfade und Schalter, nie Schlüssel:
 * Host-Schlüssel und autorisierte Schlüssel liegen als Secret-Dateien unter {@code /run/secrets/} (tech.md Regel 7,
 * Requirement 1.10). Öffentlich, weil die Szenarien des Backends den Dienst in-process starten (Aufgabe 27.3).
 *
 * @param port               Port im Container, über 1024, damit der Prozess ohne Root lauscht
 * @param wurzel             Wurzel des Eingangs; je Benutzer ein Ordner {@code <wurzel>/scan-<mandant>}
 * @param hostSchluessel     Datei mit dem privaten Host-Schlüssel (OpenSSH- oder PEM-Format), wird nie erzeugt
 * @param schluesselDatei    Datei mit Zeilen {@code scan-<mandant> <typ> <schlüssel>}
 * @param aeltereVerfahren   ausdrücklich erlaubte ältere Verfahren (Spike 27.4, Requirement 1.5), Standard leer
 */
public record Konfiguration(int port, Path wurzel, Path hostSchluessel, Path schluesselDatei,
        List<String> aeltereVerfahren) {

    static final String PORT = "ARCHIV_SFTP_PORT";
    static final String WURZEL = "ARCHIV_SFTP_WURZEL";
    static final String HOSTSCHLUESSEL = "ARCHIV_SFTP_HOSTSCHLUESSEL";
    static final String SCHLUESSEL = "ARCHIV_SFTP_SCHLUESSEL";
    static final String AELTERE_VERFAHREN = "ARCHIV_SFTP_AELTERE_VERFAHREN";

    public Konfiguration {
        if (port <= 1024 || port > 65535) {
            throw new IllegalArgumentException(PORT + " muss zwischen 1025 und 65535 liegen (Dienst ohne Root)");
        }
        aeltereVerfahren = List.copyOf(aeltereVerfahren);
    }

    static Konfiguration aus(Map<String, String> umgebung) {
        String port = umgebung.getOrDefault(PORT, "2222").trim();
        int nummer;
        try {
            nummer = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PORT + " ist keine Zahl", e);
        }
        String liste = umgebung.getOrDefault(AELTERE_VERFAHREN, "");
        List<String> aeltere = Arrays.stream(liste.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Konfiguration(nummer,
                Path.of(umgebung.getOrDefault(WURZEL, "/srv/eingang")),
                Path.of(umgebung.getOrDefault(HOSTSCHLUESSEL, "/run/secrets/archiv_sftp_hostkey")),
                Path.of(umgebung.getOrDefault(SCHLUESSEL, "/run/secrets/archiv_sftp_schluessel")),
                aeltere);
    }
}
