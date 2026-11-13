package ch.urinet.archiv.integration.adapter.in.kommando;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Passwort für {@code archiv mail einrichten}: die erste Zeile der Standardeingabe, gelesen in {@code ArchivMain.main},
 * bevor Quarkus startet. Befund Stufe A (13.11.2026): Las das Kommando die Standardeingabe erst nach dem Start (rund
 * 50 s), kam im Wegwerf-Container ({@code docker compose run -T}) nichts mehr an; las ein {@code sh} die Zeile sofort
 * und reichte sie weiter, klappte es. Das Kommando nimmt das Passwort genau einmal, danach ist der Halter leer. Das
 * Passwort erscheint nie in Log, Ausgabe oder Exception.
 */
public final class MailPasswortEingabe {

    public static final String FEHLT = "Passwort fehlt (erste Zeile der Standardeingabe)";

    private static char[] zeile;

    private MailPasswortEingabe() {
    }

    /** Liest die erste Zeile (ohne Zeilenende) und legt sie ab; {@code false}, wenn sie fehlt oder leer ist. */
    public static boolean vorabLesen(InputStream ein) throws IOException {
        char[] gelesen = ersteZeile(ein);
        synchronized (MailPasswortEingabe.class) {
            if (zeile != null) {
                Arrays.fill(zeile, '\0');
            }
            zeile = gelesen;
        }
        return gelesen.length > 0;
    }

    /** Gibt das abgelegte Passwort genau einmal heraus und leert den Halter; ohne Vorab-Lesen leer. */
    public static synchronized char[] nehmen() {
        char[] ergebnis = zeile == null ? new char[0] : zeile;
        zeile = null;
        return ergebnis;
    }

    static char[] ersteZeile(InputStream ein) throws IOException {
        Reader leser = new InputStreamReader(ein, StandardCharsets.UTF_8);
        char[] puffer = new char[64];
        int laenge = 0;
        for (int zeichen = leser.read(); zeichen != -1 && zeichen != '\n'; zeichen = leser.read()) {
            if (laenge == puffer.length) {
                char[] groesser = Arrays.copyOf(puffer, laenge * 2);
                Arrays.fill(puffer, '\0');
                puffer = groesser;
            }
            puffer[laenge++] = (char) zeichen;
        }
        if (laenge > 0 && puffer[laenge - 1] == '\r') {
            laenge--;
        }
        char[] ergebnis = Arrays.copyOf(puffer, laenge);
        Arrays.fill(puffer, '\0');
        return ergebnis;
    }
}
