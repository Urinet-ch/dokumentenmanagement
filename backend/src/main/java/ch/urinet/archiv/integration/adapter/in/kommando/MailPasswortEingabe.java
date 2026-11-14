package ch.urinet.archiv.integration.adapter.in.kommando;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Passwort für {@code archiv mail einrichten}: die erste Zeile der Standardeingabe, gelesen in {@code ArchivMain.main},
 * bevor Quarkus startet. Befund Stufe A (13.11.2026): Las das Kommando die Standardeingabe erst nach dem Start (rund
 * 50 s), kam im Wegwerf-Container ({@code docker compose run -T}) nichts mehr an; las ein {@code sh} die Zeile sofort
 * und reichte sie weiter, klappte es. Das Kommando nimmt das Passwort genau einmal, danach ist der Halter leer. Das
 * Passwort erscheint nie in Log, Ausgabe oder Exception.
 */
public final class MailPasswortEingabe {

    public static final String FEHLT = "Passwort fehlt (erste Zeile der Standardeingabe)";
    public static final String ZU_LANG = "Passwort zu lang (erste Zeile der Standardeingabe)";

    /**
     * Obergrenze in Zeichen: Gerät eine Datei ohne Zeilenumbruch oder ein Binärstrom auf die Standardeingabe, endet das
     * Kommando mit fester Meldung statt mit vollem Heap.
     */
    public static final int HOECHSTENS = 1024;

    private static char[] zeile;

    private MailPasswortEingabe() {
    }

    /**
     * Liest die erste Zeile (ohne Zeilenende) und legt sie ab.
     *
     * @return leer, wenn ein Passwort abgelegt ist, sonst die feste Meldung ({@link #FEHLT}, {@link #ZU_LANG})
     */
    public static Optional<String> vorabLesen(InputStream ein) throws IOException {
        char[] gelesen = ersteZeile(ein);
        synchronized (MailPasswortEingabe.class) {
            if (zeile != null) {
                Arrays.fill(zeile, '\0');
            }
            zeile = gelesen;
        }
        if (gelesen == null) {
            return Optional.of(ZU_LANG);
        }
        return gelesen.length > 0 ? Optional.empty() : Optional.of(FEHLT);
    }

    /** Gibt das abgelegte Passwort genau einmal heraus und leert den Halter; ohne Vorab-Lesen leer. */
    public static synchronized char[] nehmen() {
        char[] ergebnis = zeile == null ? new char[0] : zeile;
        zeile = null;
        return ergebnis;
    }

    /** Erste Zeile ohne {@code \n} bzw. {@code \r\n}; {@code null}, wenn sie länger als {@link #HOECHSTENS} ist. */
    static char[] ersteZeile(InputStream ein) throws IOException {
        Reader leser = new InputStreamReader(ein, StandardCharsets.UTF_8);
        // ein Zeichen Reserve für ein abschliessendes \r
        char[] puffer = new char[HOECHSTENS + 1];
        int laenge = 0;
        try {
            for (int zeichen = leser.read(); zeichen != -1 && zeichen != '\n'; zeichen = leser.read()) {
                if (laenge == puffer.length) {
                    return null;
                }
                puffer[laenge++] = (char) zeichen;
            }
            if (laenge > 0 && puffer[laenge - 1] == '\r') {
                laenge--;
            }
            return laenge > HOECHSTENS ? null : Arrays.copyOf(puffer, laenge);
        } finally {
            Arrays.fill(puffer, '\0');
        }
    }
}
